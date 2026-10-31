// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.game

import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.chesstrainer.app.storage.GameSaver
import org.chesstrainer.app.storage.ResumableGame
import org.chesstrainer.core.engine.DifficultyLevel
import org.chesstrainer.core.engine.EngineCapabilities
import org.chesstrainer.core.engine.EngineConfig
import org.chesstrainer.core.engine.EngineService
import org.chesstrainer.core.engine.MoveRequest
import org.chesstrainer.core.game.ChessGame
import org.chesstrainer.core.game.ChessGameFactory
import org.chesstrainer.core.model.ChessError
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Color
import org.chesstrainer.core.model.Move
import org.chesstrainer.core.model.PieceType
import org.chesstrainer.core.model.Position
import org.chesstrainer.core.model.Square
import org.chesstrainer.core.model.Uci
import org.chesstrainer.core.rules.ChessRules

/**
 * Логіка екрана «людина проти рушія». Правила й стан партії лише через [ChessRules] і [ChessGame],
 * рушій лише через [EngineService]; власних шахових правил тут немає.
 *
 * Усі публічні методи викликаються з одного потоку (головного); [scope] має працювати на ньому ж.
 * Рушій запускається ліниво й повторно: кожен запит ходу сам викликає `start()` (ідемпотентний, CONTRACTS 3.4),
 * тож після паузи у фоні чи збою додаткового відновлення не потрібно.
 */
