# CONTRACTS — контракти між модулями

Статус: **ЧЕРНЕТКА v0.1** (Архітектор, 2026-10-28). Не є остаточною, поки власник проєкту не погодить і поки Шахове ядро та Android і UI не підтвердять, що можуть це реалізувати.
Підпорядковується `MASTER_SPEC.md`. Якщо є суперечність, діє специфікація.

Код нижче це **специфікація інтерфейсів** (сигнатури й семантика), а не готова реалізація. Назви пакетів умовні: `org.<назва>.chesstrainer.<модуль>`, поки не вирішено питання назви.

## 0. Процедура змін

1. Будь-яка зміна контракту (тип, сигнатура, семантика, формат даних) починається із запису в `project_log.md` → «Запити між агентами».
2. Архітектор оцінює вплив на всі модулі й записує рішення в «Рішення».
3. Версія контрактів у цьому файлі збільшується: сумісне доповнення це MINOR (0.1 → 0.2), несумісна зміна це MAJOR.
4. Додавання нового варіанта в `sealed`-ієрархії та `enum` вважається доповненням. Споживачі зобов'язані мати гілку за замовчуванням (`else`), а не падати.
5. Модуль не змінює реалізацію чужого контракту. Потрібна зміна оформлюється запитом.

## 1. Принципи

- **Напрямок залежностей** (зворотні заборонені):

```
app ──► learning ──► core-chess (api)
 │                        ▲
 └────────────────────────┘
content: лише дані, без коду. Читається через порт ContentSource.
core-chess (engine-android) ──► core-chess (api)
```

- `core-chess:api` і `learning` це **чисті Kotlin/JVM-модулі без залежностей від Android**. Тоді їх можна тестувати на JVM без емулятора. Усе, що потребує Android (NDK-обгортка Stockfish, Room, TTS, assets), живе в `core-chess:engine-android` і `app`. *(Це підмодулі всередині `core-chess/`; структура верхнього рівня з розділу 9 специфікації не змінюється. Потребує погодження власника.)*
- **Асинхронність:** `suspend` і `kotlinx.coroutines.flow.Flow`. Диспетчери й час (`Clock`) передаються ззовні (DI вручну або конструктором), щоб тести були детермінованими.
- **Помилки:** очікувані помилки повертаються як `ChessResult<T>` (sealed: `Ok` / `Err(ChessError)`), а не кидаються винятками. Винятки лише для порушення контракту програмістом (`IllegalArgumentException` тощо).
- **Жодного тексту для користувача в `core-chess` і `learning`.** Вони повертають ключі й параметри (`ExplanationSpec`, коди помилок). Локалізація лише в `app` через `strings.xml` (див. розділ 6 щодо великих текстів уроків).
- **Незмінність:** усі моделі даних це immutable `data class`.
- **Продуктивність:** жодна синхронна функція в `ChessRules` не блокує понад кілька мс. Усе, що довше (аналіз, імпорт великого PGN), це `suspend`/`Flow`.

## 2. Спільні типи (`core-chess:api`, пакет `...core.model`)

```kotlin
@JvmInline value class Fen(val value: String)
@JvmInline value class Uci(val value: String)        // "e2e4", "e7e8q"
@JvmInline value class Square(val index: Int)        // 0..63, a1 = 0, h8 = 63

enum class Color { WHITE, BLACK }
enum class PieceType { PAWN, KNIGHT, BISHOP, ROOK, QUEEN, KING }
data class Piece(val color: Color, val type: PieceType)

data class Move(val from: Square, val to: Square, val promotion: PieceType? = null)

interface Position {                       // незмінна позиція
    val fen: Fen
    val sideToMove: Color
    fun pieceAt(square: Square): Piece?
    val halfmoveClock: Int
    val fullmoveNumber: Int
}

sealed interface Evaluation {              // ЗАВЖДИ з погляду білих
    data class Centipawns(val cp: Int) : Evaluation
    data class Mate(val moves: Int) : Evaluation   // >0: білі матують за N ходів, <0: чорні
}

sealed interface ChessResult<out T> {
    data class Ok<T>(val value: T) : ChessResult<T>
    data class Err(val error: ChessError) : ChessResult<Nothing>
}
sealed interface ChessError {              // коди, а не тексти
    data class InvalidFen(val reason: String) : ChessError
    data class IllegalMove(val uci: String) : ChessError
    data class PgnSyntax(val line: Int, val reason: String) : ChessError
    data class EngineUnavailable(val reason: String) : ChessError
    data object Cancelled : ChessError
}
```

## 3. `core-chess` — відповідальність і контракти

**Відповідає за:** правила, легальні ходи, стан партії, FEN/PGN/UCI, інтеграцію Stockfish, аналіз і класифікацію ходів. **Не відповідає за:** UI, збереження, навчальну логіку, тексти.

