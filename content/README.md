<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# content: дані навчального контенту

Роль «Навчання і контент». Формат задач: `docs/CONTRACTS.md`, 6.1. Тут схема, теми, інструменти й обсяг.

## 1. Склад і ліцензії

| Шлях | Що | Ліцензія |
|---|---|---|
| `common/manifest.json`, `common/puzzles/*.json` | Задачі: підмножина бази Lichess (запис `CT-001` у `docs/THIRD_PARTY.md`) | **CC0-1.0** (дані Lichess); походження й параметри вибірки в полях `sourceId` і `params` кожного пакета |
| `uk/`, `en/` | Тексти уроків (Пізніше, `CT-003`) | GPL-3.0-or-later, SPDX-заголовок у кожному файлі |
| `learning/…/content` | Код інструментів лежить у `learning/` (не тут) | GPL-3.0-or-later |

JSON-файли даних не мають коментарів, тому SPDX-заголовка в них немає (MASTER_SPEC 10). Ліцензія даних задається цією таблицею й полем `sourceId`: усі задачі `li-*` це дані Lichess під CC0. Атрибуція CC0 не вимагається; подяка Lichess показується на екрані «Відкриті ліцензії» за бажанням. Назви, логотипи й матеріали Lichess не використовуються, посилання `GameUrl` з дампа не переносяться.

## 2. Схема даних, версія 1.0

