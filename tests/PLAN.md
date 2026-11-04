<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# План тестування (QA, сесія 1, 2026-11-04)

База: коміт `9e09133` (`main`), `docs/CONTRACTS.md` v0.20, гілка `feature/qa-plan`.
Оновлення сесії 3 (2026-11-04, коміт `4a9d850`, гілка `feature/qa-ladder-run`): див. `results/RESULTS-2026-11-04-ladder.md`; рядки з позначкою «сесія 3» нижче новіші за решту.
Принцип звітності: у таблицях «Результат» стоїть лише те, що QA бачила у виводі команди в цій сесії. Решта позначена «н/в» (не виконано) або «з документів» (так записано в `docs/`, QA не перевіряла).

## 1. Результати прогонів у цій сесії

| Перевірка | Команда | Результат | Лог |
|---|---|---|---|
| Unit-тести всіх модулів | `./gradlew test --max-workers=2` | BUILD SUCCESSFUL (1 хв 39 с). `:app:testDebugUnitTest` 167 тестів, `:learning:test` 83, 0 збоїв, 0 пропусків. Завдання `:core-chess:api:test` у цьому прогоні було UP-TO-DATE, тому його результат перевірено окремо наступним рядком | `results/gradle-test.log` (локально) |
| `:core-chess:api:test` примусово (`--rerun`) | `./gradlew :core-chess:api:test --rerun` | BUILD SUCCESSFUL; 191 тест (разом зі швидким perft), 0 збоїв, 0 пропусків | `results/gradle-api-test-rerun.log` (локально) |
| Швидкий perft | `./gradlew :core-chess:api:perftFast --rerun` | BUILD SUCCESSFUL; 6 тестів, 0 збоїв | `results/gradle-api-rerun.log` (локально) |
| Глибокий perft (`perftDeep`) | не запускався (≈ 14 хв, за завданням «без deep») | н/в. Файл результатів у `build/` датований 2026-10-29: це не результат цієї сесії | |
| Контрактні тести `tests/contract` (нові, JVM) | `./gradlew -I <init-скрипт з include(":tests:contract")> :tests:contract:test` | BUILD SUCCESSFUL; `FakeEngineContractTest` 22 (1 пропуск за задумом), `HandicapOverFakeContractTest` 22 (1 пропуск за задумом), 0 збоїв. «Мутаційна» перевірка: рушій, що не скасовує пошук при запиті з `NoLegalMoves`, провалив C-12 | `results/contract-jvm.log` (локально) |
| `:app:connectedDebugAndroidTest` на SM-S928B | `./gradlew :app:connectedDebugAndroidTest` | **не виконано**: `DeviceException: No connected devices!`. Телефон `RZCX326349A` був у `adb devices` на початку сесії, потім зник з USB (ні `adb`, ні PnP Windows його не бачать), `adb kill-server`/`start-server` не допомогли | `results/connected-android-test.log` (локально) |
| Пристроєві сценарії рушія (contract, hang, crash, soak 240) | `tests/run-device.ps1` | **не виконано** (той самий блокер). Скрипт перевірено лише на синтаксис | |
| Ручні сценарії на пристрої | `tests/MANUAL.md` | **не виконано** (той самий блокер, плюс частина вимагає дій власника) | |

Що це значить: автоматичні JVM-перевірки зелені; **жодна перевірка на пристрої в цій сесії не виконана**. Критерії MVP з розділу 11 (стабільність на двох пристроях, 200+ циклів, Android 8) цією сесією не підтверджені.

## 2. Що вже покрито автоматично (за модулями)

Кількість `@Test` у файлах (з `grep`); для параметризованих класів фактичних запусків більше (`EngineSemanticsTest`: 8 методів × 3 параметри = 24).