### 3.1 Правила й партія

Реалізація правил прихована за інтерфейсом, щоб бібліотеку можна було замінити (відкрите питання про вибір бібліотеки).

```kotlin
interface ChessRules {
    fun startPosition(): Position
    fun parseFen(fen: String): ChessResult<Position>
    fun legalMoves(position: Position): List<Move>
    fun apply(position: Position, move: Move): ChessResult<Position>
    fun parseUci(position: Position, uci: String): ChessResult<Move>
    fun toUci(move: Move): Uci
    fun toSan(position: Position, move: Move): String
    fun parseSan(position: Position, san: String): ChessResult<Move>
    fun isInCheck(position: Position): Boolean
}

interface ChessGame {                       // партія = позиція + історія (потрібна для повторень і 50 ходів)
    val startPosition: Position
    val position: Position
    val moves: List<PlayedMove>             // PlayedMove(move, uci, san)
    val status: GameStatus
    fun play(move: Move): ChessResult<ChessGame>   // повертає нову партію (immutable)
    fun undo(plies: Int = 1): ChessGame
}

sealed interface GameStatus {
    data object Ongoing : GameStatus
    data class Checkmate(val winner: Color) : GameStatus
    data object Stalemate : GameStatus
    data object InsufficientMaterial : GameStatus
    data class DrawClaimable(val reason: ClaimReason) : GameStatus   // трикратне повторення, 50 ходів
    data class DrawAutomatic(val reason: AutoDrawReason) : GameStatus // п'ятикратне повторення, 75 ходів
}
```

Політика пропозиції нічиєї в грі проти рушія (автоматично чи за вимогою гравця) це рішення `app`; ядро лише повідомляє статус.

### 3.2 PGN

```kotlin
data class PgnGame(
    val headers: Map<String, String>,
    val startFen: Fen?,                     // з тега FEN, якщо є
    val moves: List<PgnMove>,               // PgnMove(san, uci, comment?, nags)
    val result: PgnResult                   // WHITE_WINS, BLACK_WINS, DRAW, UNKNOWN
)

interface PgnCodec {
    fun parse(text: String): Sequence<ChessResult<PgnGame>>   // лінива обробка файлів з багатьма партіями
    fun write(game: PgnGame): String
}
```

MVP: підтримується лише головна лінія. Варіанти `( … )` пропускаються без помилки. Коментарі `{ … }` і NAG зберігаються. Кодування UTF-8.

### 3.3 Рушій

```kotlin
interface EngineService {
    val state: StateFlow<EngineState>        // Stopped, Starting, Ready, Busy, Failed(reason)
    suspend fun start(): ChessResult<Unit>
    suspend fun stop()
    suspend fun bestMove(request: MoveRequest): ChessResult<EngineMove>
    fun analyse(request: AnalysisRequest): Flow<AnalysisUpdate>      // скасування збору = UCI "stop"
}

data class MoveRequest(val startFen: Fen, val moves: List<Uci>, val strength: Strength, val limit: SearchLimit)
data class AnalysisRequest(val startFen: Fen, val moves: List<Uci>, val limit: SearchLimit, val multiPv: Int = 1)
data class SearchLimit(val depth: Int? = null, val moveTimeMs: Long? = null, val nodes: Long? = null)
data class EngineMove(val best: Uci, val ponder: Uci?)
data class AnalysisUpdate(val depth: Int, val multiPv: Int, val evaluation: Evaluation, val pv: List<Uci>, val nodes: Long, val nps: Long)

sealed interface Strength {
    data object Full : Strength
    data class Elo(val elo: Int) : Strength           // UCI_LimitStrength + UCI_Elo
    data class SkillLevel(val level: Int) : Strength  // Skill Level 0..20
}
```