Версія `schemaVersion` записується рядком `MAJOR.MINOR`. Застосунок читає файл, якщо MAJOR дорівнює 1; вищий MINOR допустимий (нові необов'язкові поля ігноруються). Незнайомий MAJOR: помилка «непідтримувана версія», застосунок не падає.

### `common/manifest.json`

| Поле | Тип | Опис |
|---|---|---|
| `schemaVersion` | рядок | `"1.0"` |
| `packs[]` | масив | відповідає `ContentPack` (CONTRACTS 4.2) |
| `packs[].id` | рядок | `[a-z0-9][a-z0-9-]*`, до 48 символів; файл пакета `puzzles/<id>.json` |
| `packs[].kind` | рядок | `PUZZLES` (`LESSONS`, `OPENINGS` пізніше) |
| `packs[].languages` | масив рядків | мови пакета; для задач `[]` (дані мовно-незалежні, назви тем у `strings.xml`) |
| `packs[].itemCount` | ціле | кількість задач у файлі (перевіряється) |
| `packs[].sourceIds` | масив рядків | id у `THIRD_PARTY.md` (зараз `["CT-001"]`) |

### `common/puzzles/<pack>.json`

| Поле | Тип | Опис |
|---|---|---|
| `schemaVersion` | рядок | `"1.0"` |
| `packId` | рядок | збігається з id у манифесті |
| `sourceId` | рядок | `Puzzle.sourceId`: один на весь пакет |
| `params` | об'єкт рядок→рядок | параметри вибірки для відтворення; застосунок їх не читає |
| `puzzles[]` | масив | по одній задачі в рядку |
| `puzzles[].id` | рядок | `PuzzleId`; для Lichess `li-<PuzzleId>`; `[A-Za-z0-9_-]{1,32}`, унікальний у всьому контенті, ніколи не використовується повторно (CONTRACTS 1) |
| `puzzles[].fen` | рядок | `startFen`: позиція, де ходить гравець; канонічні 6 полів, як `Position.fen` |
| `puzzles[].solution` | масив UCI | гравець, відповідь, гравець, …; **непарна** довжина; канонічний запис (`e7e8q`, рокіровка `e1g1`) |
| `puzzles[].themes` | масив `ThemeId` | лише з розділу 4; хоча б одна тактична тема; без повторів |
| `puzzles[].rating` | ціле | рейтинг задачі за шкалою Lichess (100..4000) |

Приклад рядка (скорочено): `{"id":"li-00sHx","fen":"… w …","solution":["a2e6","d7d8","f7f8"],"themes":["mate_in_2","middlegame"],"rating":1760}`.

Файли пишуться детерміновано: UTF-8 без BOM, завершення рядків LF, задачі впорядковані за `(rating, id)`, ключі `params` за алфавітом.

### Що перевіряє перевірка (`validateContent`)

Для кожної задачі через `ChesslibRules`: FEN коректний і канонічний; `solution` непорожній, непарної довжини, кожен хід легальний і записаний канонічно; теми з розділу 4, є тактична тема, без повторів; рейтинг у 100..4000; для `mate_in_N` довжина `solution` дорівнює 2N-1 і в кінці мат. Для файлів: версія й MAJOR, наявність файлів із манифесту, відповідність `itemCount`, `packId`, `sourceIds`, унікальність id у всьому контенті, файли пакетів, яких немає в манифесті; з `--registry` ще й `sourceId` у `THIRD_PARTY.md` (з `--require-verified` зі статусом «Перевірено» або «Власний»).

## 3. Інструменти (лише на ПК розробника)

Код: `learning/src/main/kotlin/org/chesstrainer/learning/content/`. Нових залежностей немає: власний мінімальний парсер JSON, `ChesslibRules` з `:core-chess:api`. Застосунок інструментів не містить і мережі не потребує.

### 3.1 Дамп Lichess

```
mkdir .content-cache
curl -o .content-cache/lichess_db_puzzle.csv.zst https://database.lichess.org/lichess_db_puzzle.csv.zst
```

Розпакування (одна команда, інструмент ПК, `SW-022`; підійде й `zstd -d`):

```
node -e "const fs=require('fs'),z=require('zlib'),{pipeline}=require('stream');pipeline(fs.createReadStream('.content-cache/lichess_db_puzzle.csv.zst'),z.createZstdDecompress(),fs.createWriteStream('.content-cache/lichess_db_puzzle.csv'),e=>console.log(e||'ok'))"
```

Каталог `.content-cache/` в `.gitignore`. Дамп, з якого зібрано пакети в репозиторії, описаний у `CT-001` (дата, розміри, SHA-256).

### 3.2 Збірка пакета

```
./gradlew :learning:buildPuzzlePack --args="--input .content-cache/lichess_db_puzzle.csv --pack-id tactics-sample --total 200 --seed chess-trainer-sample-1"
```

Шляхи відносні до кореня репозиторію. Нові пакети додаються в `content/common/puzzles/`, а `manifest.json` оновлюється автоматично (запис з тим самим `id` замінюється). Опис усіх параметрів: `--help`.

### 3.2.1 Пакет `tactics-mvp` (чинний, у застосунку)

Єдиний пакет у `content/common` і в APK. Команда відтворення (дамп описаний у `CT-001`):

```
./gradlew :learning:buildPuzzlePack --args="--input .content-cache/lichess_db_puzzle.csv --pack-id tactics-mvp --total 4000 --seed chess-trainer-mvp-1"
```

| Параметр | Значення |
|---|---|
| Задач | 4000 (17 тактичних тем, 5 діапазонів рейтингу; клітинка «тема × діапазон» заповнена повністю, крім `smothered_mate` у 2200+: 2 задачі, і `mate_in_1` у 2200+: 18, бо в дампі за фільтрами їх бракує) |
| Діапазони | 400–999: 1143, 1000–1399: 1143, 1400–1799: 762, 1800–2199: 571, 2200–3199: 381 (ваги 3 / 3 / 2 / 1,5 / 1) |
| Розмір | 712 473 байт JSON (LF); у стисненому вигляді (gzip -9, орієнтир для APK) ≈ 184 КБ |
| SHA-256 `puzzles/tactics-mvp.json` | `f53919f6574f2cf1e38c369ee1c0c1868cff94b06233e4bd7f3f47b840abe301` (двічі зібрано з того самого дампа, збігається) |
| SHA-256 `manifest.json` | `d5df23e33059cb450944fd5750c9b950eb5a94bca99476de03eaed8f16d97841` |

SHA-256 стосуються файлів із завершенням рядків LF (так вони лежать у git і на CI). На Windows із `core.autocrlf=true` робоча копія має CRLF, і хеш не збігається; JSON при цьому читається однаково.

Попередній приклад-пакет `tactics-sample` (200 задач) видалено з `content/common`: його id збігалися з `tactics-mvp` (id унікальні в усьому контенті), а в APK має потрапляти лише потрібне. Малі пакети для тестів будують самі тести (`ContentTestSupport`) або команда вище з `--out <каталог>`.

Перетворення рядка Lichess: перший хід `Moves` робить суперник, тому `fen` = позиція після нього (ходить гравець), `solution` = решта ходів; FEN нормалізується до 6 полів через `Position.fen`; id = `li-` + `PuzzleId`.

Відбір (усі параметри записуються в `params` пакета, тому вибірка відтворюється з того самого дампа):

1. Дешеві фільтри: рейтинг у межах `--band-edges`, `RatingDeviation` ≤ 90 (надійний рейтинг), `Popularity` ≥ 80, `NbPlays` ≥ 500, довжина `solution` ≤ 7 пів-ходів, усі теги відомі каталогу, немає «складних» тегів (розділ 4), є хоча б одна вибрана тактична тема.
2. Розподіл: кількість задач ділиться між діапазонами за `--band-weights`, у діапазоні порівну між «головними» темами (головна тема це перша зі списку розділу 4 за пріоритетом); клітинка, де задач бракує, віддає залишок іншим.
3. Вибір у клітинці: задачі з найменшим SHA-256(`seed:PuzzleId`) (детерміновано, без випадковості).
4. Кожен кандидат перевіряється правилами; невалідні відкидаються й підраховуються, на їхнє місце береться наступний за хешем.
5. Результат: `puzzles/<pack>.json` + `manifest.json`; у консоль друкується звіт (рядків прочитано, відхилено за причинами, невалідних за кодами, таблиця тема × діапазон).

`--audit` перевіряє правилами весь дамп без запису.

### 3.3 Перевірка готових файлів

```
./gradlew :learning:validateContent
./gradlew :learning:validateContent --args="--content content/common --registry docs/THIRD_PARTY.md --require-verified"
```

Код виходу: 0 усе гаразд, 1 є помилки, 2 помилка запуску. Підключення до CI у `.github/` робить Android і UI. Ту саму перевірку виконує JVM-тест `ContentValidatorTest.committedContentIsValid` у `./gradlew test`.

## 4. Теми (`ThemeId`)

Стабільні ідентифікатори (CONTRACTS 1): `snake_case`, ключ рядка `puzzle_theme_<id>` (MASTER_SPEC 10). Назви нижче лише **пропозиція**; `strings.xml` пише Android і UI. Порядок = пріоритет «головної» теми при розподілі.

### 4.1 Приймаються для MVP (20)

| ThemeId | Тег Lichess | en (пропозиція) | uk (пропозиція) |
|---|---|---|---|
| `smothered_mate` | smotheredMate | Smothered mate | Задушений мат |
| `back_rank_mate` | backRankMate | Back rank mate | Мат на останній горизонталі |
| `double_check` | doubleCheck | Double check | Подвійний шах |
| `discovered_check` | discoveredCheck | Discovered check | Відкритий шах |
| `fork` | fork | Fork (double attack) | Вилка (подвійний удар) |
| `pin` | pin | Pin | Зв'язка |
| `skewer` | skewer | Skewer | Наскрізний удар |
| `discovered_attack` | discoveredAttack | Discovered attack | Відкритий напад |
| `deflection` | deflection | Deflection | Відволікання |
| `attraction` | attraction | Attraction | Заманювання |
| `trapped_piece` | trappedPiece | Trapped piece | Захоплення в пастку |
| `hanging_piece` | hangingPiece | Hanging piece | Незахищена фігура |
| `promotion` | promotion | Promotion | Перетворення пішака |
| `mate_in_1` | mateIn1 | Mate in 1 | Мат в 1 хід |
| `mate_in_2` | mateIn2 | Mate in 2 | Мат у 2 ходи |
| `mate_in_3` | mateIn3 | Mate in 3 | Мат у 3 ходи |
| `sacrifice` | sacrifice | Sacrifice | Жертва |
| `opening` | opening | Opening | Дебют |
| `middlegame` | middlegame | Middlegame | Мітельшпіль |
| `endgame` | endgame | Endgame | Ендшпіль |

Три останні це фаза партії (корисні як фільтр, але самі по собі не тактика): задача мусить мати ще й тактичну тему. Тактичних тем 17.

### 4.2 Не переносяться, задача лишається (тег прибирається)

| Теги Lichess | Чому |
|---|---|
| `crushing`, `advantage`, `equality`, `mate` | оцінка результату, не тема; `mate` дублює `mateInN` |
| `short`, `long`, `veryLong`, `oneMove` | довжину дає `solution` |
| `master`, `masterVsMaster`, `superGM` | походження позиції |
| `kingsideAttack`, `queensideAttack`, `exposedKing`, `defensiveMove`, `advancedPawn`, `attackingF2F7` | стратегічні ярлики, не тактичний прийом |
| `pawnEndgame`, `rookEndgame`, `bishopEndgame`, `knightEndgame`, `queenEndgame`, `queenRookEndgame` | тип ендшпілю: окрема тема курсу ендшпілів (Пізніше) |
| назви матових шаблонів (`anastasiaMate`, `arabianMate`, `bodenMate`, `doubleBishopMate`, `dovetailMate`, `hookMate`, `killBoxMate`, `vukovicMate`, `swallowstailMate`, `triangleMate`, `balestraMate`, `blindSwineMate`, `cornerMate`, `epauletteMate`, `morphysMate`, `operaMate`, `pillsburysMate`) | початківцеві досить «мат у N»; шаблони стануть темами курсу (Пізніше) |

### 4.3 Відхиляються: задача не береться

| Теги Lichess | Чому занадто складно або недоречно для початківця в MVP |
|---|---|
| `capturingDefender`, `clearance`, `interference`, `xRayAttack` | непрямі прийоми: ідея невидима без розуміння попередніх |
| `quietMove`, `zugzwang`, `intermezzo` | тихі ходи й проміжні ходи не мають очевидної мети, підказка «яка тема» не допомагає |
| `underPromotion`, `enPassant`, `castling` | залежать від особливих правил; належать до уроків правил, а не тактики |
| `mateIn4`, `mateIn5` | довгі комбінації; для початківця понад 3 ходи (до того ж обмеження довжини `solution` ≤ 7) |
| `collinearMove` | значення тегу не з'ясоване за першоджерелом (потребує перевірки), тому обережно виключено |

Тег, якого немає в жодному списку, вважається **невідомим**: задача відхиляється й тег потрапляє у звіт (нові теги Lichess додаються до каталогу свідомим рішенням, `ThemeCatalog.kt`).

## 5. Обсяг і формат зберігання

Див. розділ в `docs/project_log.md` (запис «Навчання і контент, сесія 1»): вимірювання розміру, час розбору, порівняння JSON і попередньо зібраної БД та пропозиція обсягу. Чинне рішення за замовчуванням: **JSON**.