### `:core-chess:api` (191 запуск у `test` разом зі швидким perft)
| Область | Файл | Що перевіряє |
|---|---|---|
| Правила | `ChesslibRulesTest` (39) | легальні ходи, рокіровка, взяття на проході, перетворення, шах/мат/пат, `positionKey`, недостатній матеріал (K+кінь проти K+кінь), SAN |
| Perft | `PerftTest` (6, Category `Perft`), `PerftDeepTest` (6, `PerftDeep`) | реєстр еталонів, розд. 6 |
| Партія | `ChessGameTest` (29) | статуси нічиєї (3/5-кратне повторення, 50/75 ходів), пріоритети статусів (мат на 100-му й 150-му півході), `undo`, `positionAt`, PGN туди й назад |
| PGN | `PgnCodecTest` (22) + `resources/pgn/sample*.pgn` | круговий тест, золотий файл, помилки |
| Модель | `ModelTest` (6) | типи, передумови |
| Рушій (Fake) | `FakeEngineServiceTest` (21) | специфікація 7.3 |
| Семантика 3.4 | `EngineSemanticsTest` (8 × 3 параметри) | JVM-версія контрактних тестів для Fake, Handicap+Full, Handicap+Handicap (попередник `tests/contract`) |
| Handicap | `HandicapEngineServiceTest` (17), `HandicapPolicyTest` (16) | легальність, детермінованість за seed, фіксована кількість викликів `Random`, кандидати = усі ходи, спад B300, скасування |
| Рівні | `DifficultyPresetsTest` (11) | стабільність `DifficultyId`, порядок, межі `capabilities`, Handicap приховується при малому `maxMultiPv` |
| Калібрування | `calibration/*` (інструмент, не тести) | `./gradlew` завдання калібрування не входять у `test` |

### `:learning` (83)
`ContentLoaderTest` (14), `ContentValidatorTest` (27), `JsonTest` (4), `LichessImportTest` (7), `PuzzleServiceTest` (11), `PuzzleSessionTest` (19), `TestFixturesWiringTest` (1).

### `:app` (JVM, 167)
`GameControllerTest` (51), `AutosaveTest` (31), `PuzzleControllerTest` (22), `PgnTransferTest` (12), `DifficultyResolverTest` (12), `RoomGameRepositoryTest` (8), `ReviewControllerTest` (7), `PackedContentSourceTest` (7), `GameSchemaTest` (6), `RoomPuzzleProgressRepositoryTest` (5), `PuzzleStringsTest` (4), `SmokeTest`, `TestFixturesWiringTest`.

### `:app` (інструментовані, пристрій; 4 тести, у цій сесії н/в)
`GameMigrationTest` (2), `PuzzleProgressDaoTest` (2). За записом у `project_log.md`: «тест міграції 4/4 на телефоні».

### `:core-chess:engine-android`
JVM: `UciEngineServiceTimeoutTest` (9 тестів, `FakeUciTransport`, сесія 3: 9/0 збоїв), див. розд. 3. Також debug-набір на пристрої: `EngineContractSuite` (20 сценаріїв), `WatchdogScenarios` (`hang`, `soak`), `PrototypeScenarios` (`crash`, `stop`, ...), запуск через `PrototypeActivity` (adb). За записами у `docs/`: контрактні 20/20 в обох режимах, soak 240+240 remote і 240 in-process, `hang` проходить. QA повторила на пристрої в сесії 3: contract 20/20 remote й inproc, hang 3/3 в обох режимах, crash, soak 240 remote без аномалій (`results/RESULTS-2026-11-04-ladder.md`).

### CI (`.github/workflows/ci.yml`, з файлу; запуск на GitHub QA не бачила)
`./gradlew build` (unit + швидкий perft), `validateContent`, звіт ліцензій, `assembleDebug`, `assembleDebugAndroidTest` (лише компіляція), `ApiCompatCheck`, нічний `perftDeep`. **У CI не запускаються:** інструментовані тести, контрактні сценарії на справжньому рушії, soak, `tests/contract`.

## 3. Контрактні тести `EngineService` (`tests/contract/`)

Реалізовано в цій сесії (`tests/contract/src/test/kotlin/org/chesstrainer/contract/`): абстрактний `EngineServiceContractTest` (22 тести C-01…C-22) і два підкласи: `FakeEngineContractTest`, `HandicapOverFakeContractTest`. Усі пошуки обмежено таймаутом 20 с, тож зависання стає провалом.

Для запуску потрібен модуль Gradle `:tests:contract` (запит Android і UI в `project_log.md`). Файл `tests/contract/build.gradle.kts` готовий. До включення в `settings.gradle.kts` тести запускаються лише з init-скриптом (`gradle.settingsEvaluated { include(":tests:contract") }`, `./gradlew -I <скрипт> :tests:contract:test`).

