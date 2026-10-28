# CONTRACTS — контракти між модулями

Статус: **ЧЕРНЕТКА v0.2** (Архітектор, 2026-10-28). Структуру модулів погоджено власником. Сигнатури остаточні після підтвердження Шаховим ядром і Android і UI.
Підпорядковується `MASTER_SPEC.md`. Якщо є суперечність, діє специфікація.

Код нижче це **специфікація інтерфейсів** (сигнатури й семантика), а не готова реалізація.

**Позначки:**
- **[MVP]**: реалізується на етапі 2, сигнатура фіксується зараз.
- **[Пізніше]**: етап 3 і далі. Ескіз напрямку, може змінитися без процедури сумісності. Реалізовувати зараз не потрібно.

## 0. Процедура змін [MVP]

1. Будь-яка зміна контракту (тип, сигнатура, семантика, формат даних) починається із запису в `project_log.md` → «Запити між агентами».
2. Архітектор оцінює вплив на всі модулі й записує рішення в «Рішення».
3. До v1.0 несумісні зміни допускаються, але кожна з записом у журналі. Після v1.0: доповнення це MINOR, несумісна зміна це MAJOR.
4. Додавання варіанта в `sealed`-ієрархію чи `enum` вважається доповненням. Споживачі мають гілку за замовчуванням, а не падають.
5. Модуль не змінює реалізацію чужого контракту.

## 1. Модулі й принципи [MVP]

| Gradle-модуль | Пакет | Android? | Власник |
|---|---|---|---|
| `:core-chess:api` | `org.chesstrainer.core` | ні (чистий Kotlin/JVM) | Шахове ядро |
| `:core-chess:engine-android` | `org.chesstrainer.engine` | так (NDK/JNI) | Шахове ядро |
| `:learning` | `org.chesstrainer.learning` | ні (чистий Kotlin/JVM) | Навчання і контент |
| `:app` | `org.chesstrainer.app` | так | Android і UI |

```
app ──► learning ──► core-chess:api ◄── core-chess:engine-android ◄── app
content/ : лише дані, читаються через порт ContentSource (реалізує app)
```

- **Асинхронність:** `suspend` і `Flow`. Диспетчери, `Clock` і `Random` передаються ззовні, щоб тести були детермінованими.
- **Помилки:** очікувані помилки повертаються як `ChessResult<T>`. Винятки лише для порушення контракту програмістом.
- **Тексти:** `core-chess` і `learning` повертають ключі й коди, а не тексти. Поле `reason: String` у помилках призначене для логів, не для UI.
- **Незмінність:** усі моделі це immutable `data class`.
- **Продуктивність:** синхронні функції `ChessRules` не блокують понад кілька мс. Довше (рушій, імпорт великого PGN) лише `suspend`/`Flow`.
- **Стабільні ідентифікатори:** усі `id`, що зберігаються в БД (рівні складності, задачі, теми), ніколи не використовуються повторно для іншого змісту.

## 2. Спільні типи (`:core-chess:api`, пакет `org.chesstrainer.core.model`) [MVP]

