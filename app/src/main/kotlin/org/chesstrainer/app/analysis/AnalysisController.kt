// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.analysis

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.chesstrainer.app.game.EngineProblem
import org.chesstrainer.app.game.PendingPromotion
import org.chesstrainer.core.engine.AnalysisEvent
import org.chesstrainer.core.engine.AnalysisRequest
import org.chesstrainer.core.engine.AnalysisUpdate
import org.chesstrainer.core.engine.EngineConfig
import org.chesstrainer.core.engine.EngineService
import org.chesstrainer.core.game.ChessGame
import org.chesstrainer.core.game.ChessGameFactory
import org.chesstrainer.core.model.ChessError
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Move
import org.chesstrainer.core.model.PieceType
import org.chesstrainer.core.model.Position
import org.chesstrainer.core.model.Square
import org.chesstrainer.core.model.Uci
import org.chesstrainer.core.rules.ChessRules

/**
 * Логіка дошки аналізу (MASTER_SPEC 6, CONTRACTS 3.4). Позиції, ходи й легальність лише через [ChessRules] і
 * [ChessGame], оцінки лише від [EngineService]; власних шахових правил тут немає. Аналіз завжди на повну силу:
 * `EngineService.analyse` не знає про рівні складності (`Strength.Full`, CONTRACTS 3.5), тож рівень партії на нього не діє.
 *
 * Усі публічні методи викликаються з головного потоку; [scope] має працювати на ньому ж.
 *
 * Рушій один на застосунок: поки відкрито дошку, екран гри віддає його через `GameController.holdEngine`.
 * Життєвий цикл (CONTRACTS 3.4): рушій запускається, коли аналіз вмикають, і зупиняється при виході з екрана
 * ([close]), у фоні ([onBackground]) і при нестачі пам'яті ([onTrimMemory]).
 */