| ID | Правило | Запит Архітектора / розділ | Джерело в `EngineContractSuite` |
|---|---|---|---|
| C-01 | `capabilities == null` до першого `start()` | 3.4 | capabilities are null before first start |
| C-02 | повторний `start()` у `Ready` це `Ok` | 3.4 | repeated start in Ready |
| C-03 | паралельні `start()` дають однаковий результат (стан `Starting`) | v0.6 | concurrent start waits |
| C-04 | запити до `start()` це `EngineUnavailable`, рушій не запускається | 3.4 | requests before start |
| C-05 | `stop()` → `start()` дає робочий рушій | 3.4 | stop then start again |
| C-06, C-07 | `bestMove` легальний, враховує `moves` | 3.4 | bestMove returns a legal move; replays moves |
| C-08 | мат і пат: `NoLegalMoves`, стан `Ready` | v0.6 | checkmate / stalemate |
| C-09 | `InvalidFen`/`IllegalMove` для `bestMove` **і** `analyse`, стан `Ready` | v0.6 | invalid FEN and illegal move |
| C-10 | `analyse`: MultiPV 1..3, закінчується `Finished` | 3.4 | analyse emits Updates |
| C-11 | новий `bestMove` скасовує `analyse` (`Failed(Cancelled)`) | запит Архітектора | new request cancels running analyse |
| C-12…C-14 | запит, що сам завершується `NoLegalMoves`/`InvalidFen`/`IllegalMove` (для `bestMove` і `analyse`), усе одно скасовує попередній | запит Архітектора, v0.6 | a failing request still cancels |
| C-15 | `bestMove` скасовує `bestMove` (`Err(Cancelled)`) | 3.4 | — (немає) |
| C-16 | `stop()` під час пошуку: `Cancelled` + `Stopped` | запит Архітектора | stop() during search |
| C-17 | скасування збору `Flow` звільняє рушій без `Failed` | 3.4 | collector cancellation |
| C-18 | `newGame()` | 3.4 | newGame keeps the engine usable |
| C-19 | падіння: `Failed(EngineCrashed)`, повторний `start()` працює (лише коли реалізація вміє `crash`) | 3.4, MASTER_SPEC 11 | — (є окремий сценарій `crash`) |
| C-20 | `SearchLimit()` без ліміту → `IllegalArgumentException` | 3.4 | — |
| C-21 | сира реалізація: `Strength.Handicap` → `IllegalArgumentException` і пошук не скасовується | запит Архітектора, 3.5 | Strength.Handicap is a contract violation |
| C-22 | декоратор: легальні ходи Handicap 1..5, `analyse` проходить без змін | 3.5 | — |

**Лишається тільки на пристрої** (потрібен справжній Stockfish; сценарії `EngineContractSuite` залишаються в debug-наборі, запуск `tests/run-device.ps1`): оцінка з погляду білих при ході чорних; знак `Mate`; `Strength.Elo`/`SkillLevel` дають легальні ходи; реальні `capabilities` (`eloRange`, `skillLevelRange`, `maxMultiPv ≥ 3`).

**Не покрито, потрібне рішення ролей (запити в журналі):**
1. Запуск тих самих C-01…C-22 на справжньому рушії (remote і in-process). Варіанти: `androidTest` у `:core-chess:engine-android` (власник Шахове ядро/Android і UI) або виклик `EngineContractSuite` з `run-device.ps1`, як зараз. Для варіанта `androidTest` `EngineHarness` вже готовий (достатньо реалізації для `RemoteEngineService`).
2. **Закрито (сесія 3).** Тести таймаутів і сторожа (`startMs` 15 с, `searchGraceMs` 5 с, `livenessMs` 10 с, `stopMs` 5 с, `closeWaitMs` 5 с) на JVM написало Шахове ядро: `core-chess/engine-android/src/test/…/UciEngineServiceTimeoutTest.kt` (9 тестів, `FakeUciTransport`, короткі `EngineTimeouts`; усі зелені). Звірка QA з переліком сценаріїв: немає `uciok` за `startMs` (`noUciokWithinStartTimeoutFailsStartAndAllowsRestart`, `openFailureGivesEngineUnavailableAndAllowsRestart`); немає `bestmove` за `moveTimeMs + searchGraceMs` (`noBestmoveWithinMoveTimePlusGraceFailsWithUnavailableNotCrashed`); тиша на `isready` (`silenceOnLivenessProbeFailsWithoutAnySearch`, `longSilentSearchIsNotAHangWhenEngineAnswersIsready`); `stop` без підтвердження → `Failed(EngineUnavailable)`, не `EngineCrashed` (`stopWithoutAcknowledgementCancelsSearchAndFailsWithUnavailableNotCrashed`, `cancelledSearchWithAcknowledgedStopReturnsToReady`); повторний `start()` після `Failed` (`assertRestartWorks` у перших тестах, плюс `crashWithoutStopGivesEngineCrashedAndAllowsRestart`, `requestsOnFailedEngineAreRejectedWithoutHang`). Зайвих дублів немає: пристроєвий `hang` лишається, бо міряє справжній процес, транспорт і реальні межі часу (analyse ≤ 10 с, bestMove ≤ 5,8 с, start ≤ 15 с), яких JVM-тест з коротшими таймаутами не перевіряє.