```kotlin
@JvmInline value class Fen(val value: String)
@JvmInline value class Uci(val value: String)          // "e2e4", "e7e8q"; рокіровка у форматі e1g1

@JvmInline value class Square(val index: Int) {        // 0..63; a1 = 0, b1 = 1, …, h8 = 63
    val file: Int    // 0..7 (a..h)
    val rank: Int    // 0..7 (1..8)
    val name: String // "e4"
    companion object {
        fun of(file: Int, rank: Int): Square
        fun parse(name: String): Square?               // null для некоректного рядка
    }
}

enum class Color { WHITE, BLACK; val opposite: Color }
enum class PieceType { PAWN, KNIGHT, BISHOP, ROOK, QUEEN, KING }
data class Piece(val color: Color, val type: PieceType)

data class Move(val from: Square, val to: Square, val promotion: PieceType? = null)

data class CastlingRights(
    val whiteKingSide: Boolean, val whiteQueenSide: Boolean,
    val blackKingSide: Boolean, val blackQueenSide: Boolean
)

interface Position {                                   // незмінна позиція; рівність = рівність FEN без лічильників
    val fen: Fen
    val sideToMove: Color
    val castling: CastlingRights
    val enPassant: Square?                             // поле взяття на проході, якщо є
    val halfmoveClock: Int
    val fullmoveNumber: Int
    fun pieceAt(square: Square): Piece?
}

sealed interface Evaluation {                          // ЗАВЖДИ з погляду білих (перетворення з UCI робить ядро)
    data class Centipawns(val cp: Int) : Evaluation
    data class Mate(val moves: Int) : Evaluation       // >0: білі матують за N ходів; <0: чорні
}

sealed interface ChessResult<out T> {
    data class Ok<T>(val value: T) : ChessResult<T>
    data class Err(val error: ChessError) : ChessResult<Nothing>
}

sealed interface ChessError {
    data class InvalidFen(val reason: String) : ChessError
    data class IllegalMove(val move: String) : ChessError
    data class PgnSyntax(val gameIndex: Int, val line: Int, val reason: String) : ChessError
    data class PgnIllegalMove(val gameIndex: Int, val ply: Int, val san: String) : ChessError
    data class EngineUnavailable(val reason: String) : ChessError
    data class EngineCrashed(val reason: String) : ChessError
    data object Cancelled : ChessError
}
```

## 3. `core-chess`

**Відповідає за:** правила, легальні ходи, стан партії, FEN/PGN/UCI, інтеграцію Stockfish, рівні складності (включно зі слабкими), аналіз позиції. **Не відповідає за:** UI, збереження, навчальну логіку, тексти.

### 3.1 Правила [MVP]

```kotlin
interface ChessRules {
    fun startPosition(): Position
    fun parseFen(fen: String): ChessResult<Position>       // перевіряє синтаксис і базову легальність (по одному королю, сторона не під шахом не на своєму ході)
    fun legalMoves(position: Position): List<Move>
    fun legalMovesFrom(position: Position, from: Square): List<Move>   // для підсвічування на дошці
    fun apply(position: Position, move: Move): ChessResult<Position>
    fun parseUci(position: Position, uci: String): ChessResult<Move>
    fun toUci(move: Move): Uci
    fun toSan(position: Position, move: Move): String     // з "+" і "#"
    fun parseSan(position: Position, san: String): ChessResult<Move>
    fun isInCheck(position: Position): Boolean
    fun needsPromotion(position: Position, from: Square, to: Square): Boolean   // UI питає, чи показувати вибір фігури
}
```

### 3.2 Партія [MVP]

```kotlin
data class PlayedMove(val move: Move, val uci: Uci, val san: String, val positionAfter: Position)

interface ChessGame {                                  // immutable; кожна дія повертає новий екземпляр
    val startPosition: Position
    val position: Position
    val moves: List<PlayedMove>
    val status: GameStatus
    fun play(move: Move): ChessResult<ChessGame>       // Err(IllegalMove), якщо хід нелегальний або партію завершено
    fun undo(plies: Int = 1): ChessGame                // plies > moves.size → повертає початкову позицію
    fun positionAt(ply: Int): Position                  // 0 = початкова; для перегляду партії
}

interface ChessGameFactory {
    fun new(start: Position? = null): ChessGame        // null = стандартна початкова позиція
    fun fromPgn(pgn: PgnGame): ChessResult<ChessGame>
    fun toPgn(game: ChessGame, headers: Map<String, String>, result: PgnResult): PgnGame
}

sealed interface GameStatus {
    data object Ongoing : GameStatus
    data class Checkmate(val winner: Color) : GameStatus
    data object Stalemate : GameStatus
    data object InsufficientMaterial : GameStatus
    data class DrawClaimable(val reasons: Set<ClaimReason>) : GameStatus   // партія триває, гравець може вимагати нічию
    data class DrawAutomatic(val reason: AutoDrawReason) : GameStatus
}
enum class ClaimReason { THREEFOLD_REPETITION, FIFTY_MOVE_RULE }
enum class AutoDrawReason { FIVEFOLD_REPETITION, SEVENTY_FIVE_MOVE_RULE }
```

