// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.puzzle

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.chesstrainer.app.content.ContentStatus
import org.chesstrainer.app.game.PendingPromotion
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Move
import org.chesstrainer.core.model.PieceType
import org.chesstrainer.core.model.Position
import org.chesstrainer.core.model.Square
import org.chesstrainer.core.rules.ChessRules
import org.chesstrainer.learning.content.ThemeCatalog
import org.chesstrainer.learning.puzzle.Hint
import org.chesstrainer.learning.puzzle.Puzzle
import org.chesstrainer.learning.puzzle.PuzzleAttempt
import org.chesstrainer.learning.puzzle.PuzzleFilter
import org.chesstrainer.learning.puzzle.PuzzleMode
import org.chesstrainer.learning.puzzle.PuzzleProgressRepository
import org.chesstrainer.learning.puzzle.PuzzleRequest
import org.chesstrainer.learning.puzzle.PuzzleService
import org.chesstrainer.learning.puzzle.PuzzleSession
import org.chesstrainer.learning.puzzle.PuzzleState
import org.chesstrainer.learning.puzzle.PuzzleStep
import org.chesstrainer.learning.puzzle.ThemeId

/**
 * Логіка екрана задач (без Compose). Шахових правил тут немає: лише виклики [ChessRules] для підсвічування
 * ходів, [PuzzleService] і [PuzzleSession].
 *
 * Правила (CONTRACTS 4.1, v0.16):
 * - підсумок «розв'язано»/«з помилкою» береться зі `PuzzleSession.state`, а не з `Correct.lineCompleted`;
 * - `hint()` після завершення кидає виняток, тож [PuzzlePlay.canHint] блокує підказку;
 * - результат записується один раз: коли лінію завершено, розв'язок відкрито або гравець вийшов із задачі,
 *   яка вже має стан FAILED (щоб вона потрапила в «Повторити помилки»). Вихід із чистої незавершеної задачі нічого не пише.
 *
 * Не потокобезпечний: усі виклики з головного потоку ([scope] це `viewModelScope`).
 *
 * @param contentStatus стан контенту (завантаження йде поза головним потоком).
 * @param replyDelayMs пауза перед відповіддю суперника, щоб гравець бачив свій хід.
 * @param onError помилка запису спроби (гра триває, результат цієї спроби може бути втрачено).
 */