class AnalysisController(
    private val rules: ChessRules,
    private val games: ChessGameFactory,
    private val engine: EngineService,
    private val scope: CoroutineScope,
    private val engineConfig: EngineConfig = EngineConfig(),
) {
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<AnalysisState> = _state.asStateFlow()

    /** Збільшується, коли поточний пошук стає неактуальним: його події ігноруються. */
    private var generation = 0
    private var searchJob: Job? = null
    private var stopJob: Job? = null
    private val engineLock = Mutex()

    /** Екран відкрито (між [open] і [close]). */
    private var active = false

    /** Користувач хоче, щоб аналіз працював (залишається `true` на паузі у фоні). */
    private var wanted = false

    // --- відкриття ---

    /** Відкриває дошку на позиції після [ply] півходів партії [game] (збережена, імпортована чи поточна) і запускає аналіз. */
    fun open(game: ChessGame, ply: Int = game.moves.size) {
        active = true
        _state.update { it.copy(game = game, ply = ply.coerceIn(0, game.moves.size), flipped = false) }
        positionChanged()
        startAnalysis()
    }

    /** Відкриває дошку на початковій позиції (без запуску: користувач обирає, що робити далі). */
    fun openEmpty() {
        active = true
        wanted = false
        val game = games.new()
        _state.update { it.copy(game = game, ply = 0, flipped = false) }
        positionChanged()
        stopSearch()
    }

    /** Вихід з екрана: скасовує пошук і зупиняє рушій. Чекає завершення `stop()`. */
    suspend fun close() {
        active = false
        wanted = false
        invalidate()
        withContext(NonCancellable) {
            searchJob?.cancelAndJoin()
            stopJob?.join()
            engine.stop()
        }
        _state.update { it.copy(engine = AnalysisEngine.Idle) }
    }

    // --- FEN ---

    fun onFenTextChange(text: String) {
        _state.update { it.copy(fenText = text, fenProblem = null) }
    }

    /** Завантажує позицію з поля FEN; помилку (порожньо чи `InvalidFen`) лишає в стані, дошку не чіпає. */
    fun loadFen() {
        val text = state.value.fenText.trim().replace(WHITESPACE, " ")
        if (text.isEmpty()) {
            _state.update { it.copy(fenProblem = FenProblem.EMPTY) }
            return
        }
        when (val parsed = rules.parseFen(text)) {
            is ChessResult.Ok -> {
                _state.update { it.copy(game = games.new(parsed.value), ply = 0) }
                positionChanged()
                startAnalysis()
            }
            is ChessResult.Err -> _state.update { it.copy(fenProblem = FenProblem.INVALID) }
        }
    }

    fun loadStartPosition() {
        _state.update { it.copy(game = games.new(), ply = 0) }
        positionChanged()
        startAnalysis()
    }

    // --- дошка: ходи за обидві сторони ---

    fun onSquareTap(square: Square) {
        val s = state.value
        if (s.pendingPromotion != null) return
        val selected = s.selected
        when {
            selected != null && square in s.targets -> attemptMove(selected, square)
            selected == square -> clearSelection()
            s.position.pieceAt(square)?.color == s.position.sideToMove -> select(square)
            else -> clearSelection()
        }
    }

    /** Початок перетягування: `true`, якщо на полі фігура сторони, що ходить, і її вибрано. */
    fun onDragStart(square: Square): Boolean {
        val s = state.value
        if (s.pendingPromotion != null) return false
        if (s.position.pieceAt(square)?.color != s.position.sideToMove) return false
        select(square)
        return true
    }

    fun onDrop(from: Square, to: Square?) {
        val s = state.value
        if (to == null || to == from || from != s.selected || to !in s.targets) return
        attemptMove(from, to)
    }

    fun choosePromotion(type: PieceType) {
        val pending = state.value.pendingPromotion ?: return
        playMove(Move(pending.from, pending.to, type))
    }

    fun cancelPromotion() {
        _state.update { it.copy(pendingPromotion = null) }
    }

    private fun select(square: Square) {
        val targets = rules.legalMovesFrom(state.value.position, square).map { it.to }.toSet()
        _state.update { it.copy(selected = square, targets = targets) }
    }

    private fun clearSelection() {
        _state.update { it.copy(selected = null, targets = emptySet()) }
    }

    private fun attemptMove(from: Square, to: Square) {
        if (rules.needsPromotion(state.value.position, from, to)) {
            _state.update { it.copy(pendingPromotion = PendingPromotion(from, to)) }
        } else {
            playMove(Move(from, to))
        }
    }

    /**
     * Хід на дошці. Якщо це наступний хід уже наявної лінії, лінія зберігається й курсор просто рухається вперед.
     * Інший хід у середині лінії відсікає її решту (варіанти в MVP не зберігаються).
     */
    private fun playMove(move: Move) {
        val s = state.value
        val next = s.game.moves.getOrNull(s.ply)
        if (next != null && next.move == move) {
            goTo(s.ply + 1)
            return
        }
        val base = if (s.ply < s.plyCount) s.game.undo(s.plyCount - s.ply) else s.game
        // Імпортована партія може стояти в стані, де `play` заборонено (автоматична нічия): продовжуємо з цієї позиції новою партією.
        val played = (base.play(move) as? ChessResult.Ok)?.value
            ?: (games.new(base.position).play(move) as? ChessResult.Ok)?.value
        if (played == null) {
            clearSelection()
            _state.update { it.copy(pendingPromotion = null) }
            return
        }
        _state.update { it.copy(game = played, ply = played.moves.size, pendingPromotion = null) }
        positionChanged()
        restartIfWanted()
    }

    // --- навігація по лінії ---

    fun first() = goTo(0)

    fun previous() = goTo(state.value.ply - 1)

    fun next() = goTo(state.value.ply + 1)

    fun last() = goTo(state.value.plyCount)

    fun goTo(ply: Int) {
        val s = state.value
        val target = ply.coerceIn(0, s.plyCount)
        if (target == s.ply) {
            _state.update { it.copy(pendingPromotion = null) }
            return
        }
        _state.update { it.copy(ply = target, pendingPromotion = null) }
        positionChanged()
        restartIfWanted()
    }

    fun flip() = _state.update { it.copy(flipped = !it.flipped) }

    // --- керування аналізом ---

    fun setMultiPv(value: Int) {
        val s = state.value
        val clamped = value.coerceIn(1, s.maxMultiPv)
        if (clamped == s.multiPv) return
        _state.update { it.copy(multiPv = clamped, lines = emptyList()) }
        restartIfWanted()
    }

    /** Кнопка «Старт» (і «Спробувати знову» після помилки рушія). */
    fun startAnalysis() {
        if (!active || !state.value.canAnalyse) return
        wanted = true
        launchSearch()
    }

    /** Кнопка «Стоп»: пошук скасовано (UCI `stop`), останні лінії лишаються на екрані. Рушій лишається запущеним до виходу. */
    fun stopAnalysis() {
        wanted = false
        stopSearch()
    }

    private fun restartIfWanted() {
        if (wanted && active) launchSearch() else stopSearch()
    }

    // --- життєвий цикл (CONTRACTS 3.4) ---

    /** Застосунок пішов у фон: пошук скасовано, рушій зупинено; після повернення аналіз відновиться, якщо він був увімкнений. */
    fun onBackground() {
        if (!active) return
        stopSearch()
        _state.update { it.copy(lines = emptyList()) }
        stopEngineAsync()
    }

    fun onForeground() {
        if (active && wanted) launchSearch()
    }

    /** Нестача пам'яті: аналіз і рушій зупинено, користувач може запустити знову. */
    fun onTrimMemory() {
        if (!active) return
        wanted = false
        stopSearch()
        stopEngineAsync()
    }

    private fun stopEngineAsync() {
        stopJob = scope.launch { engine.stop() }
    }

    private fun invalidate() {
        generation++
    }

    private fun stopSearch() {
        invalidate()
        searchJob?.cancel()
        _state.update { if (it.engine is AnalysisEngine.Problem) it else it.copy(engine = AnalysisEngine.Idle) }
    }

    private suspend fun ensureEngine(): ChessResult<Unit> {
        stopJob?.join()
        return engineLock.withLock { engine.start(engineConfig) }
    }

    private fun launchSearch() {
        val gen = ++generation
        val previous = searchJob
        val s = state.value
        if (!s.canAnalyse) {
            stopSearch()
            return
        }
        // Запит залежить лише від значень на цей момент: наступні зміни стану створять новий запит.
        val position = s.position
        val moves = s.game.moves.take(s.ply).map { it.uci }
        val startFen = s.game.startPosition.fen
        _state.update { it.copy(engine = AnalysisEngine.Starting, lines = emptyList()) }
        searchJob = scope.launch {
            previous?.cancelAndJoin()
            if (gen != generation) return@launch
            val started = ensureEngine()
            if (gen != generation) return@launch
            if (started is ChessResult.Err) {
                fail(gen, started.error)
                return@launch
            }
            val maxMultiPv = (engine.capabilities?.maxMultiPv ?: MAX_MULTI_PV).coerceIn(1, MAX_MULTI_PV)
            var multiPv = state.value.multiPv
            if (maxMultiPv != state.value.maxMultiPv || multiPv > maxMultiPv) {
                multiPv = multiPv.coerceAtMost(maxMultiPv)
                _state.update { it.copy(maxMultiPv = maxMultiPv, multiPv = multiPv) }
            }
            _state.update { it.copy(engine = AnalysisEngine.Running) }
            engine.analyse(AnalysisRequest(startFen, moves, limit = null, multiPv = multiPv)).collect { event ->
                if (gen != generation) return@collect
                when (event) {
                    is AnalysisEvent.Update -> applyUpdate(position, event.update)
                    AnalysisEvent.Finished -> _state.update { it.copy(engine = AnalysisEngine.Idle) }
                    is AnalysisEvent.Failed -> fail(gen, event.error)
                }
            }
        }
    }

    private fun fail(gen: Int, error: ChessError) {
        if (gen != generation) return
        when (error) {
            // Скасування нашим новим запитом відсіяно за `generation`; якщо воно все ж дійшло, пошук зупинено ззовні.
            ChessError.Cancelled, ChessError.NoLegalMoves ->_state.update { it.copy(engine = AnalysisEngine.Idle) }
            else -> {
                wanted = false
                _state.update { it.copy(engine = AnalysisEngine.Problem(problemOf(error))) }
            }
        }
    }

    private fun applyUpdate(position: Position, update: AnalysisUpdate) {
        val line = AnalysisLine(update.multiPv, update.evaluation, update.depth, formatPv(rules, position, update.pv))
        _state.update { s ->
            val merged = (s.lines.filter { it.rank != line.rank } + line).sortedBy { it.rank }
            s.copy(lines = merged.filter { it.rank <= s.multiPv })
        }
    }

    // --- допоміжне ---

    /** Спільне для всіх змін позиції: скидає вибір, лінії, вердикт, шах і текст FEN. */
    private fun positionChanged() {
        val s = state.value
        val position = s.position
        _state.update {
            it.copy(
                selected = null,
                targets = emptySet(),
                pendingPromotion = null,
                lines = emptyList(),
                fenText = position.fen.value,
                fenProblem = null,
                verdict = verdictOf(position),
                checkSquare = checkSquareOf(position),
            )
        }
    }

    private fun verdictOf(position: Position): PositionVerdict = when {
        rules.isCheckmate(position) -> PositionVerdict.Checkmate(winner = position.sideToMove.opposite)
        rules.isStalemate(position) -> PositionVerdict.Stalemate
        rules.isInsufficientMaterial(position) -> PositionVerdict.InsufficientMaterial
        else -> PositionVerdict.Playable
    }

    private fun checkSquareOf(position: Position): Square? {
        if (!rules.isInCheck(position)) return null
        return (0..63).map { Square(it) }.firstOrNull {
            val piece = position.pieceAt(it)
            piece?.type == PieceType.KING && piece.color == position.sideToMove
        }
    }

    private fun problemOf(error: ChessError): EngineProblem = when (error) {
        is ChessError.EngineUnavailable -> EngineProblem.UNAVAILABLE
        is ChessError.EngineCrashed -> EngineProblem.CRASHED
        else -> EngineProblem.UNEXPECTED
    }

    private fun initialState(): AnalysisState {
        val game = games.new()
        return AnalysisState(game = game, ply = 0, fenText = game.position.fen.value)
    }

    companion object {
        /** Скільки ліній показуємо щонайбільше (MASTER_SPEC 6: MultiPV 1–3). */
        const val MAX_MULTI_PV = 3
        private val WHITESPACE = Regex("\\s+")
    }
}

/**
 * Основна лінія рушія як текст SAN із номерами ходів: «12. Nf3 Nc6 13. Bb5» або «12... Nc6 13. Bb5».
 * Обривається на першому ході, який правила не приймають, і не довше за [maxPlies] півходів.
 */
fun formatPv(rules: ChessRules, from: Position, pv: List<Uci>, maxPlies: Int = 12): String {
    var position = from
    val out = StringBuilder()
    var number = position.fullmoveNumber
    var first = true
    for (uci in pv.take(maxPlies)) {
        val move = (rules.parseUci(position, uci.value) as? ChessResult.Ok)?.value ?: break
        val san = rules.toSan(position, move)
        val white = position.sideToMove == org.chesstrainer.core.model.Color.WHITE
        if (out.isNotEmpty()) out.append(' ')
        if (white) out.append(number).append(". ") else if (first) out.append(number).append("... ")
        out.append(san)
        position = (rules.apply(position, move) as? ChessResult.Ok)?.value ?: break
        if (!white) number++
        first = false
    }
    return out.toString()
}
