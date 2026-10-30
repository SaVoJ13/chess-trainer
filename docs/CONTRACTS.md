# CONTRACTS — контракти між модулями

Статус: **ЧЕРНЕТКА v0.10** (Архітектор, 2026-10-30). Структуру модулів погоджено власником і реалізовано в каркасі Gradle (PR #1). Підтверджено: розділи 2 і 3 (Шахове ядро), розділ 5 (Android і UI, закрито рішенням власника 2026-10-29). `EngineConfig` і `AnalysisEvent` (3.4) підтвердив Android і UI 2026-10-29. Реалізовано в `:core-chess:api`: розділи 2, 3.1–3.3 (PR #4, #6). Чекає підтвердження: розділ 4 (Навчання і контент).
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

**Зміни v0.5 → v0.6** (Архітектор, 2026-10-29, за реалізацією `FakeEngineService` Шаховим ядром; сигнатури не змінювались):
- Уточнено семантику `EngineService` (3.4) для всіх реалізацій: `start()` у стані `Starting`; `stop()` під час пошуку; «останній запит перемагає» також для запитів, що завершуються помилкою (`NoLegalMoves`, некоректна позиція); помилки некоректної позиції в запиті.
- 7.3: поведінка `FakeEngineService` узгоджена з 3.4; `StubRules` лишається в `src/test` (не частина API фікстур).

**Зміни v0.9 → v0.10** (Архітектор, 2026-10-30, за PR #10; сигнатури не змінювались):
- 5: правила автозбереження й відновлення партії, Room на системному SQLite; `difficultyId` до появи `DifficultyPresets` не зберігається; тимчасовий фіксований рівень без `HandicapEngineService` дозволено до появи Handicap.

**Зміни v0.8 → v0.9** (Архітектор, 2026-10-30, за звітом G1–G3, PR #9; сигнатури не змінювались):
- 3.4: режим окремого процесу `:engine` **остаточний**; умови G1–G3 закрито; правило розбору рядків `info` і визначення метрики «без втрат»; відповідь на `stop` за таймаутом дає `EngineUnavailable`.
- 3.5: місце опису алгоритму Handicap, критерії калібрування, інструмент калібрування.
- 7.2: smoke-тест на емуляторі API 26 замінено статичною перевіркою байткоду в CI і перевіркою на другому фізичному пристрої.
- 8: закрито пункти 11, 13, 14, 15; додано пункт 16 (≈ 100 МБ рідної купи невідомого походження).

**Зміни v0.7 → v0.8** (Архітектор, 2026-10-30, за прототипом Stockfish, PR #7; сигнатури не змінювались):
- 3.4: **режим розгортання рушія обрано: окремий процес `:engine` (`RemoteEngineService`), умовно**, з винятком щодо порогу пам'яті й умовами G1–G3; поріг потоку `analyse` переформульовано; додано таймаути й сторожовий механізм проти зависань; правила життєвого циклу рушія.
- 7.2: рівень 2 (JNI для хоста) скасовано; додано рівень контрактних тестів на пристрої.
- 8: закрито пункти 3, 5, 12; пункт 11 звужено до перевірки на емуляторі; додано пункти 13–15.

**Зміни v0.6 → v0.7** (Архітектор, 2026-10-29, за реалізацією Шахового ядра в PR #6; сигнатури не змінювались):
- Затверджено рішення Шахового ядра: `parseFen` приймає 4–6 полів (3.1); `InsufficientMaterial` завершує партію; причина `SEVENTY_FIVE_MOVE_RULE` має пріоритет над `FIVEFOLD_REPETITION`; `fromPgn` відтворює ходи лише за легальністю (3.2); деталі формату PGN (3.3).
- 6.1: задачі зберігають канонічний FEN з 6 полів.
- 7.1: «тег perft» реалізовано як JUnit 4 Categories `Perft`/`PerftDeep` із задачами `perftFast`/`perftDeep`; нічний job викликає `:core-chess:api:perftDeep`.
- 3.4: критерії вибору режиму розгортання рушія конкретизовано під прототип.
- 8: закрито пункти 1, 7, 9, 10; додано ризики Java 11 на API 26–29 і вирівнювання 16 КБ.

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
    fun parseFen(fen: String): ChessResult<Position>       // 4–6 полів (без лічильників: 0 і 1); перевіряє синтаксис і базову легальність (по одному королю, сторона не під шахом не на своєму ході, права рокіровки й поле взяття узгоджені з розстановкою). Position.fen завжди повертає канонічні 6 полів
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

Завершення партії (підтверджено Архітектором 2026-10-29, v0.7): партія завершена при `Checkmate`, `Stalemate`, `InsufficientMaterial` і `DrawAutomatic`; у всіх чотирьох випадках `play()` повертає `Err(IllegalMove)`. `InsufficientMaterial` завершує партію так само, як мат: за правилами FIDE партія в позиції, де жоден бік не може поставити мат, закінчується нічиєю одразу, вимога не потрібна. `undo()` і `positionAt()` працюють і для завершеної партії.

Правила статусу (v0.3):
- Пріоритет: мат, пат, недостатній матеріал, автоматична нічия (75 ходів, 5 повторень), `DrawClaimable`, `Ongoing`. Мат, поставлений на 100-му чи 150-му півході, лишається `Checkmate`.
- Повторення рахуються за `Position.positionKey` (розстановка, черга ходу, права рокіровки, поле взяття на проході лише коли взяття легально можливе). Три повторення дають `THREEFOLD_REPETITION` у `DrawClaimable`, п'ять це `FIVEFOLD_REPETITION` автоматично. `halfmoveClock >= 100` дає `FIFTY_MOVE_RULE` у `DrawClaimable`, `>= 150` це `SEVENTY_FIVE_MOVE_RULE` автоматично.
- Якщо одночасно `halfmoveClock >= 150` і п'ятикратне повторення, `DrawAutomatic.reason` = `SEVENTY_FIVE_MOVE_RULE` (v0.7). На результат партії це не впливає.
- Вимога нічиєї «за намірюваним ходом» (повторення виникне після ходу) у MVP не підтримується: `DrawClaimable` лише для повторень, що вже виникли.
- `InsufficientMaterial` лише коли мат неможливий за жодної послідовності легальних ходів: K проти K; K+слон проти K; K+кінь проти K; позиції лише з королями й слонами, де всі слони стоять на полях одного кольору. Не є автоматичною нічиєю: K+2 коні проти K, K+кінь проти K+кінь, K+слон проти K+кінь, слони різних кольорів. Заблоковані позиції з пішаками не розпізнаються. **Підтверджено Архітектором 2026-10-28:** визначення відповідає принципу «мат неможливий за жодної послідовності легальних ходів» і не оголошує нічию там, де мат теоретично можливий. Ширше розпізнавання «мертвих» позицій у MVP не потрібне; гравець може вимагати нічию іншими шляхами.

`ChessGameFactory.fromPgn` (підтверджено Архітектором 2026-10-29, v0.7): відтворює ходи **лише за легальністю** й не зупиняється на автоматичній нічиїй. Причина: правила 75 ходів і п'ятикратного повторення діють у FIDE лише з 2014 року, і історичні партії можуть їх перевищувати; відмова імпортувати таку партію була б хибою. Наслідки:
- `status` імпортованої партії обчислюється за правилами для фінальної позиції й може не збігатися з тегом `Result` (здача, перевищення часу, нічия за згодою). Для показу результату імпортованої партії `app` бере `PgnGame.result`, а не `status`.
- Імпортована партія з `status` = `DrawAutomatic` не продовжується через `play()`; перегляд (`positionAt`) працює.
- Помилки: `PgnIllegalMove(gameIndex = 0, ply = номер півходу з 1, san)`. У `fromPgn` партія завжди одна, тому `gameIndex` = 0.

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

Деталі формату (`DefaultPgnCodec`, затверджено Архітектором 2026-10-29, v0.7):
- `parse`: `gameIndex` нумерується з 0 у межах тексту; `PgnMove.san` це канонічний SAN від `ChessRules.toSan` (не сирий текст файлу); теги `SetUp`/`FEN` не потрапляють у `headers`, їх представляє `startFen`; відсутні теги Seven Tag Roster отримують значення за стандартом PGN (`?`, `????.??.??`, `*`); суфікси `!`, `?`, `!!`, `??`, `!?`, `?!` стають NAG 1–6; пробіли в коментарях стискаються.
- Обмеження MVP: коментар перед першим ходом (коментар до партії) не зберігається; у `write` символ `}` усередині коментаря замінюється на `)`. Обидва призводять до втрати даних при круговому перетворенні, лише якщо вони є в коментарях; для MVP прийнятно. Якщо коментар до партії знадобиться (наприклад, для розбору партій на етапі 3), Шахове ядро подає запит на поле в `PgnGame`.
- `write`: текст закінчується порожнім рядком; рядки ходів ≤ 80 символів (слово, довше за 80, і рядки тегів не переносяться).

### 3.4 Рушій [MVP]

```kotlin
interface EngineService {
    val state: StateFlow<EngineState>
    val capabilities: EngineCapabilities?             // null до першого успішного start()
    suspend fun start(config: EngineConfig = EngineConfig()): ChessResult<Unit>   // Ready/Busy: повторний start ігнорується (Ok); Starting: див. семантику нижче. Змінити налаштування: stop() і start(config)
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
- **Життєвий цикл (v0.8):** `app` викликає `start()` лише на екранах, яким потрібен рушій (гра проти рушія, дошка аналізу, згодом розбір партій), і `stop()` при виході з них, при `onTrimMemory` і при переході у фон. Між партіями на тому самому екрані використовується `newGame()`, а не `stop()`+`start()`. Причина: `sf_19` тримає один рушій на процес (знахідка прототипу), тож у режимі окремого процесу кожен `stop()`+`start()` це новий процес (≈ 0,7 с), зате `stop()` повертає системі всю пам'ять рушія (≈ 110 МБ мережі + Hash).
- Одночасно виконується **один** пошук. Новий запит скасовує поточний; скасований отримує `ChessError.Cancelled`.
- Позиція передається як початкова FEN + ходи UCI (потрібно для повторень).
- Падіння нативної частини → `EngineState.Failed(EngineCrashed)`. Повторний `start()` дозволено. Застосунок не падає разом із рушієм: це гарантує обраний режим окремого процесу (виявлення через `DeathRecipient`, у прототипі 846 мс). У режимі in-process збій рівня процесу (SIGSEGV, `abort()`) цього не гарантує.
- Діапазони сили беруться лише з `capabilities`. **Інші модулі не хардкодять числа Elo чи Skill Level.**
- **Таймаути й захист від зависань (v0.8, обидва режими):** жоден виклик не чекає нескінченно.
  - `start()`: не довше 15 с (зокрема перший запуск із копіюванням мережі на повільному пристрої), інакше `Err(EngineUnavailable)`, стан `Failed(EngineUnavailable)`, ресурси звільнено (у режимі окремого процесу процес `:engine` завершено).
  - `bestMove` зі скінченним лімітом: не довше `moveTimeMs` (або оцінки за `depth`/`nodes`) + 5 с, інакше `Err(EngineUnavailable)` і перехід у `Failed`.
  - Транспорт (Binder або JNI-міст) перевіряється сторожовим механізмом: якщо рушій не відповідає на службовий запит живості протягом 10 с, поточний пошук завершується `Failed(EngineUnavailable)`, стан `Failed(EngineUnavailable)`. Нескінченний аналіз сам по собі (довгі паузи між глибинами) зависанням не вважається.
  - Рушій не відповів на `stop` під час скасування за 5 с: скасований пошук завершується `Cancelled`, а рушій переходить у `Failed(EngineUnavailable)` (не `EngineCrashed`: процес живий, але не відповідає) (v0.9, за реалізацією).
  - Повторний `start()` після будь-якого `Failed` дозволено. Числа стартові, Шахове ядро може уточнити їх вимірюваннями через запит.
- Режим розгортання: in-process JNI чи окремий процес Android Service це деталь реалізації за `EngineService`; контракт однаковий. Контрактні тести `EngineService` однакові для обох реалізацій.
- **Правило вибору режиму (v0.7, Архітектор):** за замовчуванням обирається **окремий процес**, якщо він проходить усі пороги нижче; інакше in-process. Причина: лише окремий процес дає ізоляцію збою нативного коду (MASTER_SPEC 11). Автозбереження після кожного ходу (розділ 5) не дає втратити партію і в режимі in-process, але падіння всього застосунку для користувача гірше за повідомлення «рушій перезапущено».

| Критерій | Поріг для окремого процесу (на телефоні власника, медіана з 5 запусків) |
|---|---|
| Ізоляція збою | після навмисного збою рушія (тестовий `abort()` у JNI) застосунок живий, `state` = `Failed(EngineCrashed)`, повторний `start()` дає `Ready` |
| Холодний `start()` до `Ready` | не більше ніж на 300 мс довше, ніж in-process |
| Накладні IPC на `bestMove` | p95 різниці з in-process ≤ 20 мс при `SearchLimit(nodes = 1)` або мінімальній глибині (саме IPC, без пошуку) |
| Потік `analyse` (переформульовано v0.8) | **без втрат і без затримки:** кількість рядків `info … pv`, розібраних на боці рушія, дорівнює кількості `Update`, отриманих споживачем (лічильники в debug), для MultiPV 1 і 5; затримка доставки p95 ≤ 50 мс. Частота подій не нормується: її задає Stockfish (≈ 2–3 на секунду на один PV; після глибини ~20 паузи в секунди). Плавність UI під час аналізу перевіряється окремо на етапі UI |
| Додаткова пам'ять | сумарний PSS (процес застосунку + процес рушія) мінус PSS in-process ≤ 40 МБ за однакових `EngineConfig` |
| Поведінка у фоні | після 1 хв у фоні й повернення застосунок відновлює рушій без ручних дій користувача |

Пороги задав Архітектор як стартові; якщо вимірювання покажуть, що поріг нереалістичний для обох режимів, Архітектор переглядає його в журналі до ухвалення рішення. Порушення «ізоляції збою» в окремому процесі є дефектом реалізації, а не причиною обрати in-process.

**Рішення за прототипом (Архітектор, 2026-10-30, v0.8): окремий процес `:engine` (`RemoteEngineService`). З v0.9 остаточне (умови G1–G3 виконано, див. нижче).**

| Критерій | in-process | окремий процес | Висновок |
|---|---|---|---|
| Ізоляція збою | застосунок помирає | живий; `Failed(EngineCrashed)` за 846 мс, `start()` → `Ready` за 774 мс | окремий процес: виконано |
| Холодний `start()` | 421 мс | 682 мс (Δ 261) | виконано (поріг 300) |
| Накладні IPC p95 | 2,19 мс | 2,46 мс (Δ 0,27) | виконано (поріг 20) |
| Потік `analyse` | ≈ 2,5 `Update`/с | ≈ 2,5 `Update`/с, кількість збігається | старий поріг 10/с недосяжний для обох режимів, переформульовано вище; новий не виміряно напряму (умова G3) |
| Додаткова пам'ять | 369 МБ | 428 МБ (Δ +58) | **не виконано** (поріг 40) |
| Фон 1 і 10 хв | відновлюється | відновлюється | виконано |

За буквою правила (провалений поріг пам'яті) слід обрати in-process. **Архітектор робить свідомий виняток** і записує його відкрито, з таких причин:
1. Поріг пам'яті вимірював лише пік під час роботи рушія. Прототип виявив обставину, якої правило не передбачало: `sf_19` тримає один рушій на процес, тож **у режимі in-process пам'ять мережі (≈ 110 МБ) не повертається після `stop()`** до завершення всього застосунку. В окремому процесі `stop()` завершує `:engine` і повертає всю пам'ять. За правилами життєвого циклу вище рушій живе лише на екранах, де потрібен, тому сумарне споживання за сесію в окремому процесі очікувано **менше**, а не більше. Умова G2 це підтвердила з запасом: in-process після `stop()` утримує **+210 МБ** PSS, окремий процес +2,9 МБ.
2. Ізоляція збою була головною причиною правила й вимогою MASTER_SPEC 11; in-process її не дає.
3. Надлишок +58 МБ це вартість окремого ART-процесу (≈ 47 МБ), а не неефективність коду; на слабких пристроях вона діє лише поки відкрито екран рушія.

Умови, без яких рішення не остаточне (виконує Шахове ядро; до інтеграції рушія в екрани `app` на етапі 2):
- **G1. Зависання.** Причину двох зупинок remote-аналізу на ~22–25-му перезапуску встановлено й усунено, **або** таймаути й сторожовий механізм вище реалізовано й доведено тестом (штучне зависання транспорту дає `Failed(EngineUnavailable)` ≤ 10 с, повторний `start()` працює). Плюс прогін ≥ 200 циклів `start`/`analyse`/`stop` без жодного незавершеного виклику, з легким трасуванням (лічильники й мітки часу в кільцевому буфері, а не повний UCI у logcat, бо він змінює таймінги), за змоги без USB-живлення.
- **G2. Пам'ять після `stop()`.** PSS після `stop()` в обох режимах (очікування: in-process утримує ≈ 110 МБ, окремий процес повертає майже все).
- **G3. Потік `analyse` за новим порогом** (лічильники «розібрано / отримано», затримка p95) для MultiPV 1 і 5 в обох режимах.

Якщо G1 не вдається виконати (зависання відтворюється й не усувається, а таймаути не рятують), Архітектор перемикає режим на in-process: реалізації мають спільний `UciEngineService` і відрізняються транспортом, тож це дешево; тоді вимогу MASTER_SPEC 11 про ізоляцію збою буде переглянуто з власником. Власник може скасувати виняток щодо пам'яті й вимагати in-process.

**Результати умов (Шахове ядро, `PROTOTYPE.md` розділи 10–11; закрито Архітектором 2026-10-30, v0.9):**
- **G1 виконано.** Причину відтворюваного зависання знайдено: новий `bindService` до смерті старого `:engine` створював запис сервісу без процесу (у старій поведінці 20 збоїв `start()` на 40 циклів). Усунено трьома рівнями (очікування смерті старого процесу в `close()`/`open()`, повторна прив'язка, таймаути й сторож). Прогони фінальної збірки: remote 240 + 240, in-process 240, remote з 10-секундним аналізом 60: **0** збоїв `start`, **0** незавершених викликів, запасні механізми не спрацьовували. Тест штучного зависання: `analyse` 9,8 с, `bestMove` 5,5 с, `start` 10 с до `Failed(EngineUnavailable)`, повторний `start()` працює. Контрактні тести 20/20 в обох режимах.
  Залишковий ризик, прийнятий: давню «тишу > 15 хв» не відтворено й до кінця не пояснено, один збій тесту зависання з 15 на проміжній збірці (до повторної прив'язки) не діагностовано. Обидва випадки тепер обмежені таймаутами: найгірший результат це помилка з перезапуском, а не нескінченне очікування. Умова: `EngineTrace` (кільцевий буфер і лічильники) лишається в debug-збірці, а стрес-сценарій на 240 циклів повторюється перед кожним релізом і після кожної зміни транспорту (QA).
- **G2 виконано** (див. п. 1 вище).
- **G3 виконано.** Без втрат у 20 прогонах з 20 (MultiPV 1 і 5, обидва режими), затримка доставки p95 ≤ 2,6 мс (поріг 50).
- Пам'ять під час роботи (сумарний PSS remote мінус in-process) у повторному вимірі +60…+90 МБ. Виняток щодо порогу 40 МБ лишається в силі з тих самих причин.

- Позиція без легальних ходів: `bestMove` повертає `Err(NoLegalMoves)`, `analyse` видає `Failed(NoLegalMoves)`. `EngineState` при цьому не змінюється (лишається `Ready`): це очікувана помилка запиту, не збій рушія. Реалізація може перевірити це через `ChessRules` без звернення до Stockfish.
- Скасування поточного пошуку новим запитом: попередній `bestMove` повертає `Err(Cancelled)`, попередній `analyse` видає `Failed(Cancelled)` і завершується. Скасування збору `Flow` самим споживачем подій не створює (див. нижче).
- **«Останній запит перемагає» (v0.6):** будь-який запит `bestMove`/`analyse`, прийнятий у стані `Ready`/`Busy`, скасовує попередній пошук, **навіть якщо сам завершиться помилкою** (`NoLegalMoves`, `InvalidFen`, `IllegalMove`). Не скасовують: запит у стані `Stopped`/`Starting`/`Failed` (одразу `EngineUnavailable`, скасовувати нічого) і запит, що порушує передумову контракту (`IllegalArgumentException`, наприклад `Strength.Handicap` у «сирій» реалізації), бо він кидається до прийняття. Причина: UI, перейшовши до нової позиції, має бути певним, що старий пошук зупинено, незалежно від результату нового.
- **Некоректна позиція в запиті (v0.6):** `startFen` не розбирається → `Err(InvalidFen)` / `Failed(InvalidFen)`; хід у `moves` нелегальний → `Err(IllegalMove)` / `Failed(IllegalMove)`. Як і `NoLegalMoves`, це помилка запиту: стан повертається в `Ready`.
- **`stop()` під час пошуку (v0.6):** активний пошук завершується `Err(Cancelled)` / `Failed(Cancelled)`, стан `Stopped`. (Не `EngineUnavailable`: пошук перервав сам клієнт, а не збій рушія.)
- **`start()` у стані `Starting` (v0.6):** повторний виклик не запускає другий рушій, а **чекає завершення поточного запуску й повертає той самий результат** (`Ok` або `Err`). Повертати `Ok` до досягнення `Ready` заборонено: інакше наступний `bestMove` отримає `EngineUnavailable`. Реалізація, у якої запуск атомарний і стан `Starting` ззовні не спостерігається (як у `FakeEngineService`), вимогу виконує автоматично.
- Оцінки в `AnalysisUpdate.evaluation` завжди з погляду білих. Реалізація `EngineService` отримує `ChessRules` через конструктор (щоб визначити, чия черга ходу в корені пошуку).
- `analyse`: кожен рядок MultiPV це окрема подія `Update` (поле `multiPv` = 1..N), глибина зростає. Після `Finished` чи `Failed` Flow завершується; скасування збору споживачем подій не створює.
- **Які рядки стають `Update` (v0.9):** рядок `info` з `pv` і **точною** оцінкою (`score cp N` або `score mate N`, N ≠ 0) без `lowerbound`/`upperbound`. Рядки з `lowerbound`/`upperbound` (проміжні межі пошуку, оцінка неточна) і `score mate 0` свідомо не передаються: це не втрата. Метрика «без втрат» (поріг вище): «придатні рядки, прочитані рушієм» = «`Update`, отримані споживачем»; пропущені рядки рахуються окремо за причиною (межа, `mate 0`), а лічильник «пропущено з іншої причини» має дорівнювати 0.

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
3. Ослаблення реалізується в `core-chess` поверх рушія (наприклад, вибір серед кількох кандидатів MultiPV з ймовірністю гіршого ходу, обмеження глибини/вузлів, ймовірність не помітити взяття чи мат). Конкретний алгоритм обирає Шахове ядро й документує в `core-chess/api/HANDICAP.md` (v0.9; `docs/` змінює лише Архітектор).
4. Усі ходи **легальні**. Гра має виглядати як помилки людини-початківця, а не як випадкові ходи: найслабший рівень іноді пропускає незахищену фігуру й мат в 1, але не віддає фігури безглуздо на кожному ході.
5. Детермінованість для тестів: джерело випадковості `Random` передається в конструктор реалізації. З однаковим seed і однаковою позицією вибір однаковий. Шар Handicap сам стабільно сортує кандидатів (за оцінкою, за рівності за рядком UCI) перед вибором і витрачає однакову кількість викликів `Random` на хід; порядок `ChessRules.legalMoves` не гарантується.
6. Рушій на рівнях Handicap не заявляє рейтинг Elo (`approxElo = null`), поки немає калібрування.
7. Тести: легальність усіх ходів; статистика на наборі позицій (частка найкращих ходів монотонно зростає від step 1 до найслабшого рівня Elo); найслабший рівень не програє матеріал щоходу у стартових позиціях.
8. **Калібрування для MVP (v0.9).** Мета MVP не точні рейтинги (`approxElo = null`), а гарантовано монотонна шкала. Критерії приймання:
   - для кожної пари сусідніх рівнів (step k проти step k+1, найсильніший Handicap проти мінімального `UCI_Elo`) сильніший набирає **≥ 60 % очок** у матчі **≥ 100 партій** із чергуванням кольорів і різними дебютними позиціями (≥ 20 позицій, однакові для всіх пар);
   - ліміти пошуку такі самі, як у `DifficultyPresets` (без «полегшення» для калібрування);
   - звіт із таблицею матчів у `HANDICAP.md`; якщо пара не проходить поріг, параметри змінюються, а не поріг.
   Калібрувати Elo (`approxElo`) не потрібно до окремого рішення.
9. **Інструмент калібрування (v0.9).** Матчі проводяться на комп'ютері, не на телефоні. Дозволено тестовий (не в APK) клас `EngineService` поверх UCI-процесу (`ProcessBuilder`, stdin/stdout) із Stockfish, **зібраним із того самого вихідного коду `sf_19`, що в репозиторії**, звичайним `make` для хоста. Завантажені з інтернету виконувані файли не використовуються. Код інструмента лише в тестовому наборі `core-chess` або в окремому завданні Gradle, яке не входить у `build`; нові залежності лише через реєстр.

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
- **Автозбереження й відновлення (v0.10):** незавершена партія проти рушія зберігається через `GameRepository` після кожного півходу (людини й рушія), після скасування ходу й при `onStop`. Запис виконується поза головним потоком і не блокує хід. При запуску застосунку `lastUnfinished()` пропонує продовжити партію; рушій тоді отримує позицію як `startFen` + ходи UCI з PGN. Завершена партія (мат, пат, нічия, здача) позначається `finished = true` з `termination`.
- Поки немає `DifficultyPresets`, `SavedGame.difficultyId` = `null` (заглушку `DifficultyId(0)` не зберігати); відновлена партія грається фіксованим рівнем. Після появи пресетів старі записи з `null` відкриваються з рівнем за замовчуванням, міграція схеми для цього не потрібна.
- **Тимчасове відхилення від 3.5, дозволене до появи `HandicapEngineService`:** рушій у composition root ще не загорнутий у декоратор, рівень фіксований (`FixedLevel.kt`, мінімальний `UCI_Elo`, позначка `TODO(difficulty)`). Як тільки Шахове ядро передасть `HandicapEngineService` і `DifficultyPresets`, загортання обов'язкове, а `FixedLevel.kt` видаляється.
- `app` не містить шахової логіки: лише виклики `ChessRules`, `ChessGame`, `EngineService`, `PuzzleService`.
- Room (дозволено 2026-10-30, `THIRD_PARTY.md` SW-004): лише системний SQLite Android; анотації обробляє KSP (не `kapt`); схема експортується (`app/schemas/`), кожна зміна має міграцію й тест (`room-testing`). Сутності Room живуть лише в `app` і в контракти не потрапляють; назовні лише `GameRepository` і порти `learning`.
- Застосунок повністю працює без мережі; дозвіл `INTERNET` у MVP відсутній.
- **`DrawClaimable` у грі проти рушія (затверджено Архітектором 2026-10-30, v0.9, за пропозицією Android і UI):** біля рядка стану партії з'являється ненав'язлива інформаційна смуга з причиною (ключі `strings.xml` для трикратного повторення й правила 50 ходів) і кнопками «Вимагати нічию» та «Продовжити». Смуга показується лише коли хід за людиною (вимагати нічию може лише гравець, що ходить). «Вимагати нічию» завершує партію: `GameTermination.DRAW_CLAIMED`, результат 1/2-1/2 у PGN. «Продовжити» ховає смугу до наступної нової появи умови. Рушій у MVP нічию не вимагає й не пропонує. На дошці аналізу (`ANALYSIS_BOARD`) смуга не показується. Режим двох гравців на одному пристрої в MVP відсутній (MASTER_SPEC 6), тож варіант «Запропонувати нічию» для нього не реалізується.

## 6. `content` — формат даних

### 6.1 Задачі [MVP]

- Розташування: `content/common/puzzles/<pack>.json` + `content/common/manifest.json`.
- Поля задачі відповідають `Puzzle` (розділ 4.1). `startFen`, `solution` у UCI, `themes` як `ThemeId`, `rating`, `sourceId`.
- `manifest.json` містить `schemaVersion`. Незнайомий MAJOR → застосунок показує помилку й не падає.
- `startFen` зберігається в **канонічному вигляді з 6 полів**, як його повертає `Position.fen` після `parseFen` (v0.7). `parseFen` приймає й 4–5 полів, але дані контенту нормалізуються скриптом підготовки, щоб ключі й порівняння були однозначні.
- Кожна задача перевіряється скриптом підготовки (рекомендовано через `ChesslibRules`): FEN валідний, кожен хід розв'язку легальний, довжина непарна. (Чи потрібна додаткова перевірка Stockfish, вирішує Навчання і контент разом із Шаховим ядром.)
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

- Perft-тести позначені **JUnit 4 Categories** (проєкт використовує лише JUnit 4.13.2, у якому немає тегів JUnit 5): `org.chesstrainer.core.testing.Perft` (швидкі) і `org.chesstrainer.core.testing.PerftDeep` (глибокі). Gradle-задачі `:core-chess:api:perftFast` і `:core-chess:api:perftDeep`; звичайний `test` (і `build`) запускає все, крім `PerftDeep` (v0.7, затверджено Архітектором 2026-10-29). Вимірювання Шахового ядра: швидкий набір ≈ 36 с, глибокий ≈ 14 хв.
- На кожен pull request і push у `main`: збірка, unit-тести всіх JVM-модулів, швидкий perft.
- Раз на добу за розкладом і вручну: глибокий perft.
- Perft виконується на JVM (`:core-chess:api`), без емулятора й без Android.
- Нативний код Stockfish у CI перевіряється збіркою для всіх ABI зі списку (MASTER_SPEC 4). Запуск на пристрої лише вручну (MASTER_SPEC 11).
- **Не ставити фільтр `paths` у workflow, чий статус зроблено обов'язковим (Required) для `main`:** pull request, що не чіпає ці шляхи, ніколи не отримає статус перевірки й зависне в очікуванні.
- JDK у CI: 21 (Temurin). Версії дій GitHub фіксуються в `ci.yml` (на 2026-10-29: `actions/checkout@v7`, `actions/setup-java@v6`, `gradle/actions/setup-gradle@v6`); оновлюються разом із перевіркою сумісності з AGP. Перший прогін CI на GitHub пройшов (PR #1).
- Job `perft-deep` (нічний і ручний запуск) викликає `./gradlew :core-chess:api:perftDeep`. Швидкий perft входить у `./gradlew build` job `build`.
- Нативна збірка Stockfish у CI (v0.8): NDK `30.0.16248370` і CMake `4.1.2` встановлюються на раннері тими самими версіями, що в `THIRD_PARTY.md` (SW-017); мережа NNUE завантажується Gradle-задачею `fetchNnue*` з `github.com/official-stockfish/networks` з перевіркою SHA-256 і кешується між прогонами (98,5 МБ не завантажувати щоразу); у кожному прогоні перевірка вирівнювання 16 КБ (`llvm-objdump` для `LOAD`, `zipalign -c -P 16`). Без фільтра `paths`.

### 7.2 Набір ABI і тестування без емулятора x86 [MVP]

**Рішення власника 2026-10-28: набір ABI для MVP лише `arm64-v8a`** (і в релізній, і у відладковій збірці).

- `x86_64` у MVP не збирається. Тестування без x86-емулятора йде рівнями нижче.
- `armeabi-v7a` чи `x86_64` можна додати після MVP лише окремим рішенням власника (наприклад, якщо Play Console покаже помітну частку 32-бітних пристроїв; Google Play вимагає 64-бітну версію поряд із 32-бітною).
- Рівні тестування рушія:
  1. Логіка навколо рушія (Handicap, рівні, аналіз, протокол UCI): JVM-тести з `FakeEngineService`.
  2. ~~Протокол UCI зі справжнім Stockfish на JVM з нативною збіркою для хоста~~ **скасовано (v0.8):** справжній рушій перевіряється контрактним набором `EngineContractSuite` на пристрої (20 тестів, обидві реалізації 20/20), тож JNI-збірка для хоста не потрібна для MVP. Логіку розбору UCI можна тестувати на JVM без нативного коду. (Виконуваний Stockfish для хоста дозволено лише як інструмент калібрування, 3.5 п. 9.)
  - **Сумісність байткоду з API 26 (v0.9):** замість smoke-тесту на емуляторі API 26 (потребував би нового модуля без нативного коду) статична перевірка: скрипт сканування `classes*.dex` фінального APK на виклики API з `since` > `minSdk` (метод із `PROTOTYPE.md` розділ 6) комітиться й запускається в CI для кожної збірки APK; дозволені винятки (зараз одне недосяжне посилання `TypeUtils.getTypeName()` у commons-lang3) перелічено явно в скрипті. Поведінкову перевірку на Android 8 дає другий фізичний пристрій із критеріїв MVP (MASTER_SPEC 11).
  3. Справжня поведінка на Android: фізичний пристрій `arm64-v8a` через USB-налагодження (основний спосіб) або емулятор `arm64-v8a` на хості з ARM-процесором.
  4. Нативний arm64 у GitHub Actions (раннер `ubuntu-24.04-arm`) для збірки й бенчмарка arm64-версії Stockfish під звичайний Linux; перевіряє ARM-код, але не JNI й не особливості Android. Умови безкоштовного використання перевірити.
  5. Дві нативні бібліотеки (`armv8` і `armv8-dotprod`) з вибором за можливостями процесора; налагоджувальний перемикач примусово вмикає базову. Бажано мати телефон без dotprod для ручної перевірки. Обидві бібліотеки належать до ABI `arm64-v8a`, тому рішенню власника не суперечать.

### 7.3 `FakeEngineService` (testFixtures `:core-chess:api`) [MVP]

Пише Шахове ядро. Споживачі: `learning`, `app`, контрактні тести. Підключення: `testImplementation(testFixtures(project(":core-chess:api")))`.

Порядок (рішення 2026-10-29): Шахове ядро додає плагін `java-test-fixtures` у `:core-chess:api` разом із `FakeEngineService`. Після цього Android і UI перевіряє підключення в `:app` (Android-модуль) і `:learning`. До того перевірка відкладена: у каркасі ще немає плагіна, перевіряти нічого. Якщо підключення в Android-модулі не спрацює, запасний варіант: окремий JVM-модуль `:core-chess:testing` (рішення Архітектора за запитом). **Статус 2026-10-29:** плагін і `FakeEngineService` є в `main` (PR #4); черга за перевіркою Android і UI.

**`StubRules` у testFixtures не переноситься (рішення Архітектора 2026-10-29).** Він лишається в `core-chess/api/src/test`. Причини:
- це неповна реалізація `ChessRules`: більшість методів кидає виняток, а `StubPosition` порушує семантику `Position` (черга ходу завжди біла, дошка порожня). У testFixtures він став би публічним тестовим API, і тести `learning` чи `app`, що викликають `isCheckmate`, `toSan` чи `sideToMove` (наприклад, `PuzzleSession`), падали б із незрозумілою помилкою або перевіряли б неправдиву поведінку;
- справжній `ChessRules` (адаптер chesslib) і так потрібен першим кроком етапу 2. Після нього `FakeEngineService(realRules)` стає основним способом тестування для всіх модулів;
- до появи справжніх правил модулям достатньо власних локальних заглушок у `src/test` (як приклад можна взяти `StubRules`).

Якщо згодом знадобиться табличний `ChessRules` у фікстурах, Шахове ядро подає запит із повністю визначеною поведінкою всіх методів; тоді це буде окремий контракт.

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
- `start(config)`: `Stopped → Starting → Ready` атомарно (під замком, тому `Starting` ззовні не спостерігається; це виконує вимогу 3.4 щодо `Starting`); `config` обрізається за `capabilities` і записується в `appliedConfigs`. Повторний `start` у `Ready`/`Busy`/`Starting` повертає `Ok` і нічого не змінює. `capabilities` дорівнює `null` до першого успішного `start`.
- `stop()` під час пошуку: активний пошук → `Err(Cancelled)` / `Failed(Cancelled)`, стан `Stopped` (3.4).
- Будь-який прийнятий запит, зокрема з `NoLegalMoves` чи некоректною позицією, скасовує попередній пошук (3.4, «останній запит перемагає»).
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

2. Калібрування `Handicap`: п'ять кроків запропоновано, параметри уточнюються після прототипу рушія (Шахове ядро).
4. Формат зберігання бази задач і обсяг вибірки (6.1).
8. [Пізніше] Алгоритм інтервального повторення, пороги класифікації помилок, схема уроків.
16. Рідна купа рушія ≈ 235 МБ при мережі ≈ 109 МіБ і Hash 16 МБ: ≈ 100 МБ невідомого походження (обидва режими). Дослідити до етапу 4, не блокує етап 2.

Закрито у v0.4: розміщення шару Handicap (3.5), `NoLegalMoves` і `InsufficientMaterial` (3.2), набір ABI (7.2).
Закрито у v0.5: підтвердження розділу 5; конфігурація збірки (1.1).
Закрито у v0.6: поведінка `start()` у `Starting`, `stop()` під час пошуку, скасування запитами з помилкою (3.4); `StubRules` не переноситься в testFixtures (7.3).
Закрито у v0.7: остаточний вибір бібліотеки правил (chesslib 1.3.7: perft пройшов усі еталони, CI зелений; ризик пункту 11 вирішується без заміни бібліотеки); `positionKey` (реалізовано й покрито тестами, зокрема зв'язаний пішак і горизонтальна зв'язка, perft позиції 3); `EngineConfig`/`AnalysisEvent` підтвердив Android і UI; testFixtures працюють у `:app` і `:learning` без обхідних шляхів.
Закрито у v0.8: режим розгортання рушія (окремий процес, умовно, 3.4); JNI-збірка для хоста (скасовано, 7.2); вирівнювання 16 КБ (перевірено статично: `LOAD` `2**14`, `zipalign -P 16` успішно; повторювати в CI).
Закрито у v0.9: UI для `DrawClaimable` (5); умови G1–G3 і режим рушія остаточно (3.4); сумісність байткоду з API 26 (статична перевірка в CI, 7.2); бібліотека `armv8-dotprod` лишається (прискорення ×1,19 підтверджено чистим виміром); мережа NNUE для MVP копіюється у `filesDir`, варіант без копії переходить у задачі етапу 4 (MASTER_SPEC 13.2).