class PuzzleController(
    private val service: PuzzleService,
    private val progress: PuzzleProgressRepository,
    private val rules: ChessRules,
    private val contentStatus: suspend () -> ContentStatus,
    private val scope: CoroutineScope,
    private val clock: () -> Instant = Instant::now,
    private val replyDelayMs: Long = 450,
    private val onError: (Throwable) -> Unit = {},
) {
    private val _state = MutableStateFlow(PuzzleUiState())
    val state: StateFlow<PuzzleUiState> = _state

    /** Теми для вибору в порядку каталогу: спершу тактичні, потім фази партії. */
    val themes: List<ThemeId> = ThemeCatalog.accepted.map { ThemeId(it.id) }

    private var session: PuzzleSession? = null
    private var recorded = false
    private var recordJob: Job? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch { progress.stats().collect { s -> _state.update { it.copy(stats = s) } } }
        scope.launch {
            val availability = when (contentStatus()) {
                is ContentStatus.Ready -> ContentAvailability.READY
                is ContentStatus.Unavailable -> ContentAvailability.UNAVAILABLE
            }
            _state.update { it.copy(availability = availability) }
        }
    }

    // --- вибір ---

    fun selectTheme(theme: ThemeId?) = updateSetup { it.copy(theme = theme) }

    fun selectLevel(level: PuzzleLevel) = updateSetup { it.copy(level = level) }

    fun selectMode(mode: PuzzleMode) = updateSetup { it.copy(mode = mode) }

    private fun updateSetup(f: (PuzzleSetup) -> PuzzleSetup) =
        _state.update { if (it.play != null) it else it.copy(setup = f(it.setup), notice = null) }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    // --- початок і кінець задачі ---

    /** Починає задачу за поточним вибором. Нічого не робить, якщо контент недоступний або пошук уже триває. */
    fun startPuzzle() {
        val s = _state.value
        if (s.loading || s.play != null || s.availability == ContentAvailability.UNAVAILABLE) return
        _state.update { it.copy(loading = true, notice = null) }
        scope.launch { loadNext() }
    }

    /** «Наступна задача»: завершує поточну (запис результату) і бере наступну за тим самим вибором. */
    fun nextPuzzle() {
        if (_state.value.loading) return
        leavePlay()
        _state.update { it.copy(loading = true, notice = null) }
        scope.launch { loadNext() }
    }

    /** Повернення до вибору. */
    fun backToMenu() {
        leavePlay()
        _state.update { it.copy(play = null, loading = false) }
    }

    private suspend fun loadNext() {
        recordJob?.join() // щоб NEW не видав щойно зіграну задачу
        val setup = _state.value.setup
        val request = PuzzleRequest(
            PuzzleFilter(themes = setOfNotNull(setup.theme), ratingRange = setup.level.range),
            setup.mode,
        )
        try {
            val puzzle = service.next(request)
            if (puzzle == null) {
                val notice = if (setup.mode == PuzzleMode.NEW) MenuNotice.NO_NEW_PUZZLES else MenuNotice.NO_FAILED_PUZZLES
                _state.update { it.copy(loading = false, play = null, notice = notice) }
                return
            }
            open(puzzle)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            // Задача з пошкодженою позицією чи розв'язком: не падаємо, показуємо повідомлення.
            session = null
            _state.update { it.copy(loading = false, play = null, notice = MenuNotice.PUZZLE_UNREADABLE) }
        }
    }

    private fun open(puzzle: Puzzle) {
        val newSession = service.session(puzzle)
        session = newSession
        recorded = false
        val position = newSession.position
        _state.update {
            it.copy(
                loading = false,
                play = PuzzlePlay(
                    puzzle = puzzle,
                    playerColor = position.sideToMove,
                    position = position,
                    checkSquare = checkSquareOf(position),
                ),
            )
        }
    }

    /** Записує результат, якщо задача вже FAILED/завершена і запису ще не було; чисту незавершену задачу не чіпає. */
    private fun leavePlay() {
        val s = session ?: return
        if (s.state != PuzzleState.IN_PROGRESS) record(s)
        session = null
    }

    private fun record(s: PuzzleSession) {
        if (recorded) return
        recorded = true
        val attempt = PuzzleAttempt(s.result(), clock())
        val previous = recordJob
        recordJob = scope.launch {
            previous?.join()
            try {
                progress.recordAttempt(attempt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    // --- ходи ---

    fun onSquareTap(square: Square) {
        val play = _state.value.play ?: return
        if (!play.canMove) return
        val selected = play.selected
        when {
            selected != null && square in play.targets -> attemptMove(selected, square)
            selected == square -> updatePlay { it.copy(selected = null, targets = emptySet()) }
            play.position.pieceAt(square)?.color == play.playerColor -> select(square)
            else -> updatePlay { it.copy(selected = null, targets = emptySet()) }
        }
    }

    /** Початок перетягування: `true`, якщо на полі власна фігура й її вибрано. */
    fun onDragStart(square: Square): Boolean {
        val play = _state.value.play ?: return false
        if (!play.canMove) return false
        if (play.position.pieceAt(square)?.color != play.playerColor) return false
        select(square)
        return true
    }

    fun onDrop(from: Square, to: Square?) {
        val play = _state.value.play ?: return
        if (!play.canMove || to == null || to == from || from != play.selected || to !in play.targets) return
        attemptMove(from, to)
    }

    fun choosePromotion(type: PieceType) {
        val pending = _state.value.play?.pendingPromotion ?: return
        submit(Move(pending.from, pending.to, type))
    }

    fun cancelPromotion() = updatePlay { it.copy(pendingPromotion = null) }

    private fun select(square: Square) {
        val position = _state.value.play?.position ?: return
        val targets = rules.legalMovesFrom(position, square).map { it.to }.toSet()
        updatePlay { it.copy(selected = square, targets = targets, wrongMove = false) }
    }

    private fun attemptMove(from: Square, to: Square) {
        val position = _state.value.play?.position ?: return
        if (rules.needsPromotion(position, from, to)) {
            updatePlay { it.copy(pendingPromotion = PendingPromotion(from, to)) }
        } else {
            submit(Move(from, to))
        }
    }

    private fun submit(move: Move) {
        val s = session ?: return
        val before = _state.value.play ?: return
        updatePlay { it.copy(busy = true, pendingPromotion = null, selected = null, targets = emptySet(), hint = null) }
        scope.launch {
            when (val step = s.submit(move)) {
                is PuzzleStep.Wrong -> updatePlay {
                    it.copy(busy = false, wrongMove = true, state = s.state, wrongMoves = it.wrongMoves + 1)
                }
                is PuzzleStep.Finished -> updatePlay { it.copy(busy = false) }
                is PuzzleStep.Correct -> onCorrect(s, before.position, move, step)
            }
        }
    }

    private suspend fun onCorrect(s: PuzzleSession, positionBefore: Position, move: Move, step: PuzzleStep.Correct) {
        val reply = step.opponentReply
        if (reply != null) {
            // Спершу видно хід гравця, потім відповідь суперника.
            val mid = (rules.apply(positionBefore, move) as? ChessResult.Ok)?.value
            if (mid != null) {
                updatePlay { it.copy(position = mid, lastMove = move, checkSquare = checkSquareOf(mid), wrongMove = false) }
                if (replyDelayMs > 0) delay(replyDelayMs)
            }
        }
        val position = s.position
        updatePlay {
            it.copy(
                position = position,
                lastMove = reply ?: move,
                checkSquare = checkSquareOf(position),
                wrongMove = false,
                state = s.state,
                busy = false,
            )
        }
        // Кінець лінії це ще не «розв'язано»: підсумок бере `state` (SOLVED або FAILED).
        if (step.lineCompleted) {
            updatePlay { it.copy(over = true, state = s.state) }
            record(s)
        }
    }

    // --- підказки й розв'язок ---

    fun hint() {
        val s = session ?: return
        val play = _state.value.play ?: return
        if (!play.canHint) return
        val view = when (val h = s.hint()) {
            is Hint.Nudge -> HintView(1, theme = h.theme)
            is Hint.Direction -> HintView(2, from = h.fromSquare)
            is Hint.FullMove -> HintView(3, from = h.move.from, move = h.move)
        }
        updatePlay {
            it.copy(
                hint = view,
                hintsUsed = s.result().hintsUsed,
                state = s.state,
                selected = null,
                targets = emptySet(),
                wrongMove = false,
            )
        }
    }

    /** «Показати розв'язок»: задача FAILED (якщо ще йшла), далі гравець гортає ходи розв'язку. */
    fun revealSolution() {
        val s = session ?: return
        val play = _state.value.play ?: return
        if (play.busy) return
        val solution = s.revealSolution()
        val review = buildReview(s.puzzle, solution.map { it.value })
        updatePlay {
            it.copy(
                over = true,
                state = s.state,
                hint = null,
                selected = null,
                targets = emptySet(),
                pendingPromotion = null,
                wrongMove = false,
                review = review,
            )
        }
        record(s)
    }

    fun reviewStep(delta: Int) {
        updatePlay { play ->
            val r = play.review ?: return@updatePlay play
            val i = (r.index + delta).coerceIn(0, r.moves.size)
            play.copy(review = r.copy(index = i))
        }
    }

    private fun buildReview(puzzle: Puzzle, uci: List<String>): SolutionReview? {
        var position = (rules.parseFen(puzzle.startFen.value) as? ChessResult.Ok)?.value ?: return null
        val positions = arrayListOf(position)
        val moves = ArrayList<Move>()
        val san = ArrayList<String>()
        for (text in uci) {
            val move = (rules.parseUci(position, text) as? ChessResult.Ok)?.value ?: break
            val next = (rules.apply(position, move) as? ChessResult.Ok)?.value ?: break
            san += rules.toSan(position, move)
            moves += move
            positions += next
            position = next
        }
        return SolutionReview(positions, moves, san, index = 0)
    }

    private fun updatePlay(f: (PuzzlePlay) -> PuzzlePlay) =
        _state.update { s -> s.play?.let { s.copy(play = f(it)) } ?: s }

    private fun checkSquareOf(position: Position): Square? {
        if (!rules.isInCheck(position)) return null
        return (0..63).map { Square(it) }.firstOrNull {
            val piece = position.pieceAt(it)
            piece?.type == PieceType.KING && piece.color == position.sideToMove
        }
    }
}