## 4. Регресійний набір перед релізом (пристрій)

Виконується `tests/run-device.ps1` (потрібен USB-пристрій; результат у `tests/results/device-summary.log`). Обов'язковий перед кожним релізом і після змін транспорту/`RemoteEngineService`:

| Крок | Критерій успіху (з CONTRACTS 3.4 / MASTER_SPEC 11) |
|---|---|
| `contract` remote і in-process | 20/20 |
| `hang` | analyse ≤ 10 с, bestMove ≤ 5,8 с, start ≤ 15 с до `Failed(EngineUnavailable)`, повторний `start()` працює |
| `crash` | застосунок живий, `Failed(EngineCrashed)`, `start()` → `Ready` |
| `soak` 240 циклів remote | `failed_starts=0`, `unfinished=0`, без `SOAK_STALL`; (за змоги без USB-живлення: запускати, відключивши кабель, `nowait`/`logcat` після повернення) |
| `connectedDebugAndroidTest` | усі інструментовані тести зелені |
| JVM: `./gradlew test`, `:core-chess:api:perftFast`, `:tests:contract:test` | зелені |
| Нічний `perftDeep` у CI | зелений у день релізу |

Другий пристрій (MASTER_SPEC 11): ті самі кроки; для слабкого пристрою додатково частка ходів `UCI_Elo`, що впираються в стелю часу (ціль ≤ 10 %, CONTRACTS 3.5 п. 8.2). Пристрою досі немає (MASTER_SPEC 13.2).

## 5. Тест-кейси на слабкі рівні

### 5.1 Handicap (CONTRACTS 3.5)
Автоматично є: легальність (`HandicapEngineServiceTest`), детермінованість за seed, фіксований розхід `Random`, кандидати = усі легальні ходи, на синтетичних наборах B300 монотонно спадає від кроку 1 до 5 (`HandicapPolicyTest`), `DifficultyId` стабільні (`DifficultyPresetsTest`).

Прогалини (пріоритет у розд. 6):
| ID | Кейс | Як перевіряти |
|---|---|---|
| H-01 | Метрики B300/B100/M1/H на справжньому рушії строго спадають 1→5, M1 кроку 1 у 30–70 %, серії з 5 ходів поспіль | **сесія 3: збіг (з документа `HANDICAP.md` 4.1; `tune` QA не повторювала).** B300 27,5 / 20,6 / 18,3 / 14,8 / 12,6 % у цілях v0.20; B100, M1, H строго спадають; M1 кроку 1 49,7 %; серії з 5 — 0,6 % |
| H-02 | Матчі: сусідні видимі рівні 60–80 % очок (≥ 100 партій) | **сесія 3: 8 з 9 пар у вікні; відхилення: UCI_Elo 1500 проти 1320 = 81,5 % (межово, ДІ 74,0–89,0), рішення Архітектора (`HANDICAP.md` 9.5).** Деталі в `results/RESULTS-2026-11-04-ladder.md` |
| H-03 | Час ходу Handicap на телефоні ≤ 300 мс без `minReplyDelayMs` | вимір на пристрої, н/в |
| H-04 | Handicap при `maxMultiPv` 1…4: рівні відсутні, `byId` → `null`, партія з `DifficultyId(1..5)` відкривається на найближчому рівні | є частково (`DifficultyPresetsTest`, `DifficultyResolverTest`); додати наскрізний тест `GameController` + рівні (див. 5.3) |
| H-05 | Ручна перевірка: крок 1 віддає фігури, але не кожного ходу; крок 3 і 5 за v0.20 | `MANUAL.md` M5.3 (оновлено в сесії 3); потрібен власник, перенастроювання виконано (v0.4), ручна перевірка кроків 1, 3, 5 ще не виконана |