`DrawClaimable` не завершує партію: `play()` дозволено. Поразка через здачу, нічия за згодою тощо це рішення `app` (див. `GameTermination`, розділ 5).

### 3.3 PGN [MVP]

```kotlin
enum class PgnResult(val tag: String) { WHITE_WINS("1-0"), BLACK_WINS("0-1"), DRAW("1/2-1/2"), UNKNOWN("*") }

data class PgnMove(val san: String, val uci: Uci, val comment: String? = null, val nags: List<Int> = emptyList())

data class PgnGame(
    val headers: Map<String, String>,       // порядок: спершу Seven Tag Roster, далі решта
    val startFen: Fen?,                     // з тегів SetUp/FEN
    val moves: List<PgnMove>,
    val result: PgnResult
)

interface PgnCodec {
    fun parse(text: String): Sequence<ChessResult<PgnGame>>   // ліниво; помилка в одній партії не зупиняє наступні
    fun write(game: PgnGame): String                           // UTF-8, рядки ≤ 80 символів
}
```

MVP: лише головна лінія. Варіанти `( … )` пропускаються без помилки. Коментарі `{ … }` і NAG зберігаються. Обмеження розміру імпорту встановлює `app`.

### 3.4 Рушій [MVP]

```kotlin
interface EngineService {
    val state: StateFlow<EngineState>
    val capabilities: EngineCapabilities?             // null до першого успішного start()
    suspend fun start(): ChessResult<Unit>
    suspend fun stop()
    suspend fun newGame()                             // UCI "ucinewgame": очищує стан між партіями
    suspend fun bestMove(request: MoveRequest): ChessResult<EngineMove>
    fun analyse(request: AnalysisRequest): Flow<AnalysisUpdate>   // скасування збору = UCI "stop"
}

sealed interface EngineState {
    data object Stopped : EngineState
    data object Starting : EngineState
    data object Ready : EngineState
    data object Busy : EngineState
    data class Failed(val error: ChessError) : EngineState
}

data class EngineCapabilities(
    val name: String,
    val version: String,
    val eloRange: IntRange?,             // з опції UCI_Elo, яку повідомляє рушій; null, якщо опції немає
    val skillLevelRange: IntRange?,      // з опції "Skill Level"
    val maxMultiPv: Int,
    val threadsMax: Int
)

data class SearchLimit(val depth: Int? = null, val moveTimeMs: Long? = null, val nodes: Long? = null)
// Хоча б одне поле не null, інакше IllegalArgumentException. Нескінченний аналіз: окремо, через analyse() без ліміту → AnalysisRequest.infinite

data class MoveRequest(val startFen: Fen, val moves: List<Uci>, val level: DifficultyLevel)
data class AnalysisRequest(val startFen: Fen, val moves: List<Uci>, val limit: SearchLimit?, val multiPv: Int = 1)
// limit == null → нескінченний аналіз до скасування

data class EngineMove(val best: Uci, val ponder: Uci?)

data class AnalysisUpdate(
    val depth: Int, val selDepth: Int?, val multiPv: Int,
    val evaluation: Evaluation, val pv: List<Uci>,
    val nodes: Long, val nps: Long, val timeMs: Long
)
```

