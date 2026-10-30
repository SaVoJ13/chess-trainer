// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.engine

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.chesstrainer.core.engine.AnalysisEvent
import org.chesstrainer.core.engine.AnalysisRequest
import org.chesstrainer.core.engine.AnalysisUpdate
import org.chesstrainer.core.engine.EngineCapabilities
import org.chesstrainer.core.engine.EngineConfig
import org.chesstrainer.core.engine.EngineMove
import org.chesstrainer.core.engine.EngineService
import org.chesstrainer.core.engine.EngineState
import org.chesstrainer.core.engine.MoveRequest
import org.chesstrainer.core.engine.SearchLimit
import org.chesstrainer.core.engine.Strength
import org.chesstrainer.core.model.Color
import org.chesstrainer.core.model.ChessError
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Evaluation
import org.chesstrainer.core.model.Fen
import org.chesstrainer.core.model.Position
import org.chesstrainer.core.model.Uci
import org.chesstrainer.core.rules.ChessRules

/**
 * Реалізація [EngineService] поверх UCI-каналу [UciTransport] (CONTRACTS 3.4, v0.6/v0.7).
 * Не залежить від того, де працює рушій: [InProcessEngineService] і [RemoteEngineService] відрізняються
 * лише транспортом, тому проходять один набір контрактних тестів.
 */