### 5.2 UCI_Elo (рівні 10–12, 20–21, 30–31, 99 за журналом)
| ID | Кейс | Як перевіряти |
|---|---|---|
| E-01 | Усі `Strength.Elo(n)` у межах `capabilities.eloRange`, мінімум дорівнює нижній межі | `DifficultyPresetsTest` (є) |
| E-02 | Ліміт вузлів + стеля часу (v0.20 п. 8.2) замість часу | **сесія 3: збіг.** Пресети `UCI_Elo` 1320–2100 на `nodes` + стеля 3 × час, кроки Handicap на `depth` 1–3; код, тест `DifficultyPresetsTest` і `HANDICAP.md` 9.3 збігаються (`results/RESULTS-2026-11-04-ladder.md`) |
| E-03 | Приховані рівні: `levels()` не повертає прихований рівень, `byId()` → `null`, `DifficultyId` не перевикористовується | **сесія 3: частково.** Є тести для Handicap (`byId` → `null`), діапазону Elo й стабільності id; немає асерції `byId == null` для Elo-рівня поза `eloRange` (запит Шаховому ядру) |
| E-04 | Опції `UCI_LimitStrength`/`UCI_Elo` справді ставляться: на пристрої послідовність 1320 < 1500 < 1700 за часткою очок у 20 партіях | **сесія 3: збіг:** 1500 проти 1320 81,5 %, 1700 проти 1500 74,0 % (100 партій) |
| E-05 | Рівні, що вимагають `eloRange == null` (рушій без UCI_Elo): `levels()` без Elo-рівнів | `DifficultyPresetsTest` (є) |

### 5.3 Недоступний рівень у збереженій партії (CONTRACTS 5, v0.12)
Покрито: `DifficultyResolverTest` (найближчий рівень групи, найслабший доступний). Прогалини:
| ID | Кейс | Як перевіряти |
|---|---|---|
| U-01 | Збережена партія з `difficultyId = 11` при `eloRange = 1600..2500` продовжується на найближчому рівні групи, гравцеві показано `game_level_unavailable` | `GameControllerTest`-подібний наскрізний тест (запит до Android і UI, їхній модуль) |
| U-02 | `difficultyId = null` (запис до v0.12) → `DifficultyId(10)` | **покрито**: `AutosaveTest` (стара партія відновлюється з рівнем 10 попри інше налаштування) і `DifficultyResolverTest.legacyGamesUseDifficulty10` |
| U-03 | Невідомий `difficultyId` (999): партія не падає | **покрито**: `DifficultyResolverTest.unknownIdFallsBackToTheWeakestAvailableLevel` |
| U-04 | На пристрої: підмінити `difficultyId` у БД через `adb run-as ... sqlite3` (якщо `sqlite3` є) на недоступний і відкрити «Продовжити» | н/в; виконується разом з M5.7 після відновлення пристрою |

## 6. Реєстр perft-еталонів (CONTRACTS 7)

Значення збігаються з `PerftTest` і `PerftDeepTest` (звірено в сесії 3 рядок за рядком) і, за записом Шахового ядра (сесія 10, `docs/project_log.md`), з chessprogramming.org/Perft_Results: усі значення вірні. QA першоджерела не відкривала. Позначка «потребує перевірки» знята.

| Позиція | Швидкий набір (`Perft`) | Глибокий (`PerftDeep`) |
|---|---|---|
| Стартова | d1..d5: 20, 400, 8 902, 197 281, 4 865 609 (збігається з CONTRACTS 7) | d6: 119 060 324 |
| Kiwipete | d1..d4: 48, 2 039, 97 862, 4 085 603 (d3 = 97 862 збігається з CONTRACTS 7) | d5: 193 690 690 |
| Позиція 3 | d1..d6: 14, 191, 2 812, 43 238, 674 624, 11 030 083 | d7: 178 633 661 |
| Позиція 4 | d1..d5: 6, 264, 9 467, 422 333, 15 833 292 | d6: 706 045 033 |
| Позиція 5 | d1..d4: 44, 1 486, 62 379, 2 103 487 | d5: 89 941 194 |
| Позиція 6 | d1..d4: 46, 2 079, 89 890, 3 894 594 | d5: 164 075 551 |