Семантика:
- Один екземпляр на застосунок. Життєвим циклом керує `app` (запуск при потребі, `stop()` при виході у фон або нестачі пам'яті).
- Одночасно виконується **один** пошук. Новий запит скасовує поточний; скасований отримує `ChessError.Cancelled`.
- Позиція передається як початкова FEN + ходи UCI (потрібно для повторень).
- Падіння нативної частини → `EngineState.Failed(EngineCrashed)`. Повторний `start()` дозволено. Застосунок не падає разом із рушієм.
- Діапазони сили беруться лише з `capabilities`. **Інші модулі не хардкодять числа Elo чи Skill Level.**

### 3.5 Рівні складності й слабкі рівні для початківців [MVP]

```kotlin
sealed interface Strength {
    data object Full : Strength
    data class Elo(val elo: Int) : Strength            // лише в межах capabilities.eloRange
    data class SkillLevel(val level: Int) : Strength   // лише в межах capabilities.skillLevelRange
    data class Handicap(val step: Int) : Strength      // слабше за мінімум UCI_Elo; step 1 = найслабший
}

@JvmInline value class DifficultyId(val value: Int)   // стабільний, зберігається в партіях

data class DifficultyLevel(
    val id: DifficultyId,
    val strength: Strength,
    val limit: SearchLimit,
    val minReplyDelayMs: Long,     // мінімальна пауза перед ходом рушія (щоб не було миттєвих відповідей); виконує app
    val approxElo: Int?,           // показується в UI лише якщо не null; для Handicap = null
    val group: DifficultyGroup
)
enum class DifficultyGroup { BEGINNER, AMATEUR, CLUB, STRONG, MAX }

interface DifficultyPresets {
    fun levels(capabilities: EngineCapabilities): List<DifficultyLevel>   // від найслабшого до найсильнішого
    fun byId(id: DifficultyId, capabilities: EngineCapabilities): DifficultyLevel?
}
```

Назви рівнів для UI: ключі `difficulty_<id>` у `strings.xml` (створює Android і UI, список id надає Шахове ядро).

**Вимоги до `Strength.Handicap` (завдання Шахового ядра):**
1. Мінімальне значення UCI_Elo у Stockfish відчутно сильніше за справжнього новачка. Точне значення **не хардкодити**: читати з `capabilities.eloRange` (за пам'яттю в сучасних версіях близько 1320, **перевірити** для обраної версії через вивід опцій UCI).
2. Не менше **3 рівнів** нижче найслабшого рівня UCI_Elo. Точну кількість і калібрування пропонує Шахове ядро.
3. Ослаблення реалізується в `core-chess` поверх рушія (наприклад, вибір серед кількох кандидатів MultiPV з ймовірністю гіршого ходу, обмеження глибини/вузлів, ймовірність не помітити взяття чи мат). Конкретний алгоритм обирає Шахове ядро й документує в `docs/`.
4. Усі ходи **легальні**. Гра має виглядати як помилки людини-початківця, а не як випадкові ходи: найслабший рівень іноді пропускає незахищену фігуру й мат в 1, але не віддає фігури безглуздо на кожному ході.
5. Детермінованість для тестів: джерело випадковості `Random` передається в конструктор реалізації. З однаковим seed і однаковою позицією вибір однаковий.
6. Рушій на рівнях Handicap не заявляє рейтинг Elo (`approxElo = null`), поки немає калібрування.
7. Тести: легальність усіх ходів; статистика на наборі позицій (частка найкращих ходів монотонно зростає від step 1 до найслабшого рівня Elo); найслабший рівень не програє матеріал щоходу у стартових позиціях.

### 3.6 Аналіз позиції [MVP]

Для MVP достатньо `EngineService.analyse()`: шкала оцінки й найкраща лінія на дошці аналізу. Окремого інтерфейсу немає.

### 3.7 Аналіз партій [Пізніше]

```kotlin
interface GameAnalyzer {
    fun review(game: PgnGame, settings: ReviewSettings): Flow<ReviewProgress>   // Progress(done, total) | Done(GameReview)
}
data class ReviewSettings(val limit: SearchLimit, val thresholds: QualityThresholds)   // пороги: Шахове ядро + Методист
data class GameReview(val moves: List<MoveReview>, val criticalPlies: List<Int>)
data class MoveReview(
    val ply: Int, val played: Uci, val bestMoves: List<Uci>, val bestLine: List<Uci>,
    val evalBefore: Evaluation, val evalAfter: Evaluation, val cpLoss: Int,
    val quality: MoveQuality, val facts: List<MoveFact>
)
enum class MoveQuality { BEST, GOOD, INACCURACY, MISTAKE, BLUNDER }
sealed interface MoveFact {
    data class MissedMate(val inMoves: Int) : MoveFact
    data class AllowedMate(val inMoves: Int) : MoveFact
    data class HangsPiece(val square: Square, val piece: Piece) : MoveFact
    data class MaterialLoss(val centipawns: Int) : MoveFact
}
```

Класифікація й факти виводяться лише з оцінок і варіантів рушія та простих перевірок дошки.

## 4. `learning`

**Відповідає за:** логіку задач і підказок (MVP), а згодом рівні гравця, діагностику, курс, повторення, пояснення. **Не відповідає за:** правила шахів, UI, збереження (лише порти), тексти.

### 4.1 Задачі [MVP]

```kotlin
@JvmInline value class PuzzleId(val value: String)
@JvmInline value class ThemeId(val value: String)      // назва для UI: strings.xml, ключ puzzle_theme_<id>

data class Puzzle(
    val id: PuzzleId,
    val startFen: Fen,               // позиція, де ХОДИТЬ ГРАВЕЦЬ
    val solution: List<Uci>,         // гравець, відповідь, гравець, …; завжди непарна довжина
    val themes: Set<ThemeId>,
    val rating: Int,
    val sourceId: String             // ID запису в docs/THIRD_PARTY.md
)

data class PuzzleFilter(
    val themes: Set<ThemeId> = emptySet(),   // порожньо = будь-які
    val ratingRange: IntRange? = null,
    val excludeIds: Set<PuzzleId> = emptySet(),
    val limit: Int = 50
)

enum class PuzzleMode { NEW, RETRY_FAILED }             // REVIEW_DUE додається пізніше
data class PuzzleRequest(val filter: PuzzleFilter, val mode: PuzzleMode)

interface PuzzleService {
    suspend fun next(request: PuzzleRequest): Puzzle?   // null = задачі за фільтром закінчились
    fun session(puzzle: Puzzle): PuzzleSession
}

interface PuzzleSession {
    val puzzle: Puzzle
    val position: Position
    val state: PuzzleState
    suspend fun submit(move: Move): PuzzleStep
    fun hint(): Hint                                    // кожен виклик дає наступний крок підказки (до FullMove)
    fun revealSolution(): List<Uci>                     // задача вважається не розв'язаною
    fun result(): PuzzleResult
}

enum class PuzzleState { IN_PROGRESS, SOLVED, FAILED }

sealed interface PuzzleStep {
    data class Correct(val opponentReply: Move?, val solved: Boolean) : PuzzleStep
    data object Wrong : PuzzleStep                       // позиція не змінюється; спроба зараховується як FAILED
    data object Finished : PuzzleStep                    // хід після завершення ігнорується
}

sealed interface Hint {
    data class Nudge(val theme: ThemeId) : Hint           // крок 1: тема/ідея
    data class Direction(val fromSquare: Square) : Hint   // крок 2: яка фігура
    data class FullMove(val move: Move) : Hint            // крок 3: конкретний хід
}

data class PuzzleResult(val puzzleId: PuzzleId, val state: PuzzleState, val wrongMoves: Int, val hintsUsed: Int, val timeMs: Long)
data class PuzzleAttempt(val result: PuzzleResult, val at: Instant)
```

Правила сесії:
- Хід, що дає мат, приймається, навіть якщо він не збігається з `solution` (перевірка через `ChessRules`). Інші альтернативні ходи в MVP не приймаються.
- Після `Wrong` гравець може продовжити спроби, але результат задачі `FAILED` (для черги повторення).
- Використання `FullMove` або `revealSolution()` → `FAILED`.
- Нормалізацію зовнішніх баз (наприклад, де перший хід належить супернику) виконує підготовка контенту, не застосунок.
- Адаптивний рейтинг гравця в задачах: [Пізніше]. У MVP гравець обирає діапазон складності сам.

### 4.2 Порти для MVP (визначає `learning`, реалізує `app`) [MVP]

```kotlin
interface PuzzleProgressRepository {
    suspend fun recordAttempt(attempt: PuzzleAttempt)
    suspend fun lastResult(id: PuzzleId): PuzzleResult?
    suspend fun failedPuzzleIds(limit: Int): List<PuzzleId>   // FAILED, після яких ще не було SOLVED; найстаріші першими
    suspend fun attemptedIds(): Set<PuzzleId>
    fun stats(): Flow<PuzzleStats>
}
data class PuzzleStats(val attempted: Int, val solved: Int, val failed: Int)

interface ContentSource {
    suspend fun manifest(): ContentManifest
    suspend fun puzzles(filter: PuzzleFilter): List<Puzzle>
    suspend fun puzzle(id: PuzzleId): Puzzle?
}

data class SchemaVersion(val major: Int, val minor: Int)
data class ContentManifest(val schemaVersion: SchemaVersion, val packs: List<ContentPack>)
data class ContentPack(val id: String, val kind: ContentKind, val languages: Set<String>, val itemCount: Int, val sourceIds: Set<String>)
enum class ContentKind { PUZZLES, LESSONS, OPENINGS }
```

### 4.3 Рівні, діагностика, курс [Пізніше]

```kotlin
enum class PlayerLevel { BEGINNER, AMATEUR, CLUB, STRONG }
data class SkillRating(val value: Int, val deviation: Int)
data class PlayerProfile(val level: PlayerLevel, val rating: SkillRating, val topicRatings: Map<ThemeId, SkillRating>)

interface DiagnosticService { fun start(): DiagnosticSession }
interface CurriculumService {
    suspend fun tracks(): List<Track>
    suspend fun lesson(id: LessonId, lang: String): Lesson
    suspend fun nextRecommended(profile: PlayerProfile): LessonRef?
    suspend fun markCompleted(id: LessonId)
}
```

### 4.4 Інтервальне повторення [Пізніше]

```kotlin
interface ReviewScheduler { fun schedule(item: ReviewItem?, grade: ReviewGrade, now: Instant): ReviewItem }
enum class ReviewGrade { AGAIN, HARD, GOOD, EASY }
data class ReviewItem(val id: String, val kind: ReviewKind, val refId: String, val dueAt: Instant, val state: String)
enum class ReviewKind { PUZZLE, LESSON_POSITION, OPENING_LINE, GAME_MISTAKE }
```

### 4.5 Розбір партій і пояснення [Пізніше]

```kotlin
interface GameReviewService { fun review(game: PgnGame, profile: PlayerProfile): Flow<ReviewProgress> }
data class ExplanationSpec(val templateId: String, val args: Map<String, String>)   // MoveFact → шаблон
```

### 4.6 Повний порт прогресу [Пізніше]

`ProgressRepository` (профіль, черга повторення, пройдені уроки) і розширення `ContentSource` (`lesson`, `openings`).

## 5. `app` [MVP]

**Відповідає за:** екрани Compose, навігацію, теми, доступність, життєвий цикл рушія, Room, реалізацію портів `learning`, озвучення, локалізацію, налаштування.

```kotlin
@JvmInline value class GameId(val value: Long)

enum class GameMode { VS_ENGINE, ANALYSIS_BOARD, IMPORTED }
enum class GameTermination { CHECKMATE, STALEMATE, INSUFFICIENT_MATERIAL, DRAW_CLAIMED, DRAW_AUTOMATIC, RESIGNATION, ABANDONED }

data class SavedGame(
    val id: GameId?,                 // null = ще не збережена
    val pgn: String,                 // єдиний носій ходів; внутрішні типи ядра в БД не серіалізуються
    val mode: GameMode,
    val playerColor: Color?,         // для VS_ENGINE
    val difficultyId: DifficultyId?, // для VS_ENGINE
    val finished: Boolean,
    val termination: GameTermination?,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class SavedGameSummary(val id: GameId, val mode: GameMode, val result: PgnResult, val finished: Boolean, val updatedAt: Instant, val plyCount: Int)

interface GameRepository {
    suspend fun save(game: SavedGame): GameId          // вставка або оновлення
    suspend fun load(id: GameId): SavedGame?
    suspend fun delete(id: GameId)
    fun observeAll(): Flow<List<SavedGameSummary>>
    suspend fun lastUnfinished(): SavedGame?           // відновлення партії після перезапуску
}

interface Monetization {                               // MVP: лише NoMonetization
    val isEnabled: Boolean
}
object NoMonetization : Monetization { override val isEnabled = false }
```

Правила:
- Незавершена партія проти рушія автоматично зберігається після кожного ходу.
- `app` не містить шахової логіки: лише виклики `ChessRules`, `ChessGame`, `EngineService`, `PuzzleService`.
- Room: схема експортується (`app/schemas/`), кожна зміна має міграцію й тест.
- Застосунок повністю працює без мережі; дозвіл `INTERNET` у MVP відсутній.
- Пропозиція нічиєї при `DrawClaimable` у грі проти рушія: [MVP], рішення UI (узгодити з Архітектором до реалізації).

## 6. `content` — формат даних

### 6.1 Задачі [MVP]

- Розташування: `content/common/puzzles/<pack>.json` + `content/common/manifest.json`.
- Поля задачі відповідають `Puzzle` (розділ 4.1). `startFen`, `solution` у UCI, `themes` як `ThemeId`, `rating`, `sourceId`.
- `manifest.json` містить `schemaVersion`. Незнайомий MAJOR → застосунок показує помилку й не падає.
- Кожна задача перевіряється скриптом підготовки: FEN валідний, кожен хід розв'язку легальний, довжина непарна. (Чи потрібна додаткова перевірка Stockfish, вирішує Навчання і контент разом із Шаховим ядром.)
- Формат зберігання (JSON чи попередньо зібрана БД) і обсяг вибірки уточнюються після оцінки розміру пакета.

### 6.2 Уроки й тексти [Пізніше]

- Тексти: `content/uk/lessons/…`, `content/en/lessons/…`. Обидві мови обов'язкові (перевірка CI).
- Позиції й вправи уроку мовно-незалежні; тексти посилаються на них через ключі.
- Ліцензія власних текстів: GPL-3.0. Кожен файл має заголовок з ліцензією.

### 6.3 Дебюти [Пізніше]

Дерево ходів (`uci`, `san`, `nameKey`, `commentKey?`) у `content/common/openings/`.

## 7. Тестові вимоги [MVP]

- `ChessRules`: perft на еталонних позиціях. Стартова позиція, глибина 5 = 4 865 609 вузлів. «Kiwipete», глибина 3 = 97 862. Додаткові позиції ядро добирає й документує. Окремі тести на рокіровку, взяття на проході, перетворення, пат, повторення, 50 і 75 ходів, недостатній матеріал.
- `EngineService`: `FakeEngineService` у `:core-chess:api` (testFixtures) для тестів `learning` і `app`.
- `Strength.Handicap`: див. розділ 3.5, п. 7.
- `PgnCodec`: круговий тест (parse → write → parse) і «золоті» файли.
- `learning`: тести на JVM з фейковими портами й фіксованими `Clock`/`Random`.
- Контрактні тести в `tests/contract/` запускаються в CI для кожної реалізації інтерфейсу.

## 8. Відкриті питання контрактів

1. Остаточний вибір бібліотеки правил.
2. Алгоритм і кількість рівнів `Handicap` (Шахове ядро).
3. Формат зберігання бази задач і обсяг вибірки (6.1).
4. Пропозиція нічиєї в грі проти рушія (5).
5. [Пізніше] Алгоритм інтервального повторення, пороги класифікації помилок, схема уроків.
