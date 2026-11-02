// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.calibration

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
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
import org.chesstrainer.core.model.ChessError
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Color
import org.chesstrainer.core.model.Evaluation
import org.chesstrainer.core.model.Fen
import org.chesstrainer.core.model.Position
import org.chesstrainer.core.model.Uci
import org.chesstrainer.core.rules.ChessRules

/**
 * ТІЛЬКИ ІНСТРУМЕНТ НА ПК (CONTRACTS 3.5 п. 9): `EngineService` поверх UCI-процесу Stockfish, зібраного
 * з вихідного коду `sf_19` із репозиторію (`core-chess/tools/handicap-calibration/build-stockfish-android.sh`), який працює на телефоні власника (`adb shell`). Не входить
 * в APK, не запускається в `./gradlew test`.
 *
 * Спрощення порівняно з `engine-android`: один пошук одночасно (інструмент послідовний на кожен екземпляр),
 * без скасування. Один екземпляр = один процес Stockfish з `Threads = 1` (відтворюваний пошук).
 *
 * Кеш пошуків [AnalysisCache] спільний для екземплярів: той самий пошук (позиція, ліміт, MultiPV) виконується
 * раз; повторний запуск інструмента бере відповідь із файлу.
 */
class UciProcessEngine(
    private val command: List<String>,
    private val netPath: String,
    private val rules: ChessRules,
    private val cache: AnalysisCache,
) : EngineService {
    private val _state = MutableStateFlow<EngineState>(EngineState.Stopped)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    @Volatile
    override var capabilities: EngineCapabilities? = null
        private set

    /** Вузли останнього `bestMove` (останній рядок `info nodes`): для перерахунку часу пресета у вузли. */
    @Volatile
    var lastNodes: Long = 0
        private set

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    /** Опції, які вже надіслані процесу: щоб не повторювати `setoption`. */
    private val applied = mutableMapOf<String, String>()

    override suspend fun start(config: EngineConfig): ChessResult<Unit> {
        if (_state.value == EngineState.Ready) return ChessResult.Ok(Unit)
        _state.value = EngineState.Starting
        val p = ProcessBuilder(command).redirectErrorStream(true).start()
        process = p
        writer = p.outputStream.bufferedWriter()
        reader = p.inputStream.bufferedReader()
        send("uci")
        var name = "Stockfish"
        val spins = mutableMapOf<String, IntRange>()
        while (true) {
            val line = readLine() ?: error("Stockfish exited during startup")
            if (line.startsWith("id name ")) name = line.removePrefix("id name ")
            if (line.startsWith("option name ") && " type spin " in line) {
                val optName = line.substringAfter("option name ").substringBefore(" type ")
                val min = line.substringAfter(" min ").substringBefore(" ").toInt()
                val max = line.substringAfter(" max ").trim().toInt()
                spins[optName] = min..max
            }
            if (line == "uciok") break
        }
        setOption("EvalFile", netPath)
        setOption("Threads", "1") // відтворюваний пошук
        setOption("Hash", "16")
        isReady()
        capabilities = EngineCapabilities(
            name = name.substringBefore(' '),
            version = name.substringAfter(' ', ""),
            eloRange = spins["UCI_Elo"],
            skillLevelRange = spins["Skill Level"],
            maxMultiPv = spins["MultiPV"]?.last ?: 1,
            threadsMax = spins["Threads"]?.last ?: 1,
            hashMaxMb = spins["Hash"]?.last ?: 16,
        )
        _state.value = EngineState.Ready
        return ChessResult.Ok(Unit)
    }

    /** Аварійно обриває процес (сторож матчів): заблокований `readLine` поверне `null`. */
    fun kill() {
        process?.destroyForcibly()
    }

    override suspend fun stop() {
        runCatching { send("quit") }
        process?.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        process?.destroyForcibly()
        process = null
        applied.clear()
        _state.value = EngineState.Stopped
    }

    override suspend fun newGame() {
        send("ucinewgame")
        isReady()
    }

    override suspend fun bestMove(request: MoveRequest): ChessResult<EngineMove> {
        val position = when (val p = positionAfter(request.startFen, request.moves)) {
            is ChessResult.Ok -> p.value
            is ChessResult.Err -> return p
        }
        if (rules.legalMoves(position).isEmpty()) return ChessResult.Err(ChessError.NoLegalMoves)
        when (val strength = request.level.strength) {
            Strength.Full -> setOption("UCI_LimitStrength", "false")
            is Strength.Elo -> {
                setOption("UCI_LimitStrength", "true")
                setOption("UCI_Elo", strength.elo.toString())
            }
            is Strength.SkillLevel -> {
                setOption("UCI_LimitStrength", "false")
                setOption("Skill Level", strength.level.toString())
            }
            is Strength.Handicap -> throw IllegalArgumentException("Handicap goes through HandicapEngineService")
        }
        setOption("MultiPV", "1")
        _state.value = EngineState.Busy
        try {
            newGame()
            send(positionCommand(request.startFen, request.moves))
            send("go ${limitCommand(request.level.limit)}")
            while (true) {
                val line = readLine() ?: return ChessResult.Err(ChessError.EngineCrashed("Stockfish exited"))
                if (line.startsWith("info ") && " nodes " in line) {
                    line.substringAfter(" nodes ").substringBefore(' ').toLongOrNull()?.let { lastNodes = it }
                }
                if (line.startsWith("bestmove ")) {
                    val parts = line.split(' ')
                    return ChessResult.Ok(EngineMove(Uci(parts[1]), parts.getOrNull(3)?.let { Uci(it) }))
                }
            }
        } finally {
            _state.value = EngineState.Ready
        }
    }

    override fun analyse(request: AnalysisRequest): Flow<AnalysisEvent> = flow {
        val position = when (val p = positionAfter(request.startFen, request.moves)) {
            is ChessResult.Ok -> p.value
            is ChessResult.Err -> {
                emit(AnalysisEvent.Failed(p.error))
                return@flow
            }
        }
        val legal = rules.legalMoves(position).size
        if (legal == 0) {
            emit(AnalysisEvent.Failed(ChessError.NoLegalMoves))
            return@flow
        }
        val lines = minOf(request.multiPv, legal)
        val limit = request.limit ?: error("calibration tool needs a bounded search")
        val key = AnalysisCache.key(request.startFen, request.moves, limit, lines)
        val entry = cache.get(key) ?: run {
            setOption("UCI_LimitStrength", "false")
            setOption("MultiPV", lines.toString())
            _state.value = EngineState.Busy
            val found = try {
                search(request, limit, lines, position)
            } finally {
                _state.value = EngineState.Ready
            }
            cache.put(key, found)
            found
        }
        entry.lines.forEach { emit(AnalysisEvent.Update(it)) }
        emit(AnalysisEvent.Finished)
    }

    private fun search(request: AnalysisRequest, limit: SearchLimit, lines: Int, position: Position): AnalysisCache.Entry {
        // `ucinewgame` перед пошуком очищає хеш: пошук не залежить від попередніх (відтворюваність).
        send("ucinewgame")
        isReady()
        send(positionCommand(request.startFen, request.moves))
        val started = System.nanoTime()
        send("go ${limitCommand(limit)}")
        val byDepth = sortedMapOf<Int, MutableMap<Int, AnalysisUpdate>>()
        val latest = mutableMapOf<Int, AnalysisUpdate>()
        while (true) {
            val line = readLine() ?: error("Stockfish exited during search")
            if (line.startsWith("bestmove")) break
            parseInfo(line, position.sideToMove)?.let { u ->
                byDepth.getOrPut(u.depth) { mutableMapOf() }[u.multiPv] = u
                latest[u.multiPv] = u
            }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val complete = byDepth.entries.lastOrNull { it.value.size >= lines }?.value ?: latest
        val chosen = complete.toSortedMap().values.toList()
        val nodes = chosen.maxOfOrNull { it.nodes } ?: 0L
        return AnalysisCache.Entry(chosen, nodes, elapsedMs)
    }

    private fun parseInfo(line: String, mover: Color): AnalysisUpdate? {
        if (!line.startsWith("info ") || " score " !in line || " pv " !in line) return null
        if (" lowerbound" in line || " upperbound" in line) return null
        val t = line.split(' ')
        fun after(name: String): String? = t.indexOf(name).takeIf { it >= 0 }?.let { t.getOrNull(it + 1) }
        val depth = after("depth")?.toIntOrNull() ?: return null
        val multiPv = after("multipv")?.toIntOrNull() ?: 1
        val si = t.indexOf("score")
        val raw = t[si + 2].toInt()
        val white = if (mover == Color.WHITE) raw else -raw
        val evaluation: Evaluation = when (t[si + 1]) {
            "cp" -> Evaluation.Centipawns(white)
            "mate" -> if (white == 0) return null else Evaluation.Mate(white)
            else -> return null
        }
        val pv = t.drop(t.indexOf("pv") + 1).map { Uci(it) }
        return AnalysisUpdate(
            depth = depth,
            selDepth = after("seldepth")?.toIntOrNull(),
            multiPv = multiPv,
            evaluation = evaluation,
            pv = pv,
            nodes = after("nodes")?.toLongOrNull() ?: 0L,
            nps = after("nps")?.toLongOrNull() ?: 0L,
            timeMs = after("time")?.toLongOrNull() ?: 0L,
        )
    }

    private fun limitCommand(limit: SearchLimit): String = buildString {
        limit.depth?.let { append("depth $it ") }
        limit.moveTimeMs?.let { append("movetime $it ") }
        limit.nodes?.let { append("nodes $it ") }
    }.trim()

    private fun positionCommand(fen: Fen, moves: List<Uci>): String =
        "position fen ${fen.value}" + if (moves.isEmpty()) "" else " moves " + moves.joinToString(" ") { it.value }

    private fun positionAfter(startFen: Fen, moves: List<Uci>): ChessResult<Position> {
        var position = when (val parsed = rules.parseFen(startFen.value)) {
            is ChessResult.Ok -> parsed.value
            is ChessResult.Err -> return parsed
        }
        for (uci in moves) {
            val move = when (val parsed = rules.parseUci(position, uci.value)) {
                is ChessResult.Ok -> parsed.value
                is ChessResult.Err -> return parsed
            }
            position = when (val next = rules.apply(position, move)) {
                is ChessResult.Ok -> next.value
                is ChessResult.Err -> return next
            }
        }
        return ChessResult.Ok(position)
    }

    private fun setOption(name: String, value: String) {
        if (applied[name] == value) return
        send("setoption name $name value $value")
        applied[name] = value
    }

    private fun isReady() {
        send("isready")
        while (readLine() != "readyok") Unit
    }

    private fun send(line: String) {
        val w = writer ?: error("engine not started")
        w.write(line)
        w.newLine()
        w.flush()
    }

    private fun readLine(): String? = reader?.readLine()
}

/**
 * Кеш пошуків на диску (TSV, дописування). Запис зберігає лише лінії найглибшої повної ітерації, тож живий пошук
 * і відповідь із кешу однакові. `nodes` і `ms` це вартість пошуку при створенні запису (для оцінки часу ходу).
 */
class AnalysisCache(private val file: File?, private val retain: Boolean = true) {
    data class Entry(val lines: List<AnalysisUpdate>, val nodes: Long, val ms: Long)

    private val map = ConcurrentHashMap<String, Entry>()
    private val lock = Any()

    init {
        file?.parentFile?.mkdirs()
        if (file != null && file.isFile) file.forEachLine { line -> decode(line)?.let { (k, e) -> map[k] = e } }
    }

    val size: Int get() = map.size
    fun entries(): Map<String, Entry> = map

    fun get(key: String): Entry? = map[key]

    fun put(key: String, entry: Entry) {
        if (retain) map[key] = entry
        if (file != null) synchronized(lock) { file.appendText(encode(key, entry) + "\n") }
    }

    private fun encode(key: String, e: Entry): String {
        val lines = e.lines.joinToString(";") { u ->
            val ev = when (val x = u.evaluation) {
                is Evaluation.Centipawns -> "c${x.cp}"
                is Evaluation.Mate -> "m${x.moves}"
            }
            "${u.multiPv},${u.depth},$ev,${u.nodes},${u.pv.firstOrNull()?.value ?: ""}"
        }
        return "$key\t${e.nodes}\t${e.ms}\t$lines"
    }

    private fun decode(line: String): Pair<String, Entry>? {
        val f = line.split('\t')
        if (f.size < 4) return null
        val lines = f[3].split(';').filter { it.isNotEmpty() }.map { s ->
            val p = s.split(',')
            val ev = p[2]
            val evaluation =
                if (ev[0] == 'c') Evaluation.Centipawns(ev.substring(1).toInt()) else Evaluation.Mate(ev.substring(1).toInt())
            AnalysisUpdate(
                depth = p[1].toInt(), selDepth = null, multiPv = p[0].toInt(), evaluation = evaluation,
                pv = listOf(Uci(p[4])), nodes = p[3].toLong(), nps = 0, timeMs = 0,
            )
        }
        return f[0] to Entry(lines, f[1].toLong(), f[2].toLong())
    }

    companion object {
        /** Версія рушія/мережі/опцій входить у ключ: інша збірка не бере чужі відповіді. */
        const val ENGINE_TAG = "sf19-nn1a298aa575a0-t1-h16"

        fun key(fen: Fen, moves: List<Uci>, limit: SearchLimit, lines: Int): String =
            "$ENGINE_TAG|${fen.value}|${moves.joinToString(" ") { it.value }}|d${limit.depth}m${limit.moveTimeMs}n${limit.nodes}|pv$lines"
    }
}