class GameController(
    private val rules: ChessRules,
    private val games: ChessGameFactory,
    private val engine: EngineService,
    private val scope: CoroutineScope,
    private val levelFor: (EngineCapabilities?) -> DifficultyLevel,
    private val engineConfig: EngineConfig = EngineConfig(),
    private val saver: GameSaver = GameSaver.None,
    playerColor: Color = Color.WHITE,
) {
    private val _state = MutableStateFlow(newState(playerColor))
    val state: StateFlow<GameUiState> = _state.asStateFlow()

    /** Збільшується, коли поточний пошук стає неактуальним (скасування, нова партія, пауза). */
    private var generation = 0
    private var engineJob: Job? = null
    private var warmJob: Job? = null
    private var stopJob: Job? = null
    private var paused = false

    private val engineLock = Mutex()
    private var pendingNewGame = false

    /** Збережена партія, яку запропоновано продовжити (UI бачить лише [GameUiState.resumeOfferPlies]). */
    private var resumeOffer: ResumableGame? = null

    private fun newState(color: Color): GameUiState {
        val game = games.new()
        return GameUiState(game = game, playerColor = color, checkSquare = checkSquareOf(game.position))
    }

    // --- життєвий цикл (CONTRACTS 3.4) ---

    /** Вхід на екран: запуск рушія; якщо людина грає чорними, рушій одразу ходить. */
    fun start() {
        warmUp()
        if (state.value.engineToMove) requestEngineMove()
    }

    /** Застосунок пішов у фон: пошук скасовано, рушій зупинено. Повернення: [onForeground]. */
    fun onBackground() {
        if (paused) return
        paused = true
        persist()
        invalidateSearch()
        warmJob?.cancel()
        _state.update { if (it.engine is EngineStatus.Problem) it else it.copy(engine = EngineStatus.Idle) }
        stopEngineAsync()
    }

    fun onForeground() {
        if (!paused) return
        paused = false
        val s = state.value
        if (s.engine is EngineStatus.Problem || s.isOver) return
        warmUp()
        if (s.engineToMove) requestEngineMove()
    }

    /** Нестача пам'яті: зупиняємо рушій, лише якщо він зараз не веде пошук; наступний хід запустить його знову. */
    fun onTrimMemory() {
        val s = state.value
        if (s.engine is EngineStatus.Thinking || s.engine is EngineStatus.Starting) return
        warmJob?.cancel()
        _state.update { if (it.engine is EngineStatus.Problem) it else it.copy(engine = EngineStatus.Idle) }
        stopEngineAsync()
    }

    /** Вихід з екрана: скасовує все й зупиняє рушій. Чекає завершення `stop()`. */
    suspend fun shutdown() {
        invalidateSearch()
        warmJob?.cancel()
        withContext(NonCancellable) {
            stopJob?.join()
            engine.stop()
        }
    }

    // --- ходи людини ---

    fun onSquareTap(square: Square) {
        val s = state.value
        if (!s.humanToMove || s.pendingPromotion != null) return
        val selected = s.selected
        when {
            selected != null && square in s.targets -> attemptMove(selected, square)
            selected == square -> _state.update { it.copy(selected = null, targets = emptySet()) }
            s.position.pieceAt(square)?.color == s.playerColor -> select(square)
            else -> _state.update { it.copy(selected = null, targets = emptySet()) }
        }
    }

    /** Початок перетягування: `true`, якщо на полі власна фігура й її вибрано. */
    fun onDragStart(square: Square): Boolean {
        val s = state.value
        if (!s.humanToMove || s.pendingPromotion != null) return false
        if (s.position.pieceAt(square)?.color != s.playerColor) return false
        select(square)
        return true
    }

    /** Кінець перетягування. Невдалий скид (за межами дошки чи не на легальне поле) лишає фігуру вибраною. */
    fun onDrop(from: Square, to: Square?) {
        val s = state.value
        if (to == null || to == from || from != s.selected || to !in s.targets) return
        attemptMove(from, to)
    }

    fun choosePromotion(type: PieceType) {
        val pending = state.value.pendingPromotion ?: return
        playHuman(Move(pending.from, pending.to, type))
    }

    fun cancelPromotion() {
        _state.update { it.copy(pendingPromotion = null) }
    }

    private fun select(square: Square) {
        val targets = rules.legalMovesFrom(state.value.position, square).map { it.to }.toSet()
        _state.update { it.copy(selected = square, targets = targets) }
    }

    private fun attemptMove(from: Square, to: Square) {
        if (rules.needsPromotion(state.value.position, from, to)) {
            _state.update { it.copy(pendingPromotion = PendingPromotion(from, to)) }
        } else {
            playHuman(Move(from, to))
        }
    }

    private fun playHuman(move: Move) {
        val s = state.value
        when (val result = s.game.play(move)) {
            is ChessResult.Ok -> {
                commit(result.value)
                if (state.value.engineToMove) requestEngineMove()
            }
            is ChessResult.Err -> _state.update {
                it.copy(selected = null, targets = emptySet(), pendingPromotion = null)
            }
        }
    }

    // --- дії над партією ---

    fun undo() {
        val s = state.value
        if (!s.canUndo) return
        invalidateSearch()
        commit(s.game.undo(s.undoPlies))
    }

    fun resign() {
        val s = state.value
        if (s.isOver || s.awaitingResume) return
        invalidateSearch()
        _state.update {
            it.copy(
                endedBy = GameOutcome.Resigned(winner = it.engineColor),
                selected = null,
                targets = emptySet(),
                pendingPromotion = null,
                engine = if (it.engine is EngineStatus.Problem) it.engine else EngineStatus.Idle,
            )
        }
        persist()
    }

    fun claimDraw() {
        val s = state.value
        if (s.claimReasons.isEmpty()) return
        invalidateSearch()
        _state.update { it.copy(endedBy = GameOutcome.DrawClaimed, selected = null, targets = emptySet()) }
        persist()
    }

    fun dismissClaim() {
        _state.update { it.copy(claimDismissedAtPly = it.game.moves.size) }
    }

    fun flipBoard() {
        _state.update { it.copy(flipped = !it.flipped) }
    }

    /** Нова партія; рушій лишається запущеним, між партіями діє `newGame()` (CONTRACTS 3.4). */
    fun newGame(playerColor: Color) {
        if (state.value.awaitingResume) return
        invalidateSearch()
        saver.abandonActive()
        val fresh = newState(playerColor)
        val keepProblem = state.value.engine as? EngineStatus.Problem
        _state.value = fresh.copy(engine = keepProblem ?: EngineStatus.Starting)
        pendingNewGame = true
        if (keepProblem == null) warmUp()
        if (keepProblem == null && fresh.engineToMove) requestEngineMove()
    }

    fun retryEngine() {
        val s = state.value
        if (s.engine !is EngineStatus.Problem) return
        _state.update { it.copy(engine = EngineStatus.Starting) }
        warmUp()
        if (s.engineToMove) requestEngineMove()
    }

    // --- рушій ---

    private fun invalidateSearch() {
        generation++
        engineJob?.cancel()
        engineJob = null
    }

    private fun stopEngineAsync() {
        stopJob = scope.launch { engine.stop() }
    }

    /** `start()` ідемпотентний; запити, що прийшли під час запуску, чекають його результату. */
    private suspend fun ensureEngine(): ChessResult<Unit> {
        stopJob?.join()
        return engineLock.withLock {
            val started = engine.start(engineConfig)
            if (started is ChessResult.Ok && pendingNewGame) {
                engine.newGame()
                pendingNewGame = false
            }
            started
        }
    }

    private fun warmUp() {
        warmJob?.cancel()
        _state.update { if (it.engine is EngineStatus.Thinking) it else it.copy(engine = EngineStatus.Starting) }
        warmJob = scope.launch {
            val result = ensureEngine()
            _state.update { s ->
                when {
                    s.engine !is EngineStatus.Starting -> s
                    result is ChessResult.Err -> s.copy(engine = EngineStatus.Problem(problemOf(result.error)))
                    else -> s.copy(engine = EngineStatus.Idle)
                }
            }
        }
    }

    private fun requestEngineMove() {
        invalidateSearch()
        val gen = generation
        _state.update { it.copy(engine = EngineStatus.Thinking) }
        engineJob = scope.launch { runEngineTurn(gen) }
    }

    private suspend fun runEngineTurn(gen: Int) {
        val game = state.value.game
        val started = ensureEngine()
        if (gen != generation) return
        if (started is ChessResult.Err) return fail(problemOf(started.error))

        val level = levelFor(engine.capabilities)
        val mark = TimeSource.Monotonic.markNow()
        val request = MoveRequest(game.startPosition.fen, game.moves.map { it.uci }, level)
        val result = engine.bestMove(request)
        if (gen != generation) return

        when (result) {
            is ChessResult.Ok -> {
                val wait = level.minReplyDelayMs - mark.elapsedNow().inWholeMilliseconds
                if (wait > 0) delay(wait)
                if (gen != generation) return
                applyEngineMove(game, result.value.best)
            }
            is ChessResult.Err -> when (result.error) {
                // Позиція без ходів: партія вже завершена, статус покаже UI.
                ChessError.NoLegalMoves -> _state.update { it.copy(engine = EngineStatus.Idle) }
                else -> fail(problemOf(result.error))
            }
        }
    }

    private fun applyEngineMove(game: ChessGame, best: Uci) {
        val move = (rules.parseUci(game.position, best.value) as? ChessResult.Ok)?.value
            ?: return fail(EngineProblem.UNEXPECTED)
        when (val played = game.play(move)) {
            is ChessResult.Ok -> commit(played.value)
            is ChessResult.Err -> fail(EngineProblem.UNEXPECTED)
        }
    }

    private fun fail(problem: EngineProblem) {
        _state.update { it.copy(engine = EngineStatus.Problem(problem)) }
    }

    /** `Cancelled` без нашого скасування (його відсікає перевірка `generation`) означає обрив ззовні: даємо повторити. */
    private fun problemOf(error: ChessError): EngineProblem = when (error) {
        is ChessError.EngineUnavailable -> EngineProblem.UNAVAILABLE
        is ChessError.EngineCrashed -> EngineProblem.CRASHED
        else -> EngineProblem.UNEXPECTED
    }

    private fun commit(game: ChessGame) {
        _state.update {
            it.copy(
                game = game,
                selected = null,
                targets = emptySet(),
                pendingPromotion = null,
                checkSquare = checkSquareOf(game.position),
                engine = if (it.engine is EngineStatus.Problem) it.engine else EngineStatus.Idle,
            )
        }
        persist()
    }

    /** Збереження після кожної зміни партії; поки йде перевірка збереженої партії, нічого не пишемо. */
    private fun persist() {
        val s = state.value
        if (!s.awaitingResume) saver.save(s)
    }

    // --- відновлення збереженої партії (CONTRACTS 5) ---

    /**
     * Запуск застосунку: шукає незавершену партію. До рішення користувача ходи заблоковані
     * ([GameUiState.awaitingResume]); пошкоджені дані або помилка сховища дають звичайну нову партію.
     */
    fun checkForSavedGame() {
        _state.update { it.copy(awaitingResume = true) }
        scope.launch {
            val found = try {
                saver.findResumable()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (found == null) {
                finishResumeCheck()
            } else {
                resumeOffer = found
                _state.update { it.copy(resumeOfferPlies = found.game.moves.size) }
            }
        }
    }

    /** «Продовжити»: позиція, колір людини й ходи беруться зі збереженої партії; якщо черга рушія, він ходить. */
    fun continueSavedGame() {
        val offer = resumeOffer ?: return
        resumeOffer = null
        invalidateSearch()
        saver.resume(offer)
        pendingNewGame = true
        _state.update {
            it.copy(
                game = offer.game,
                playerColor = offer.playerColor,
                flipped = false,
                selected = null,
                targets = emptySet(),
                pendingPromotion = null,
                endedBy = null,
                claimDismissedAtPly = null,
                awaitingResume = false,
                resumeOfferPlies = null,
                checkSquare = checkSquareOf(offer.game.position),
            )
        }
        if (!paused && state.value.engineToMove) requestEngineMove()
    }

    /** «Нова партія»: збережену партію закрито як залишену, але не видалено. */
    fun declineSavedGame() {
        val offer = resumeOffer ?: return
        resumeOffer = null
        saver.discard(offer)
        finishResumeCheck()
    }

    private fun finishResumeCheck() {
        _state.update { it.copy(awaitingResume = false, resumeOfferPlies = null) }
        if (!paused && state.value.engineToMove) requestEngineMove()
    }

    private fun checkSquareOf(position: Position): Square? {
        if (!rules.isInCheck(position)) return null
        return (0..63).map { Square(it) }.firstOrNull {
            val piece = position.pieceAt(it)
            piece?.type == PieceType.KING && piece.color == position.sideToMove
        }
    }
}