Семантика:
- Одночасно виконується **один** пошук. Новий запит скасовує поточний (скасований отримує `ChessError.Cancelled`).
- Позиція передається як початкова FEN + список ходів UCI (потрібно для коректних повторень).
- Допустимі діапазони `Elo`/`SkillLevel` залежать від версії Stockfish: `EngineService` надає `val capabilities: EngineCapabilities` (мін/макс Elo, версія, наявність NNUE). **Діапазони не хардкодяться в інших модулях.**
- Життєвий цикл: один екземпляр на застосунок, ним керує `app` (запуск при потребі, зупинка при `onStop`/нестачі пам'яті).

Рівні складності гри (таблиця «рівень → Strength + час на хід») належать `core-chess`:

```kotlin
data class DifficultyLevel(val id: Int, val strength: Strength, val moveTimeMs: Long)
object DifficultyPresets { val all: List<DifficultyLevel> }   // назви для UI: ключі difficulty_<id> у strings.xml
```

### 3.4 Аналіз партій

```kotlin
interface GameAnalyzer {
    fun review(game: PgnGame, settings: ReviewSettings): Flow<ReviewProgress>   // Progress(done, total) ... Done(GameReview)
}

data class GameReview(val moves: List<MoveReview>, val criticalPlies: List<Int>, val summary: ReviewSummary)

data class MoveReview(
    val ply: Int,
    val played: Uci,
    val bestMoves: List<Uci>,
    val bestLine: List<Uci>,
    val evalBefore: Evaluation,
    val evalAfter: Evaluation,
    val cpLoss: Int,                         // з погляду гравця, що ходив, ≥ 0
    val quality: MoveQuality,                // BEST, GOOD, INACCURACY, MISTAKE, BLUNDER
    val facts: List<MoveFact>                // машинозчитувані факти
)

sealed interface MoveFact {
    data class MissedMate(val inMoves: Int) : MoveFact
    data class AllowedMate(val inMoves: Int) : MoveFact
    data class HangsPiece(val square: Square, val piece: Piece) : MoveFact
    data class MaterialLoss(val centipawns: Int) : MoveFact
    // розширюється (див. розділ 0, п. 4)
}
```

Принципи:
- Класифікація і факти виводяться **лише з оцінок і варіантів рушія** та простих перевірок дошки. Ядро не вигадує пояснень.
- Пороги класифікації (`ReviewSettings`) мають значення за замовчуванням, які затверджують спільно Шахове ядро і Методист. Значень у цьому документі навмисно немає.
- Перетворення `MoveFact` → людський текст виконує `learning` (вибір шаблону) і `app` (локалізований рядок).

## 4. `learning` — відповідальність і контракти

**Відповідає за:** рівні гравця, діагностику, рекомендації, підказки, інтервальне повторення, логіку задач і курсу, складання пояснень з фактів. **Не відповідає за:** правила шахів, UI, збереження (порти), тексти (ключі).

```kotlin
enum class PlayerLevel { BEGINNER, AMATEUR, CLUB, STRONG }
data class SkillRating(val value: Int, val deviation: Int)           // алгоритм оцінювання, внутрішня справа learning
data class PlayerProfile(val level: PlayerLevel, val rating: SkillRating, val topicRatings: Map<TopicId, SkillRating>)

interface DiagnosticService {
    fun start(): DiagnosticSession           // nextTask(): DiagnosticTask?, submit(answer), result(): LevelEstimate
}

interface CurriculumService {
    suspend fun tracks(): List<Track>
    suspend fun lesson(id: LessonId): Lesson
    suspend fun nextRecommended(profile: PlayerProfile): LessonRef?
    suspend fun markCompleted(id: LessonId)
}

interface PuzzleService {
    suspend fun next(request: PuzzleRequest): Puzzle?                // фільтр: тема, діапазон рейтингу, лише повторення
    fun session(puzzle: Puzzle): PuzzleSession
}

interface PuzzleSession {
    val position: Position
    suspend fun submit(move: Move): PuzzleStep      // Correct(opponentReply, solved) | Wrong
    fun hint(): Hint                                 // 3 кроки (розділ 7 специфікації)
    fun result(): PuzzleResult                       // solved, mistakes, hintsUsed, timeMs
}

sealed interface Hint {
    data class Nudge(val themeKey: String) : Hint               // 1: натяк (ідея/тема)
    data class Direction(val fromSquare: Square) : Hint         // 2: яку фігуру/поле розглянути
    data class FullMove(val move: Move) : Hint                  // 3: конкретний хід
}
```

Задачі: у даних зберігається **початкова позиція, де ходить гравець**, і розв'язок як послідовність `Uci` (гравець, відповідь, гравець, …). Якщо в позиції є кілька виграшних ходів (наприклад, кілька матів у 1), `PuzzleSession` приймає будь-який хід, що дає мат (перевірка через `ChessRules`). Імпортер зовнішніх баз (наприклад, формат Lichess, де перший хід це хід суперника) нормалізує дані при підготовці контенту, а не в застосунку.

Інтервальне повторення (алгоритм прихований за інтерфейсом, вибір алгоритму за Методистом, з перевіркою ліцензії, якщо це не власна реалізація):

```kotlin
interface ReviewScheduler {
    fun schedule(item: ReviewItem?, grade: ReviewGrade, now: Instant): ReviewItem  // AGAIN, HARD, GOOD, EASY
}
data class ReviewItem(val id: String, val kind: ReviewKind, val refId: String, val dueAt: Instant, val state: String)
// kind: PUZZLE, LESSON_POSITION, OPENING_LINE, GAME_MISTAKE. state: непрозорий рядок алгоритму
```

Розбір партій:

```kotlin
interface GameReviewService {
    fun review(game: PgnGame, profile: PlayerProfile): Flow<ReviewProgress>   // використовує GameAnalyzer
}
data class ExplanationSpec(val templateId: String, val args: Map<String, String>)
// MoveReview + MoveFact → ExplanationSpec. Невідомий факт → шаблон за замовчуванням.
```

**Порти (інтерфейси, які визначає `learning`, реалізує `app`):**

```kotlin
interface ProgressRepository {
    suspend fun profile(): PlayerProfile?
    suspend fun saveProfile(profile: PlayerProfile)
    suspend fun recordAttempt(attempt: PuzzleAttempt)
    suspend fun dueReviews(now: Instant, limit: Int): List<ReviewItem>
    suspend fun saveReview(item: ReviewItem)
    suspend fun completedLessons(): Set<LessonId>
}

interface ContentSource {                   // реалізація в app читає assets
    suspend fun manifest(): ContentManifest        // schemaVersion, мови, лічильники
    suspend fun lesson(id: LessonId, lang: String): Lesson
    suspend fun puzzles(filter: PuzzleFilter): List<Puzzle>
    suspend fun openings(): List<OpeningNode>
}
```

## 5. `app` — відповідальність

**Відповідає за:** екрани Compose, навігацію, теми, доступність, життєвий цикл рушія, локальне збереження (Room), реалізацію портів `learning`, озвучення (Android TTS і звукові ефекти), локалізацію, налаштування, монетизацію (якщо буде, ізольовано).

- `GameRepository` (збереження партій) визначається в `app`: зберігає **текст PGN + метадані** (дата, результат, рівень). PGN це формат обміну й міграції; внутрішні типи ядра в БД не серіалізуються.
- Room: схема експортується в репозиторій (`schemas/`), кожна зміна має міграцію й тест міграції.
- `app` не містить шахової логіки (легальність ходів, оцінки): лише виклики `ChessRules`/`ChessGame`/`EngineService`.
- Монетизація (якщо буде) ізольована за інтерфейсом `Monetization` з no-op реалізацією за замовчуванням, щоб збірка без неї була повноцінною.
- Локалізація: `strings.xml` у `values/` (en) і `values-uk/` (uk). Відсутність мережі не ламає застосунок.

## 6. `content` — формат даних (чернетка схем; затверджує Архітектор після пропозиції агента «Навчання і контент»)

- Кожен пакет має `manifest.json` з `schemaVersion`. Застосунок відмовляється читати незнайомий MAJOR і показує зрозумілу помилку.
- Задача: `id`, `startFen`, `solution: [uci…]`, `themes: [themeId…]`, `rating`, `source` (ключ запису в `THIRD_PARTY.md`), `license`.
- Урок: `id`, `track`, `level`, `topics`, `exercises` (позиції FEN + очікувані ходи), `schemaVersion`; кожна позиція та твердження перевірені Stockfish (процес описує агент «Навчання і контент»).
- Дебют: дерево ходів (`uci`, `san`, `name`, `commentKey?`).
- **Питання, що потребує рішення власника:** MASTER_SPEC розділ 3 вимагає всі тексти через `strings.xml`. Для великих уроків це незручно. **Пропозиція:** UI-тексти лишаються в `strings.xml`, а тексти уроків і пояснень живуть у `content/<lang>/…` (uk, en) як дані з обов'язковою наявністю обох мов (перевірка в CI). Потребує зміни розділу 3 специфікації.

## 7. Тестові вимоги до контрактів

- `ChessRules`: тести perft на еталонних позиціях. Стартова позиція, глибина 5 = 4 865 609 вузлів. Позиція «Kiwipete», глибина 3 = 97 862. Додаткові позиції ядро добирає самостійно й документує. Плюс окремі тести на рокіровку, взяття на проході, перетворення, пат, повторення, 50 ходів.
- `EngineService`: є `FakeEngineService` для тестів `learning` і `app` без реального Stockfish.
- `PgnCodec`: круговий тест (parse → write → parse) і «золоті» файли.
- `learning`: тести на JVM з `FakeProgressRepository`, `FakeContentSource`, фіксованим `Clock`.
- Контрактні тести тримаються в `tests/contract/` і запускаються в CI для всіх реалізацій інтерфейсу.

## 8. Відкриті питання контрактів

1. Остаточний вибір бібліотеки правил і чи вона підтримує історію позицій для повторень (перевірити).
2. Формат і місце зберігання текстів уроків (розділ 6).
3. Чи виділяти `chess-model` в окремий мікромодуль, якщо `core-chess:api` стане великим.
4. Алгоритм інтервального повторення (рішення Методиста).
5. Пороги класифікації помилок (рішення Шахового ядра разом із Методистом).
6. Політика автоматичних нічиїх у грі проти рушія (рішення `app`, узгодити з Архітектором).