open class UciEngineService internal constructor(
    private val transport: UciTransport,
    private val rules: ChessRules,
    private val timeouts: EngineTimeouts = EngineTimeouts(),
) : EngineService {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<EngineState>(EngineState.Stopped)
    private val guard = Any()
    private val lock = Mutex()

    @Volatile private var _capabilities: EngineCapabilities? = null
    @Volatile private var session: Session? = null
    @Volatile private var current: Search? = null
    private var startResult: CompletableDeferred<ChessResult<Unit>>? = null

    /** Закриття транспорту виконується поза викликачем (воно може зависнути); наступний запуск його чекає. */
    @Volatile private var pendingClose: Deferred<*>? = null

    override val state: StateFlow<EngineState> = _state.asStateFlow()
    override val capabilities: EngineCapabilities? get() = _capabilities

    // --- життєвий цикл ---

    override suspend fun start(config: EngineConfig): ChessResult<Unit> {
        // Starting: чекаємо результат поточного запуску й повертаємо той самий (v0.6).
        var joined: CompletableDeferred<ChessResult<Unit>>? = null
        var started: CompletableDeferred<ChessResult<Unit>>? = null
        synchronized(guard) {
            when (_state.value) {
                EngineState.Ready, EngineState.Busy -> return ChessResult.Ok(Unit)
                EngineState.Starting -> joined = startResult
                EngineState.Stopped, is EngineState.Failed -> {
                    started = CompletableDeferred<ChessResult<Unit>>().also { startResult = it }
                    _state.value = EngineState.Starting
                }
            }
        }
        joined?.let { return it.await() }
        val pending = started!!
        // Запуск не переривається скасуванням викликача: інакше стан застряг би в Starting.
        val result = withContext(NonCancellable) { startBounded(config) }
        synchronized(guard) { startResult = null }
        pending.complete(result)
        return result
    }

    /** Запуск не довший за [EngineTimeouts.startMs]: блокувальний `open` іде окремо, зависання стає помилкою. */
    private suspend fun startBounded(config: EngineConfig): ChessResult<Unit> {
        EngineTrace.mark("start.begin")
        withTimeoutOrNull(timeouts.closeWaitMs) { pendingClose?.await() }
        val session = Session()
        this.session = session
        val job = scope.async(Dispatchers.IO) { doStart(session, config) }
        val result = withTimeoutOrNull(timeouts.startMs) { job.await() }
        if (result != null) {
            EngineTrace.mark("start.end", if (result is ChessResult.Ok) "ok" else "err")
            return result
        }
        job.cancel()
        EngineTrace.mark("start.timeout")
        return failStart(session, ChessError.EngineUnavailable("engine did not start in ${timeouts.startMs} ms"))
    }

    private suspend fun doStart(session: Session, config: EngineConfig): ChessResult<Unit> {
        val evalFile = try {
            transport.open(session.listener)
        } catch (t: Throwable) {
            return failStart(session, ChessError.EngineUnavailable("cannot open engine: $t"))
        }
        session.dispatcher = scope.launch { dispatch(session) }
        session.lastLineAt = System.nanoTime()

        val handshake = mutableListOf<String>()
        val uciOk = exchange(session, listOf("uci"), UCI_TIMEOUT_MS, collect = { handshake += it }) { it == "uciok" }
        if (uciOk != Exchange.Done) return failStart(session, exchangeError(uciOk, "uci"))

        val caps = parseCapabilities(handshake)
            ?: return failStart(session, ChessError.EngineUnavailable("engine did not report id and options"))
        val threads = config.threads.coerceIn(1, caps.threadsMax)
        val hashMb = config.hashMb.coerceIn(1, caps.hashMaxMb)
        val errors = mutableListOf<String>()
        val ready = exchange(
            session,
            listOf(
                "setoption name Threads value $threads",
                "setoption name Hash value $hashMb",
                "setoption name EvalFile value $evalFile",
                "isready",
            ),
            timeouts.startMs,
            collect = { if (it.contains("ERROR", ignoreCase = true)) errors += it },
        ) { it == "readyok" }
        if (ready != Exchange.Done) return failStart(session, exchangeError(ready, "isready"))
        if (errors.isNotEmpty()) {
            return failStart(session, ChessError.EngineUnavailable("engine reported: ${errors.first()}"))
        }
        _capabilities = caps
        synchronized(guard) {
            // Збій або таймаут під час запуску вже міг перевести стан у Failed (див. onClosed, startBounded).
            if (_state.value == EngineState.Starting && this.session === session) _state.value = EngineState.Ready
        }
        if (_state.value == EngineState.Ready && this.session === session) {
            session.watchdog = scope.launch { watch(session) }
        }
        return if (_state.value == EngineState.Ready) {
            ChessResult.Ok(Unit)
        } else {
            ChessResult.Err((_state.value as? EngineState.Failed)?.error ?: ChessError.EngineUnavailable("engine stopped"))
        }
    }

    private fun failStart(session: Session, error: ChessError): ChessResult<Unit> {
        session.expectedClose.set(true)
        closeTransportAsync()
        session.dispatcher?.cancel()
        session.watchdog?.cancel()
        synchronized(guard) { if (this.session === session) _state.value = EngineState.Failed(error) }
        EngineTrace.mark("start.failed", error::class.simpleName)
        return ChessResult.Err(error)
    }

    /** Закриває транспорт у фоні: при зависанні `close` блокується, і викликач не повинен його чекати. */
    private fun closeTransportAsync(): Deferred<*> {
        val job = scope.async(Dispatchers.IO) { runCatching { transport.close() } }
        pendingClose = job
        return job
    }

    private fun exchangeError(outcome: Exchange, what: String): ChessError = when (outcome) {
        Exchange.Timeout -> ChessError.EngineUnavailable("engine did not answer '$what' in time")
        else -> ChessError.EngineCrashed("engine closed during '$what'")
    }

    override suspend fun stop() {
        val awaiting = synchronized(guard) { startResult }
        awaiting?.await() // не обриваємо запуск посередині
        val oldSession: Session?
        synchronized(guard) {
            oldSession = session
            if (_state.value == EngineState.Stopped) return
            oldSession?.expectedClose?.set(true)
            val search = current
            current = null
            search?.finish(ChessError.Cancelled)
            search?.engineIdle?.complete(Unit)
            _state.value = EngineState.Stopped
        }
        EngineTrace.mark("stop.begin")
        oldSession?.watchdog?.cancel()
        withContext(NonCancellable) {
            val closing = closeTransportAsync()
            val closed = withTimeoutOrNull(timeouts.closeWaitMs) { closing.await() } != null
            oldSession?.dispatcher?.cancel()
            EngineTrace.mark("stop.end", if (closed) "closed" else "close-pending")
        }
    }

    /** Налагодження: аварійне завершення нативного коду (`abort()`); працює лише в debug-збірці застосунку. */
    fun crashNativeForTest() = transport.crash()

    /** Налагодження (G3): рядки `info … pv`, прочитані рушієм на його боці з початку поточного відкриття. */
    fun emittedInfoLinesForTest(): Long = transport.emittedInfoLines()

    override suspend fun newGame() {
        lock.withLock {
            if (!isRunning()) return
            if (!cancelCurrentAndWait()) return
            val session = session ?: return
            val outcome = exchange(session, listOf("ucinewgame", "isready"), timeouts.livenessMs) { it == "readyok" }
            if (outcome == Exchange.Timeout) onUnavailable(session, "engine did not answer 'ucinewgame' in time")
        }
    }

    // --- пошук ---

    override suspend fun bestMove(request: MoveRequest): ChessResult<EngineMove> {
        val strength = request.level.strength
        require(strength !is Strength.Handicap) { "Strength.Handicap is handled by HandicapEngineService" }
        val search = when (val begun = begin(request.startFen, request.moves, 1, request.level.limit, strength, false)) {
            is Begun.Rejected -> return ChessResult.Err(begun.error)
            is Begun.Started -> begun.search
        }
        val running = session
        val bound = searchBoundMs(request.level.limit)
        try {
            val result = if (bound == null) search.moveResult.await() else withTimeoutOrNull(bound) { search.moveResult.await() }
            if (result != null) return result
            val reason = "engine did not return bestmove within $bound ms"
            onUnavailable(running, reason)
            return ChessResult.Err(ChessError.EngineUnavailable(reason))
        } finally {
            if (!search.finished.get()) abandon(search)
        }
    }

    override fun analyse(request: AnalysisRequest): Flow<AnalysisEvent> = flow {
        val search = when (val begun = begin(request.startFen, request.moves, request.multiPv, request.limit, Strength.Full, true)) {
            is Begun.Rejected -> {
                emit(AnalysisEvent.Failed(begun.error))
                return@flow
            }
            is Begun.Started -> begun.search
        }
        try {
            for (event in search.events) {
                if (event is AnalysisEvent.Update) EngineStats.delivered(System.nanoTime() - (search.stamps.poll() ?: System.nanoTime()))
                emit(event)
            }
        } finally {
            // Споживач пішов: UCI "stop"; стан повернеться в Ready, коли рушій надішле bestmove.
            if (!search.finished.get()) abandon(search)
        }
    }

    private sealed interface Begun {
        class Started(val search: Search) : Begun
        class Rejected(val error: ChessError) : Begun
    }

    /**
     * Приймає запит: скасовує попередній пошук (навіть якщо цей запит завершиться помилкою, v0.6),
     * перевіряє позицію через [ChessRules] і запускає `go`.
     */
    private suspend fun begin(
        startFen: Fen,
        moves: List<Uci>,
        multiPv: Int,
        limit: SearchLimit?,
        strength: Strength,
        analysis: Boolean,
    ): Begun = lock.withLock {
        if (!isRunning()) return Begun.Rejected(unavailable())
        if (!cancelCurrentAndWait()) return Begun.Rejected(unavailable())
        val session = session ?: return Begun.Rejected(unavailable())

        val position = when (val result = positionOf(startFen, moves)) {
            is ChessResult.Ok -> result.value
            is ChessResult.Err -> {
                markReady()
                return Begun.Rejected(result.error)
            }
        }
        if (rules.legalMoves(position).isEmpty()) {
            markReady()
            return Begun.Rejected(ChessError.NoLegalMoves)
        }

        val caps = _capabilities ?: return Begun.Rejected(unavailable())
        val lines = multiPv.coerceIn(1, caps.maxMultiPv)
        val search = Search(analysis, rootBlack = position.sideToMove == Color.BLACK)
        val commands = buildList {
            add("setoption name MultiPV value $lines")
            when (strength) {
                Strength.Full -> {
                    add("setoption name UCI_LimitStrength value false")
                    add("setoption name Skill Level value 20")
                }
                is Strength.Elo -> {
                    add("setoption name UCI_LimitStrength value true")
                    add("setoption name UCI_Elo value ${strength.elo}")
                }
                is Strength.SkillLevel -> {
                    add("setoption name UCI_LimitStrength value false")
                    add("setoption name Skill Level value ${strength.level}")
                }
                is Strength.Handicap -> error("filtered by bestMove")
            }
            add("position fen ${startFen.value}" + if (moves.isEmpty()) "" else " moves " + moves.joinToString(" ") { it.value })
            add("go " + goArguments(limit))
        }
        synchronized(guard) {
            if (!isRunning()) return Begun.Rejected(unavailable())
            current = search
            _state.value = EngineState.Busy
        }
        commands.forEach(transport::send)
        Begun.Started(search)
    }

    /**
     * Верхня межа очікування `bestmove` (CONTRACTS 3.4, v0.8): час пошуку плюс запас. Пошук зупиняє перший
     * досягнутий ліміт, тому береться найменша з оцінок. Оцінки за `depth` і `nodes` грубі й свідомо щедрі
     * (2 с на ply, 5 тис. вузлів/с); `moveTimeMs` точний. `null` — ліміту немає, діє лише сторожовий механізм.
     */
    private fun searchBoundMs(limit: SearchLimit?): Long? {
        if (limit == null) return null
        val estimates = listOfNotNull(
            limit.moveTimeMs,
            limit.depth?.let { it.coerceAtLeast(1) * 2_000L },
            limit.nodes?.let { it * 1_000L / 5_000L },
        )
        return estimates.minOrNull()?.plus(timeouts.searchGraceMs)
    }

    private fun goArguments(limit: SearchLimit?): String {
        if (limit == null) return "infinite"
        return buildList {
            limit.depth?.let { add("depth $it") }
            limit.moveTimeMs?.let { add("movetime $it") }
            limit.nodes?.let { add("nodes $it") }
        }.joinToString(" ")
    }

    private fun unavailable() = ChessError.EngineUnavailable("engine is not running")

    private fun isRunning(): Boolean = _state.value.let { it == EngineState.Ready || it == EngineState.Busy }

    private fun markReady() {
        _state.compareAndSet(EngineState.Busy, EngineState.Ready)
    }

    /**
     * Скасовує поточний пошук: споживач одразу бачить Cancelled, а до рушія йде `stop`.
     * Повертає `false`, якщо рушій не відповів (тоді позначено збій).
     */
    private suspend fun cancelCurrentAndWait(): Boolean {
        val old = current ?: return true
        old.finish(ChessError.Cancelled)
        if (!old.engineIdle.isCompleted) {
            transport.send("stop")
            val answered = withTimeoutOrNull(timeouts.stopMs) { old.engineIdle.await() }
            if (answered == null) {
                onUnavailable(session, "engine ignored stop")
                return false
            }
        }
        return isRunning()
    }

    /** Споживач пішов (скасована корутина або Flow): зупиняємо пошук без подій. */
    private fun abandon(search: Search) {
        if (search.finish(null) && !search.engineIdle.isCompleted && current === search) {
            transport.send("stop")
        }
    }

    private fun positionOf(startFen: Fen, moves: List<Uci>): ChessResult<Position> {
        var position = when (val parsed = rules.parseFen(startFen.value)) {
            is ChessResult.Ok -> parsed.value
            is ChessResult.Err -> return parsed
        }
        for (uci in moves) {
            val move = when (val parsed = rules.parseUci(position, uci.value)) {
                is ChessResult.Ok -> parsed.value
                is ChessResult.Err -> return parsed
            }
            position = when (val applied = rules.apply(position, move)) {
                is ChessResult.Ok -> applied.value
                is ChessResult.Err -> return applied
            }
        }
        return ChessResult.Ok(position)
    }

    // --- рядки від рушія ---

    private sealed interface Incoming {
        class Line(val text: String, val readAtNs: Long) : Incoming
        class Closed(val crashed: Boolean, val reason: String) : Incoming
    }

    private enum class Exchange { Done, Timeout, Closed }

    private inner class Session {
        val incoming = Channel<Incoming>(Channel.UNLIMITED)
        val closed = CompletableDeferred<Unit>()
        val expectedClose = AtomicBoolean(false)
        @Volatile var tap: ((String) -> Unit)? = null
        @Volatile var dispatcher: Job? = null
        @Volatile var watchdog: Job? = null

        /** Час (nanoTime) останнього рядка від рушія: основа сторожового механізму. */
        @Volatile var lastLineAt = System.nanoTime()
        private val sawFirstLine = AtomicBoolean(false)

        val listener = object : UciTransport.Listener {
            override fun onLine(line: String, readAtNs: Long) {
                lastLineAt = System.nanoTime()
                if (sawFirstLine.compareAndSet(false, true)) EngineTrace.mark("uci.first-line")
                if (line.startsWith("info ") && line.contains(" pv ")) EngineStats.received()
                incoming.trySend(Incoming.Line(line, readAtNs))
            }

            override fun onClosed(crashed: Boolean, reason: String) {
                incoming.trySend(Incoming.Closed(crashed, reason))
            }
        }
    }

    private suspend fun dispatch(session: Session) {
        for (item in session.incoming) {
            when (item) {
                is Incoming.Line -> {
                    session.tap?.invoke(item.text)
                    if (session === this.session) onSearchLine(item.text, item.readAtNs)
                }
                is Incoming.Closed -> {
                    session.closed.complete(Unit)
                    if (item.crashed && !session.expectedClose.get()) onCrashed(session, item.reason)
                    return
                }
            }
        }
    }

    /** Надсилає [commands] і чекає рядок, на якому [until] дає `true`. */
    private suspend fun exchange(
        session: Session,
        commands: List<String>,
        timeoutMs: Long,
        collect: (String) -> Unit = {},
        until: (String) -> Boolean,
    ): Exchange {
        val done = CompletableDeferred<Unit>()
        session.tap = { line ->
            collect(line)
            if (until(line)) done.complete(Unit)
        }
        try {
            commands.forEach(transport::send)
            return withTimeoutOrNull(timeoutMs) {
                select {
                    done.onAwait { Exchange.Done }
                    session.closed.onAwait { Exchange.Closed }
                }
            } ?: Exchange.Timeout
        } finally {
            session.tap = null
        }
    }

    private fun onSearchLine(line: String, readAtNs: Long) {
        val search = current ?: return
        when {
            line.startsWith("info ") -> if (search.analysis) {
                val update = parseInfo(line, search.rootBlack)
                if (update != null) search.emit(update, readAtNs) else if (line.contains(" pv ")) EngineStats.skipped()
            }
            line.startsWith("bestmove") -> onBestMove(search, line)
        }
    }

    private fun onBestMove(search: Search, line: String) {
        synchronized(guard) {
            if (current === search) {
                current = null
                markReady()
            }
        }
        val parts = line.split(' ')
        val best = parts.getOrNull(1)
        if (search.analysis) {
            search.finishWith(null)
        } else if (best == null || best == "(none)" || best == "0000") {
            search.finish(ChessError.NoLegalMoves)
        } else {
            val ponder = if (parts.getOrNull(2) == "ponder") parts.getOrNull(3) else null
            search.complete(EngineMove(Uci(best), ponder?.let(::Uci)))
        }
        search.engineIdle.complete(Unit)
    }

    private fun onCrashed(from: Session?, reason: String) = onFailed(from, ChessError.EngineCrashed(reason))

    /** Рушій живий, але мовчить або не виконує запит: те саме, що збій, лише з іншою помилкою. */
    private fun onUnavailable(from: Session?, reason: String) {
        EngineTrace.mark("watchdog.unavailable", reason)
        onFailed(from, ChessError.EngineUnavailable(reason))
    }

    /**
     * Сторожовий механізм (CONTRACTS 3.4, v0.8). Рушій, що 10 с не дав жодного рядка, хоча йому надсилали
     * службовий запит `isready` (на нього Stockfish відповідає `readyok` навіть під час пошуку), вважається
     * недоступним. Довгі паузи між `info` у нескінченному аналізі зависанням не є: `readyok` надходить і так.
     */
    private suspend fun watch(session: Session) {
        var lastPing = 0L
        while (true) {
            delay(timeouts.tickMs)
            if (this.session !== session) return
            val state = _state.value
            if (state != EngineState.Ready && state != EngineState.Busy) return
            val now = System.nanoTime()
            val idleMs = (now - session.lastLineAt) / 1_000_000
            // Крок опитування віднімається, щоб відмова настала не пізніше livenessMs від останнього рядка.
            if (idleMs >= timeouts.livenessMs - timeouts.tickMs) {
                onUnavailable(session, "engine did not answer liveness probe in ${timeouts.livenessMs} ms")
                return
            }
            if (idleMs >= timeouts.pingAfterMs && (now - lastPing) / 1_000_000 >= timeouts.pingAfterMs) {
                lastPing = now
                scope.launch(Dispatchers.IO) { runCatching { transport.send("isready") } }
            }
        }
    }

    private fun onFailed(from: Session?, error: ChessError) {
        synchronized(guard) {
            if (from != null && from !== session) return
            if (_state.value == EngineState.Stopped) return
            _state.value = EngineState.Failed(error)
            val search = current
            current = null
            search?.finish(error)
            search?.engineIdle?.complete(Unit)
        }
        from?.expectedClose?.set(true)
        from?.watchdog?.cancel()
        closeTransportAsync()
    }

    // --- розбір UCI ---

    private fun parseCapabilities(lines: List<String>): EngineCapabilities? {
        val idName = lines.firstOrNull { it.startsWith("id name ") }?.removePrefix("id name ")?.trim() ?: return null
        val options = lines.mapNotNull(::parseOption).associateBy { it.name }
        val multiPv = options["MultiPV"]?.max ?: return null
        val threads = options["Threads"]?.max ?: return null
        val hash = options["Hash"]?.max ?: return null
        val space = idName.indexOf(' ')
        return EngineCapabilities(
            name = if (space < 0) idName else idName.substring(0, space),
            version = if (space < 0) "" else idName.substring(space + 1),
            eloRange = options["UCI_Elo"]?.let { o -> o.min?.let { lo -> o.max?.let { hi -> lo.toInt()..hi.toInt() } } },
            skillLevelRange = options["Skill Level"]?.let { o -> o.min?.let { lo -> o.max?.let { hi -> lo.toInt()..hi.toInt() } } },
            maxMultiPv = multiPv.toInt(),
            threadsMax = threads.toInt(),
            hashMaxMb = hash.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
    }

    private class UciOption(val name: String, val min: Long?, val max: Long?)

    private fun parseOption(line: String): UciOption? {
        if (!line.startsWith("option name ")) return null
        val match = OPTION.matchEntire(line) ?: return null
        return UciOption(
            name = match.groupValues[1],
            min = match.groups[3]?.value?.toLongOrNull(),
            max = match.groups[4]?.value?.toLongOrNull(),
        )
    }

    // --- пошук: стан одного запиту ---

    private inner class Search(val analysis: Boolean, val rootBlack: Boolean) {
        val engineIdle = CompletableDeferred<Unit>()
        val moveResult = CompletableDeferred<ChessResult<EngineMove>>()
        val events = Channel<AnalysisEvent>(Channel.UNLIMITED)
        val finished = AtomicBoolean(false)

        /** Часи прочитання рядків рушієм, у порядку `Update` у [events]: для виміру затримки доставки (G3). */
        val stamps = java.util.concurrent.ConcurrentLinkedQueue<Long>()

        fun emit(update: AnalysisUpdate, readAtNs: Long) {
            if (finished.get()) return
            EngineStats.parsed()
            stamps.add(readAtNs)
            events.trySend(AnalysisEvent.Update(update))
        }

        fun complete(move: EngineMove) {
            if (finished.compareAndSet(false, true)) moveResult.complete(ChessResult.Ok(move))
        }

        /** Завершення Finished (аналіз) без помилки. */
        fun finishWith(error: ChessError?) {
            if (!finished.compareAndSet(false, true)) return
            if (error != null) events.trySend(AnalysisEvent.Failed(error)) else events.trySend(AnalysisEvent.Finished)
            events.close()
        }

        /** `true`, якщо це перше завершення. [error] == `null`: споживач пішов, подій більше нема. */
        fun finish(error: ChessError?): Boolean {
            if (!finished.compareAndSet(false, true)) return false
            if (analysis) {
                if (error != null) events.trySend(AnalysisEvent.Failed(error))
                events.close()
            } else {
                moveResult.complete(ChessResult.Err(error ?: ChessError.Cancelled))
            }
            return true
        }
    }

    private companion object {
        const val UCI_TIMEOUT_MS = 10_000L

        // option name <ім'я> type <тип> [default <v>] [min <n>] [max <n>] [var ...]
        val OPTION = Regex("""option name (.+?) type (\w+)(?: default (?:\S+|<empty>))?(?: min (-?\d+))?(?: max (-?\d+))?.*""")
    }
}

/**
 * Таймаути [UciEngineService] (CONTRACTS 3.4, v0.8). Типові значення це контрактні; інші лише для тестів.
 *
 * @property startMs `start()` у цілому, зокрема перший запуск із копіюванням мережі.
 * @property searchGraceMs запас до очікуваного часу пошуку для `bestMove`.
 * @property livenessMs скільки рушій може мовчати попри службовий запит.
 * @property stopMs очікування `bestmove` після UCI `stop` при скасуванні пошуку.
 * @property closeWaitMs скільки `stop()`/наступний `start()` чекають закриття транспорту.
 */
internal data class EngineTimeouts(
    val startMs: Long = 15_000,
    val searchGraceMs: Long = 5_000,
    val livenessMs: Long = 10_000,
    val stopMs: Long = 5_000,
    val closeWaitMs: Long = 5_000,
    val tickMs: Long = 250,
    val pingAfterMs: Long = 2_000,
)

/** Розбір рядка `info ... multipv N score cp|mate X ... pv ...`; `null`, якщо рядок не оновлення пошуку. */
internal fun parseInfo(line: String, rootBlack: Boolean): AnalysisUpdate? {
    val tokens = line.split(' ')
    if ("pv" !in tokens || "score" !in tokens) return null
    var depth = 0
    var selDepth: Int? = null
    var multiPv = 1
    var nodes = 0L
    var nps = 0L
    var time = 0L
    var evaluation: Evaluation? = null
    var pv = emptyList<Uci>()
    var i = 1
    while (i < tokens.size) {
        when (tokens[i]) {
            "depth" -> depth = tokens.getOrNull(++i)?.toIntOrNull() ?: depth
            "seldepth" -> selDepth = tokens.getOrNull(++i)?.toIntOrNull()
            "multipv" -> multiPv = tokens.getOrNull(++i)?.toIntOrNull() ?: multiPv
            "nodes" -> nodes = tokens.getOrNull(++i)?.toLongOrNull() ?: nodes
            "nps" -> nps = tokens.getOrNull(++i)?.toLongOrNull() ?: nps
            "time" -> time = tokens.getOrNull(++i)?.toLongOrNull() ?: time
            "score" -> {
                val kind = tokens.getOrNull(++i)
                val value = tokens.getOrNull(++i)?.toIntOrNull() ?: return null
                // Межі (lowerbound/upperbound) не є остаточною оцінкою рядка.
                if (tokens.getOrNull(i + 1).let { it == "lowerbound" || it == "upperbound" }) return null
                val signed = if (rootBlack) -value else value
                evaluation = when (kind) {
                    "cp" -> Evaluation.Centipawns(signed)
                    "mate" -> if (value == 0) return null else Evaluation.Mate(signed)
                    else -> return null
                }
            }
            "pv" -> {
                pv = tokens.drop(i + 1).filter { it.isNotEmpty() }.map(::Uci)
                break
            }
        }
        i++
    }
    if (evaluation == null || pv.isEmpty()) return null
    return AnalysisUpdate(depth, selDepth, multiPv, evaluation, pv, nodes, nps, time)
}
