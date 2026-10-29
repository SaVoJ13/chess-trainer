# CONTRACTS — контракти між модулями

Статус: **ЧЕРНЕТКА v0.5** (Архітектор, 2026-10-29). Структуру модулів погоджено власником і реалізовано в каркасі Gradle (PR #1). Підтверджено: розділи 2 і 3 (Шахове ядро), розділ 5 (Android і UI, закрито рішенням власника 2026-10-29). Чекають підтвердження: `EngineConfig` і `AnalysisEvent` у 3.4 з боку Android і UI; розділ 4 (Навчання і контент).
Підпорядковується `MASTER_SPEC.md`. Якщо є суперечність, діє специфікація.

Код нижче це **специфікація інтерфейсів** (сигнатури й семантика), а не готова реалізація.

**Зміни v0.2 → v0.3** (до v1.0 несумісні зміни допускаються, кожна зафіксована в `project_log.md`):
- Несумісно: `EngineService.analyse()` повертає `Flow<AnalysisEvent>` (було `Flow<AnalysisUpdate>`); `EngineService.start()` приймає `EngineConfig`.
- Додано: `Position.positionKey`; `EngineConfig`; `AnalysisEvent`; `EngineCapabilities.hashMaxMb`; `ChessError.NoLegalMoves`; `ChessRules.isCheckmate/isStalemate/isInsufficientMaterial`.
- Уточнено: `Evaluation.Mate` ніколи не дорівнює 0; `apply` повертає `Err(IllegalMove)`, а `toSan` кидає виняток; правила статусів нічиєї (3.2); `PgnCodec` отримує `ChessRules` через конструктор (3.3); режим розгортання рушія й поведінка на позиції без ходів (3.4); порядок кандидатів у Handicap (3.5).

**Зміни v0.3 → v0.4** (рішення Архітектора й власника 2026-10-28):
- Підтверджено: `ChessError.NoLegalMoves` і визначення `InsufficientMaterial` (3.2).
- Додано: декоратор `HandicapEngineService` у `:core-chess:api` (3.5); специфікація `FakeEngineService` під `AnalysisEvent` і `EngineConfig` (7.3).
- Уточнено: `positionKey` враховує поле взяття на проході лише коли взяття **легально** можливе (2); `NoLegalMoves` не змінює `EngineState` (3.4); у чому саме полягає скасування для `bestMove` і `analyse` (3.4); діапазони UCI_Elo і Skill Level для `sf_19` (3.5, вимога 1).
- Виправлено: коментар до `SearchLimit` посилався на неіснуюче `AnalysisRequest.infinite` (3.4).
- Рішення власника: набір ABI для MVP лише `arm64-v8a` (7.2).

**Зміни v0.4 → v0.5** (Архітектор і власник, 2026-10-29; сигнатури не змінювались):
- Додано: розділ 1.1 «Конфігурація збірки» (`minSdk 26`, `targetSdk 36`, `compileSdk 37`, JVM 17, JDK 21 у CI, тимчасовий `applicationId`).
- Підтверджено: розділ 5 (Android і UI).
- Уточнено: підключення testFixtures (7.3) перевіряється після появи плагіна `java-test-fixtures` у `:core-chess:api`; JDK і версії дій у CI (7.1).

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

### 1.1 Конфігурація збірки [MVP]

Зафіксовано 2026-10-29 за каркасом Gradle (PR #1). Змінює Android і UI лише після рішення Архітектора (MASTER_SPEC 4).

| Параметр | Значення | Де задано |
|---|---|---|
| `minSdk` | 26 | `app`, `core-chess/engine-android` |
| `targetSdk` | 36 | `app` |
| `compileSdk` | 37 | `app`, `core-chess/engine-android` |
| Байткод JVM | 17 (`sourceCompatibility`/`targetCompatibility`, `jvmTarget`) у всіх модулях; без `jvmToolchain` | усі `build.gradle.kts` |
| JDK для збірки | 17+ локально; 21 (Temurin) у CI | `.github/workflows/ci.yml` |
| ABI | лише `arm64-v8a` (`ndk.abiFilters`) | `app`, `core-chess/engine-android` |
| `applicationId` | тимчасово `org.chesstrainer.app`; debug `org.chesstrainer.app.debug` | змінна `appId` у `app/build.gradle.kts`, більше ніде |
| `namespace` | `org.chesstrainer.app`, `org.chesstrainer.engine` | не залежить від `applicationId` |
| Версії залежностей | лише `gradle/libs.versions.toml` (Gradle: `gradle-wrapper.properties`) | кожна спершу в `THIRD_PARTY.md` |
| Дозвіл `INTERNET` | відсутній | `app/src/main/AndroidManifest.xml` |

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

interface Position {                                   // незмінна позиція; рівність двох позицій = рівність positionKey (реалізація перевизначає equals/hashCode)
    val fen: Fen                                       // повний FEN з лічильниками; поле взяття на проході за специфікацією FEN (після кожного подвійного ходу пішака)
    val positionKey: String                            // FEN без лічильників; поле взяття на проході лише коли таке взяття легально можливе (з урахуванням зв'язки й шаху). Ключ повторень позиції
    val sideToMove: Color
    val castling: CastlingRights
    val enPassant: Square?                             // поле взяття на проході, якщо є
    val halfmoveClock: Int
    val fullmoveNumber: Int
    fun pieceAt(square: Square): Piece?
}

sealed interface Evaluation {                          // ЗАВЖДИ з погляду білих (перетворення з UCI робить ядро)
    data class Centipawns(val cp: Int) : Evaluation
    data class Mate(val moves: Int) : Evaluation       // >0: білі матують за N ходів; <0: чорні. Ніколи 0: позицію з матом на дошці рушій не оцінює, її описує GameStatus
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
    data object NoLegalMoves : ChessError              // рушія запитали про позицію без легальних ходів (мат або пат). Очікувана помилка, не збій рушія
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
    fun apply(position: Position, move: Move): ChessResult<Position>   // нелегальний хід → Err(IllegalMove); виняток не кидається
    fun parseUci(position: Position, uci: String): ChessResult<Move>
    fun toUci(move: Move): Uci
    fun toSan(position: Position, move: Move): String     // з "+" і "#"; нелегальний хід → IllegalArgumentException (порушення контракту програмістом)
    fun parseSan(position: Position, san: String): ChessResult<Move>
    fun isInCheck(position: Position): Boolean
    fun needsPromotion(position: Position, from: Square, to: Square): Boolean   // UI питає, чи показувати вибір фігури
    fun isCheckmate(position: Position): Boolean       // шах і немає легальних ходів
    fun isStalemate(position: Position): Boolean       // немає шаху і немає легальних ходів
    fun isInsufficientMaterial(position: Position): Boolean   // правило з розділу 3.2
}
```

Порядок ходів у `legalMoves` / `legalMovesFrom` не гарантується. Споживачі, яким потрібна детермінованість (зокрема шар Handicap, розділ 3.5), сортують самі.

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

Правила статусу (v0.3):
- Пріоритет: мат, пат, недостатній матеріал, автоматична нічия (75 ходів, 5 повторень), `DrawClaimable`, `Ongoing`. Мат, поставлений на 100-му чи 150-му півході, лишається `Checkmate`.
- Повторення рахуються за `Position.positionKey` (розстановка, черга ходу, права рокіровки, поле взяття на проході лише коли взяття легально можливе). Три повторення дають `THREEFOLD_REPETITION` у `DrawClaimable`, п'ять це `FIVEFOLD_REPETITION` автоматично. `halfmoveClock >= 100` дає `FIFTY_MOVE_RULE` у `DrawClaimable`, `>= 150` це `SEVENTY_FIVE_MOVE_RULE` автоматично.
- Вимога нічиєї «за намірюваним ходом» (повторення виникне після ходу) у MVP не підтримується: `DrawClaimable` лише для повторень, що вже виникли.
- `InsufficientMaterial` лише коли мат неможливий за жодної послідовності легальних ходів: K проти K; K+слон проти K; K+кінь проти K; позиції лише з королями й слонами, де всі слони стоять на полях одного кольору. Не є автоматичною нічиєю: K+2 коні проти K, K+кінь проти K+кінь, K+слон проти K+кінь, слони різних кольорів. Заблоковані позиції з пішаками не розпізнаються. **Підтверджено Архітектором 2026-10-28:** визначення відповідає принципу «мат неможливий за жодної послідовності легальних ходів» і не оголошує нічию там, де мат теоретично можливий. Ширше розпізнавання «мертвих» позицій у MVP не потрібне; гравець може вимагати нічию іншими шляхами.

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

MVP: лише головна лінія. Варіанти `( … )` пропускаються без помилки. Коментарі `{ … }` і NAG зберігаються. Обмеження розміру імпорту встановлює `app`. Реалізація `PgnCodec` отримує `ChessRules` через конструктор (потрібна для перетворення SAN в UCI та перевірки легальності ходів); сам інтерфейс не змінюється.

### 3.4 Рушій [MVP]

```kotlin
interface EngineService {
    val state: StateFlow<EngineState>
    val capabilities: EngineCapabilities?             // null до першого успішного start()
    suspend fun start(config: EngineConfig = EngineConfig()): ChessResult<Unit>   // Ready/Busy: повторний start ігнорується (Ok). Змінити налаштування: stop() і start(config)
    suspend fun stop()
    suspend fun newGame()                             // UCI "ucinewgame": очищує стан між партіями
    suspend fun bestMove(request: MoveRequest): ChessResult<EngineMove>
    fun analyse(request: AnalysisRequest): Flow<AnalysisEvent>    // скасування збору = UCI "stop"; Flow завершується разом із пошуком
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
    val threadsMax: Int,                 // межа опції Threads рушія (довідково). Реальну кількість потоків обирає app через EngineConfig
    val hashMaxMb: Int                   // межа опції Hash (довідково)
)

data class SearchLimit(val depth: Int? = null, val moveTimeMs: Long? = null, val nodes: Long? = null)
// Хоча б одне поле не null, інакше IllegalArgumentException. Нескінченний аналіз задається не тут, а через AnalysisRequest.limit == null

data class MoveRequest(val startFen: Fen, val moves: List<Uci>, val level: DifficultyLevel)
data class AnalysisRequest(val startFen: Fen, val moves: List<Uci>, val limit: SearchLimit?, val multiPv: Int = 1)
// limit == null → нескінченний аналіз до скасування

data class EngineMove(val best: Uci, val ponder: Uci?)

data class EngineConfig(val threads: Int = 1, val hashMb: Int = 16)   // значення поза межами capabilities обрізаються реалізацією; типові значення збігаються зі стандартними опціями Stockfish

sealed interface AnalysisEvent {
    data class Update(val update: AnalysisUpdate) : AnalysisEvent
    data object Finished : AnalysisEvent                       // пошук завершився за лімітом; після нього Flow завершується
    data class Failed(val error: ChessError) : AnalysisEvent   // Cancelled (замінений новим запитом), EngineCrashed, EngineUnavailable, NoLegalMoves; після нього Flow завершується
}

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
- Падіння нативної частини → `EngineState.Failed(EngineCrashed)`. Повторний `start()` дозволено. Цільова вимога: застосунок не падає разом із рушієм. У режимі in-process збій рівня процесу (SIGSEGV) цього не гарантує, див. «Режим розгортання».
- Діапазони сили беруться лише з `capabilities`. **Інші модулі не хардкодять числа Elo чи Skill Level.**
- Режим розгортання: in-process JNI чи окремий процес Android Service це деталь реалізації за `EngineService`; контракт однаковий. Прототип етапу 1 порівнює обидва режими, вибір після прототипу (запис у журналі). Критерії порівняння: поведінка при падінні нативного коду, затримка першого ходу й аналізу, споживання пам'яті, складність коду, поведінка при виході у фон. Контрактні тести `EngineService` однакові для обох реалізацій.
- Позиція без легальних ходів: `bestMove` повертає `Err(NoLegalMoves)`, `analyse` видає `Failed(NoLegalMoves)`. `EngineState` при цьому не змінюється (лишається `Ready`): це очікувана помилка запиту, не збій рушія. Реалізація може перевірити це через `ChessRules` без звернення до Stockfish.
- Скасування поточного пошуку новим запитом: попередній `bestMove` повертає `Err(Cancelled)`, попередній `analyse` видає `Failed(Cancelled)` і завершується. Скасування збору `Flow` самим споживачем подій не створює (див. нижче).
- Оцінки в `AnalysisUpdate.evaluation` завжди з погляду білих. Реалізація `EngineService` отримує `ChessRules` через конструктор (щоб визначити, чия черга ходу в корені пошуку).
- `analyse`: кожен рядок MultiPV це окрема подія `Update` (поле `multiPv` = 1..N), глибина зростає. Після `Finished` чи `Failed` Flow завершується; скасування збору споживачем подій не створює.

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
1. Мінімальне значення UCI_Elo у Stockfish відчутно сильніше за справжнього новачка. Точне значення **не хардкодити**: читати з `capabilities.eloRange`. Для `sf_19` Шахове ядро встановило за вихідним кодом: UCI_Elo 1320–3190, Skill Level 0–20 (див. `THIRD_PARTY.md`, SW-001). Ці числа довідкові: інша версія рушія може мати інші межі.
2. Не менше **3 рівнів** нижче найслабшого рівня UCI_Elo. Точну кількість і калібрування пропонує Шахове ядро.
3. Ослаблення реалізується в `core-chess` поверх рушія (наприклад, вибір серед кількох кандидатів MultiPV з ймовірністю гіршого ходу, обмеження глибини/вузлів, ймовірність не помітити взяття чи мат). Конкретний алгоритм обирає Шахове ядро й документує в `docs/`.
4. Усі ходи **легальні**. Гра має виглядати як помилки людини-початківця, а не як випадкові ходи: найслабший рівень іноді пропускає незахищену фігуру й мат в 1, але не віддає фігури безглуздо на кожному ході.
5. Детермінованість для тестів: джерело випадковості `Random` передається в конструктор реалізації. З однаковим seed і однаковою позицією вибір однаковий. Шар Handicap сам стабільно сортує кандидатів (за оцінкою, за рівності за рядком UCI) перед вибором і витрачає однакову кількість викликів `Random` на хід; порядок `ChessRules.legalMoves` не гарантується.
6. Рушій на рівнях Handicap не заявляє рейтинг Elo (`approxElo = null`), поки немає калібрування.
7. Тести: легальність усіх ходів; статистика на наборі позицій (частка найкращих ходів монотонно зростає від step 1 до найслабшого рівня Elo); найслабший рівень не програє матеріал щоходу у стартових позиціях.

**Розміщення шару Handicap (рішення Архітектора 2026-10-28, пропозиція Шахового ядра):** декоратор над `EngineService` у `:core-chess:api`.

```kotlin
class HandicapEngineService(               // :core-chess:api; пакет у межах org.chesstrainer.core на розсуд Шахового ядра
    delegate: EngineService,               // «сира» реалізація з :core-chess:engine-android або FakeEngineService
    rules: ChessRules,
    random: Random
) : EngineService
```

Правила:
- `bestMove` з `Strength.Handicap`: декоратор сам обирає хід за алгоритмом Шахового ядра (кандидати через `delegate.analyse` з MultiPV і/або `ChessRules`), повертає `EngineMove(best, ponder = null)`.
- `bestMove` з будь-якою іншою `Strength`, а також `analyse`, `start`, `stop`, `newGame`, `state`, `capabilities`: передаються в `delegate` без змін. **Аналіз завжди на повну силу**, Handicap його не стосується.
- «Сирі» реалізації `EngineService` (`engine-android`, `FakeEngineService`) запит із `Strength.Handicap` не обробляють: це порушення контракту викликачем, `IllegalArgumentException`. Тест відразу виявить, що декоратор забули підключити.
- `app` у точці збирання залежностей (composition root) **завжди** загортає «сирий» рушій у `HandicapEngineService`. Решта `app` бачить лише загорнутий екземпляр як `EngineService`.
- Декоратор дотримується всіх правил 3.4: один пошук одночасно, `Cancelled` при заміні запиту, `NoLegalMoves`, `EngineCrashed` від `delegate` передаються як є.
- `DifficultyPresets.levels()` не повертає рівні Handicap, якщо `capabilities.maxMultiPv` менше, ніж потрібно алгоритму.
- Контрактні тести `EngineService` (`tests/contract/`) запускаються і для `HandicapEngineService` поверх `FakeEngineService`.

Чому так: шар лишається чистим Kotlin і тестується на JVM без пристрою; не залежить від того, де працює Stockfish (in-process чи окремий процес); `app` і `learning` про нього не знають; `engine-android` лишається тонкою обгорткою. Ризик: у режимі окремого процесу кожен хід Handicap коштує додаткового звернення через IPC. На слабких рівнях ліміти пошуку малі, а `minReplyDelayMs` однаково додає паузу, тому вплив очікується незначним. Прототип має це виміряти.

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
- Ліцензія власних текстів: GPL-3.0-or-later. Кожен файл має SPDX-заголовок (MASTER_SPEC 10).

### 6.3 Дебюти [Пізніше]

Дерево ходів (`uci`, `san`, `nameKey`, `commentKey?`) у `content/common/openings/`.

## 7. Тестові вимоги [MVP]

- `ChessRules`: perft на еталонних позиціях. Стартова позиція, глибина 5 = 4 865 609 вузлів. «Kiwipete», глибина 3 = 97 862. Додаткові позиції ядро добирає з таблиці chessprogramming.org/Perft_Results (на неї посилається `tests/perft.sh` у Stockfish) і документує. Окремі тести на рокіровку, взяття на проході, перетворення, пат, повторення, 50 і 75 ходів, недостатній матеріал.
- Perft виконується в CI (GitHub Actions) за правилами розділу 7.1; червоний CI блокує злиття в `main`.
- Окремі тести на нові методи й правила v0.3: `positionKey` (взяття на проході враховується лише коли воно можливе), `isCheckmate`, `isStalemate`, `isInsufficientMaterial` (зокрема K+кінь проти K+кінь не є нічиєю), пріоритет статусів (мат на 100-му півході), `apply` з нелегальним ходом повертає `Err(IllegalMove)`, `toSan` з нелегальним ходом кидає `IllegalArgumentException`.
- `EngineService`: `FakeEngineService` у `:core-chess:api` (testFixtures) для тестів `learning` і `app`, специфікація в 7.3.
- `Strength.Handicap`: див. розділ 3.5, п. 7.
- `PgnCodec`: круговий тест (parse → write → parse) і «золоті» файли.
- `learning`: тести на JVM з фейковими портами й фіксованими `Clock`/`Random`.
- Контрактні тести в `tests/contract/` запускаються в CI для кожної реалізації інтерфейсу.

### 7.1 CI (GitHub Actions) [MVP]

- Perft-тести мають тег JUnit `perft` (швидкі) і `perft-deep` (глибокі).
- На кожен pull request і push у `main`: збірка, unit-тести всіх JVM-модулів, швидкий perft.
- Раз на добу за розкладом і вручну: глибокий perft.
- Perft виконується на JVM (`:core-chess:api`), без емулятора й без Android.
- Нативний код Stockfish у CI перевіряється збіркою для всіх ABI зі списку (MASTER_SPEC 4). Запуск на пристрої лише вручну (MASTER_SPEC 11).
- **Не ставити фільтр `paths` у workflow, чий статус зроблено обов'язковим (Required) для `main`:** pull request, що не чіпає ці шляхи, ніколи не отримає статус перевірки й зависне в очікуванні.
- JDK у CI: 21 (Temurin). Версії дій GitHub фіксуються в `ci.yml` (на 2026-10-29: `actions/checkout@v7`, `actions/setup-java@v6`, `gradle/actions/setup-gradle@v6`); оновлюються разом із перевіркою сумісності з AGP. Перший прогін CI на GitHub пройшов (PR #1).
- Job `perft-deep` поки заглушка; Шахове ядро замінює її на Gradle-задачу разом із першими perft-тестами.

### 7.2 Набір ABI і тестування без емулятора x86 [MVP]

**Рішення власника 2026-10-28: набір ABI для MVP лише `arm64-v8a`** (і в релізній, і у відладковій збірці).

- `x86_64` у MVP не збирається. Тестування без x86-емулятора йде рівнями нижче.
- `armeabi-v7a` чи `x86_64` можна додати після MVP лише окремим рішенням власника (наприклад, якщо Play Console покаже помітну частку 32-бітних пристроїв; Google Play вимагає 64-бітну версію поряд із 32-бітною).
- Рівні тестування рушія:
  1. Логіка навколо рушія (Handicap, рівні, аналіз, протокол UCI): JVM-тести з `FakeEngineService`.
  2. Протокол UCI зі справжнім Stockfish: на JVM з нативною збіркою для хост-машини (Linux x86_64 у CI) через той самий шар обгортки, якщо Шахове ядро підтвердить, що обгортку можна зібрати для хоста.
  3. Справжня поведінка на Android: фізичний пристрій `arm64-v8a` через USB-налагодження (основний спосіб) або емулятор `arm64-v8a` на хості з ARM-процесором.
  4. Нативний arm64 у GitHub Actions (раннер `ubuntu-24.04-arm`) для збірки й бенчмарка arm64-версії Stockfish під звичайний Linux; перевіряє ARM-код, але не JNI й не особливості Android. Умови безкоштовного використання перевірити.
  5. Дві нативні бібліотеки (`armv8` і `armv8-dotprod`) з вибором за можливостями процесора; налагоджувальний перемикач примусово вмикає базову. Бажано мати телефон без dotprod для ручної перевірки. Обидві бібліотеки належать до ABI `arm64-v8a`, тому рішенню власника не суперечать.

### 7.3 `FakeEngineService` (testFixtures `:core-chess:api`) [MVP]

Пише Шахове ядро. Споживачі: `learning`, `app`, контрактні тести. Підключення: `testImplementation(testFixtures(project(":core-chess:api")))`.

Порядок (рішення 2026-10-29): Шахове ядро додає плагін `java-test-fixtures` у `:core-chess:api` разом із `FakeEngineService`. Після цього Android і UI перевіряє підключення в `:app` (Android-модуль) і `:learning`. До того перевірка відкладена: у каркасі ще немає плагіна, перевіряти нічого. Якщо підключення в Android-модулі не спрацює, запасний варіант: окремий JVM-модуль `:core-chess:testing` (рішення Архітектора за запитом).

```kotlin
class FakeEngineService(
    rules: ChessRules,
    capabilities: EngineCapabilities = DEFAULT_CAPABILITIES
) : EngineService {
    // сценарії (необов'язкові; без них діє поведінка за замовчуванням)
    fun onBestMove(responder: (MoveRequest) -> ChessResult<EngineMove>)
    fun onAnalyse(responder: (AnalysisRequest) -> List<AnalysisEvent>)   // список має закінчуватися Finished або Failed
    fun holdSearches()                    // нові пошуки чекають release(), щоб тест міг перевірити скасування
    fun release()
    fun crash(reason: String = "fake crash")
    // записи для перевірок у тестах
    val appliedConfigs: List<EngineConfig>   // конфігурація після обрізання за capabilities
    val moveRequests: List<MoveRequest>
    val analysisRequests: List<AnalysisRequest>

    companion object { val DEFAULT_CAPABILITIES: EngineCapabilities }
}
```

Поведінка за замовчуванням:
- `start(config)`: `Stopped → Starting → Ready`; `config` обрізається за `capabilities` і записується в `appliedConfigs`. Повторний `start` у `Ready`/`Busy` повертає `Ok` і нічого не змінює. `capabilities` дорівнює `null` до першого успішного `start`.
- Виклики до `start` або після `stop`: `bestMove` → `Err(EngineUnavailable)`, `analyse` → `Failed(EngineUnavailable)`.
- `bestMove`: перший легальний хід після сортування за рядком UCI (детерміновано). Позиція без ходів → `Err(NoLegalMoves)`, стан лишається `Ready`.
- `analyse`: для кожного `multiPv` від 1 до min(запитаний, кількість легальних ходів) одна подія `Update` (глибина 1, `Centipawns(0)`, `pv` з одного ходу в порядку сортування за UCI), потім `Finished`. Позиція без ходів → `Failed(NoLegalMoves)`.
- Заміна запиту новим (під час `holdSearches`): попередній `bestMove` → `Err(Cancelled)`, попередній `analyse` → `Failed(Cancelled)`.
- `crash()`: `state` → `Failed(EngineCrashed)`; активний пошук завершується `Err(EngineCrashed)` / `Failed(EngineCrashed)`; далі як до `start`; повторний `start` дозволено.
- Запит із `Strength.Handicap` → `IllegalArgumentException` (як у «сирій» реалізації, 3.5).
- Без реального часу: жодних `Thread.sleep` і годинника; час і диспетчер передаються ззовні.
- `DEFAULT_CAPABILITIES`: `name = "FakeEngine"`, `version = "0"`, `eloRange = 1320..3190`, `skillLevelRange = 0..20` (як у `sf_19` за записом Шахового ядра), `maxMultiPv = 5`, `threadsMax = 4`, `hashMaxMb = 256`. Останні три це **тестові значення**, не факти про Stockfish.
- `FakeEngineService` сам проходить контрактні тести `EngineService` з `tests/contract/`.

## 8. Відкриті питання контрактів

1. Остаточний вибір бібліотеки правил: умовно chesslib (Apache-2.0) із власними правилами нічиєї; остаточно після успішного perft у CI.
2. Калібрування `Handicap`: п'ять кроків запропоновано, параметри уточнюються після прототипу рушія (Шахове ядро).
3. Режим розгортання рушія: in-process чи окремий процес Service (прототип етапу 1).
4. Формат зберігання бази задач і обсяг вибірки (6.1).
5. Чи можна зібрати JNI-обгортку Stockfish для хоста Linux x86_64 (рівень 2 у 7.2): відповідь Шахового ядра після прототипу.
6. Пропозиція нічиєї в грі проти рушія (5).
7. Підтвердження Шаховим ядром уточнення v0.4 щодо `positionKey` (взяття на проході лише коли воно легально можливе).
8. [Пізніше] Алгоритм інтервального повторення, пороги класифікації помилок, схема уроків.
9. Підтвердження `EngineConfig` і `AnalysisEvent` (3.4) з боку Android і UI.
10. Сумісність testFixtures `:core-chess:api` з `:app` (7.3): після появи `java-test-fixtures`.

Закрито у v0.4: розміщення шару Handicap (3.5), `NoLegalMoves` і `InsufficientMaterial` (3.2), набір ABI (7.2).
Закрито у v0.5: підтвердження розділу 5; конфігурація збірки (1.1).