Прогалини: тести рівня `perft` перевіряють лише суму вузлів. Немає окремих «divide»-кейсів на відомі проблемні позиції (взяття на проході з відкриттям шаху по горизонталі, перетворення з шахом, рокіровка під боєм поля, яке перетинає тура), крім позиції 3. Пропозиція: додати 6–8 таких позицій з відомими значеннями (джерело: `chessprogramming.org/Perft_Results`, розділ «Tricky» та позиції з Talkchess), після звірки Шахового ядра. Станом на сесію 3 Шахове ядро таблицю звірило, але додаткових позицій із відомими значеннями в першоджерелі не знайшло, тож розширення `PerftTest` не робилось; QA списку позицій не вигадує.

## 7. Прогалини й пріоритети

| № | Прогалина | Пріоритет | Хто | Стан |
|---|---|---|---|---|
| 1 | ~~Жоден пристроєвий прогін не виконано~~ **Закрито для SM-S928B (сесії 2 і 3, останній прогін на коміті `4a9d850`).** Лишається: другий пристрій | Блокер етапу знято | власник | `results/RESULTS-2026-11-04-ladder.md` |
| 2 | Модуль `:tests:contract` не підключено до Gradle/CI | P1 | Android і UI (запит) | чекає |
| 3 | ~~Тести таймаутів і сторожа лише на пристрої~~ **Закрито (сесія 3):** 9 JVM-тестів `UciEngineServiceTimeoutTest`; `hang` на пристрої лишається як інтеграційна перевірка | — | Шахове ядро | виконано |
| 4 | `connectedDebugAndroidTest`, `EngineContractSuite`, soak не входять у CI | P2 (пристрою в CI немає); перед релізом ручний набір розд. 4 | QA | процедура є |
| 5 | Наскрізні сценарії автозбереження «вбити процес посеред партії» лише ручні | P1 | QA/власник | `MANUAL.md` M1, M2, M4 |
| 6 | Другий пристрій і Android 8 (MASTER_SPEC 11) | P1 до MVP | власник | відкрито |
| 7 | Handicap після перенастроювання: метрики й матчі звірено (розд. 5.1, 5.2); лишаються проміжок 1320→1500 (81,5 %, рішення Архітектора), ручна перевірка власником (H-05, `MANUAL.md` M5.3), частка ходів зі стелею часу на слабшому телефоні | P1 | Архітектор, власник | частково |
| 8 | U-01 (недоступний рівень, наскрізно; U-02 і U-03 вже покриті) | P2 | Android і UI | запит |
| 9 | Розширення perft «divide»-кейсами: Шахове ядро повідомило, що додаткових складних позицій із відомими значеннями в джерелі немає, власних значень не вигадувало | P3 | Шахове ядро | відкрито, зняти, якщо не з’явиться джерело |
| 10 | UI-тести Compose відсутні (немає `androidx.compose.ui:ui-test` у залежностях) | P3: нова залежність потребує дозволу Архітектора | Архітектор | не просимо зараз |
| 11 | Відновлення збереженої партії зі збірки до PR #13 (запит Архітектора, низький пріоритет) | P3 | QA | `MANUAL.md` M8, н/в |
| 12 | PGN на межах (2 МБ, 500 партій, без часткового імпорту): JVM-тест є (`PgnTransferTest`), наскрізно з SAF лише вручну | P2 | QA/власник | `MANUAL.md` M6 + `tests/pgn/make-pgn.ps1` |

## 8. Процес

- Нові дефекти додаються в `tests/DEFECTS.md` (шаблон там же): що робила, очікуване/фактичне, коміт, серйозність, відтворюваність. Причини в коді QA не припускає без перевірки.
- Після кожного прогону пристроєвого набору `tests/results/device-summary.log` комітиться разом з записом у `project_log.md`.
- Регресійні набори (розд. 4) запускає QA перед кожною збіркою «для інших людей» (MASTER_SPEC 11) і після змін транспорту рушія.
