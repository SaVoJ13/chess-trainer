# THIRD_PARTY — реєстр сторонніх ресурсів

Статус: **v0.6** (Архітектор, 2026-10-29). Заповнюється поступово. Ресурс без статусу «Перевірено» або «Власний» **не потрапляє в релізну збірку** і не публікується. (Сам рядок реєстру в `main` бути може: реєстр і є місцем, де ведеться перевірка.)

> Записи з позначкою «заявлена (не перевірено)» це припущення, а не підтверджені факти. Записи з позначкою «за першоджерелом» прочитав у першоджерелі вказаний агент; Архітектор їх повторно не перевіряв. Перед релізом кожну ліцензію треба підтвердити й перевести рядок у статус «Перевірено». Це не юридична консультація.

## 1. Правила

1. Будь-який зовнішній ресурс (бібліотека, шрифт, набір фігур, звук, база задач, дебютна база, текст, зображення) має **окремий рядок** у таблиці розділу 3.
2. До рядка додається: точна версія/дата завантаження, URL першоджерела, текст або посилання на ліцензію, ім'я того, хто перевірив, дата перевірки.
3. Перевірка = прочитана ліцензія + відповідь на питання «чи сумісна з розповсюдженням у складі застосунку під GPL-3.0-or-later, у тому числі в Google Play (і F-Droid, якщо планується)». Якщо відповідь неочевидна, статус «Потребує юриста/рішення власника».
4. **Заборонено без окремого рішення власника:** ліцензії з обмеженням некомерційного використання (NC), забороною змін (ND), «лише для особистого використання», невідомі/відсутні ліцензії, ресурси «з інтернету без ліцензії», вміст, скопійований з комерційних сервісів і підручників.
5. Ліцензії з вимогою зберігати умови при поширенні похідних (наприклад, SA-ліцензії) потребують окремої перевірки сумісності з GPL-3.0 (напрямок сумісності й версія ліцензії мають значення).
6. Ліцензії тільки GPL-2.0 (без «or later») потенційно несумісні з GPL-3.0; перевіряти конкретне формулювання.
7. Брендинг Lichess та інших сервісів (назви, логотипи, оригінальні матеріали) не використовується. Окремо перевіряти, чи не обмежують умови використання назву проєкту в назві/описі застосунку.
8. Усі пояснення в уроках пишуться власноруч (проєктом) або з ресурсів із перевіреною ліцензією. Тексти зі сторонніх підручників, сайтів і книг не копіюються.
9. У застосунку є екран «Про застосунок → Відкриті ліцензії» з атрибуцією всіх ресурсів, де цього вимагає ліцензія. Дані для нього беруться з цього реєстру/автогенерації (інструмент для залежностей Gradle обирає Android і UI; ліцензію самого інструмента теж записати).
10. Кожен ресурс у `content/` має у своїх метаданих ключ запису з цього реєстру (`source`).
11. Оновлення версії ресурсу = повторна перевірка рядка.
12. **Прямі** залежності Gradle мають окремий рядок у цьому реєстрі. **Транзитивні** залежності обліковуються звітом інструмента SW-006: Android і UI зберігає його в репозиторії й оновлює разом із залежностями; Архітектор переглядає звіт на відповідність правилу 4. Транзитивна залежність із ліцензією, не дозволеною правилом 4, отримує окремий рядок і рішення.
13. У колонці «Де використовується» обов'язково вказано, чи потрапляє компонент у APK (runtime), лише в збірку чи лише в тести. Сумісність із GPL-3.0-or-later потрібна для всього, що розповсюджується (APK і вихідний код у репозиторії).
14. Для компонентів під Apache-2.0, що мають файл `NOTICE`, його текст відтворюється на екрані «Відкриті ліцензії» (Apache-2.0 §4(d)). Звіт SW-006 у `offlineMode` цих текстів не містить, тому вони додаються вручну (`collect.configPath`, Android і UI). Текст NOTICE заноситься в примітку рядка реєстру.

## 2. Статуси

| Статус | Значення |
|---|---|
| Кандидат | Розглядається, ліцензію ще не читали |
| Перевіряється | Ліцензія читається, питання відкриті |
| Перевірено | Ліцензія прочитана, сумісність підтверджена, є посилання й дата |
| Потребує рішення | Неоднозначно (юрист/власник) |
| Відхилено | Не використовуємо (вказати причину) |
| Власний | Створено проєктом, ліцензія проєкту; запис потрібен для атрибуції й контролю походження |

## 3. Реєстр

### 3.1 Код і бібліотеки

| ID | Компонент | Версія | Джерело (URL) | Ліцензія (і звідки відомо) | Де використовується | Статус | Хто / коли перевірив | Примітки |
|---|---|---|---|---|---|---|---|---|
| SW-001 | Stockfish | `sf_19` (2026-09-05) | github.com/official-stockfish/Stockfish | GPLv3 «або будь-яка пізніша версія», за першоджерелом (заголовки вихідних файлів, `Copying.txt`) | `core-chess/engine-android` (NDK/JNI) | Перевіряється | Шахове ядро / 2026-10-28 | Опції за кодом `sf_19` (`search.h`, `engine.cpp`): UCI_Elo 1320–3190, Skill Level 0–20, типові Threads 1 і Hash 16. Обов'язки GPLv3: надати відповідний вихідний код, зберегти повідомлення про авторство. README Stockfish вимагає атрибуції навчальних даних Lc0 (ODbL), див. SW-008. Збірка під `minSdk 26` (2026-10-29): CMake NDK r30, API 26, лінкування з `--no-undefined` проти stub-ів API 26 пройшло, bionic-функцій вище API 26 не використано (`core-chess/engine-android/PROTOTYPE.md`). Вихідний код `sf_19` (коміт `edb0d9db6731067ec50ce619ff372b463bc4dd5d`) без змін лежить у `core-chess/engine-android/src/main/cpp/stockfish/` (`SOURCE.txt`). Модифікацій Stockfish немає; власний лише JNI-міст. Текст атрибуції для екрана ліцензій і спосіб надання вихідного коду ще не оформлено |
| SW-002 | chesslib (генератор ходів, FEN, SAN) | 1.3.7 (реліз 2026-06-30) | github.com/bhlangonijr/chesslib; розповсюдження через JitPack | Apache-2.0: `LICENSE` **на тегу `1.3.7`** («Apache License, Version 2.0»; у файлі лише шаблон рядка copyright), за першоджерелом | `core-chess/api` як `implementation` (за інтерфейсом `ChessRules`), у APK (runtime) | **Перевірено** | Шахове ядро / 2026-10-28; Архітектор / 2026-10-29 (тег `1.3.7`) | Координати Gradle `com.github.bhlangonijr:chesslib:1.3.7` (README; JitPack, у `settings.gradle.kts` лише для групи `com.github.bhlangonijr`); байткод Java 11 (`maven-compiler-plugin` source/target 11 у POM). 2026-10-29: perft пройшов на JVM (швидкий і глибокий набір, див. журнал); `:app:assembleDebug` і `:app:lintDebug` проходять, класи chesslib є в debug-APK (запуск на пристрої не виконувався). Лишилось не перевірено: запуск на Android-пристрої, сумісність Apache-2.0 з GPL-3.0-or-later (для «Перевірено»), звіт SW-006 ще не перегенеровано. Правила нічиєї пишемо самі: за читанням коду chesslib вважає K+кінь проти K+кінь нічиєю, що суперечить CONTRACTS 3.2. Сумісність Apache-2.0 з GPL-3.0-or-later (в один бік) потрібно підтвердити при переведенні у «Перевірено». Залежність: SW-007. 2026-10-29 (Архітектор): perft пройшов усі еталони chessprogramming.org; CI зелений (PR #6). Apache-2.0 включається в GPLv3-твір (односпрямовано). **Технічний ризик окремо від ліцензії:** байткод Java 11 на Android 8–10 (API 26–29) під час виконання не перевірено; див. CONTRACTS 8 |
| SW-007 | Apache Commons Lang 3 (`org.apache.commons:commons-lang3`, залежність chesslib) | 3.18.0 (за деревом Gradle `:core-chess:api:runtimeClasspath`; так само в POM chesslib 1.3.7 з JitPack) | Maven Central (`org.apache.commons:commons-lang3:3.18.0`); commons.apache.org/proper/commons-lang | Apache-2.0: `LICENSE.txt` **на тегу `rel/commons-lang-3.18.0`**, за першоджерелом. `NOTICE.txt` на тому ж тегу: «Apache Commons Lang / Copyright 2001-2025 The Apache Software Foundation / This product includes software developed at The Apache Software Foundation (https://www.apache.org/).» | транзитивно через SW-002, у APK (runtime) | **Перевірено** | Шахове ядро / 2026-10-29 (версія, POM); Архітектор / 2026-10-29 (тег) | Версію взято з `./gradlew :core-chess:api:dependencies --configuration runtimeClasspath`. **Обов'язок Apache-2.0 §4(d): текст `NOTICE.txt` відтворюється на екрані «Відкриті ліцензії»** (правило 14) |
| SW-008 | Нейромережа Stockfish (NNUE) `nn-1a298aa575a0.nnue` | для `sf_19` | github.com/official-stockfish/networks | CC0 за README репозиторію мереж; ліцензію самого файлу окремо не перевіряли | `core-chess/engine-android` (вбудована в бібліотеку або окремий файл) | Перевіряється | Шахове ядро / 2026-10-28 | Навчальні дані Lc0 під ODbL: за README Stockfish потрібна атрибуція (екран «Відкриті ліцензії»). Розмір файлу 98 511 183 байт (92,2 МіБ), SHA-256 починається з `1a298aa575a0` (збігається з іменем). Не комітиться в git: `.nnue-cache/` + завдання Gradle `fetchNnue*`; у APK як asset `nnue/` (одна копія для обох `.so`), копіюється у `filesDir` при першому запуску |
| SW-003 | AndroidX Core KTX (`androidx.core:core-ktx`) | 1.19.1 | github.com/androidx/androidx; артефакт у Google Maven | Apache-2.0 за `LICENSE.txt` репозиторію androidx (рівень репозиторію; POM артефакту не прочитано: Google Maven недоступний із середовища Архітектора) | APK (`app`) | Перевіряється | Архітектор / 2026-10-29 | Версію взяв Android і UI з Google Maven 2026-10-28. Вимагає `compileSdk 37` (за записом Android і UI). Ліцензію артефакту й транзитивних залежностей підтвердить звіт SW-006 |
| SW-004 | Room | _визначити_ | developer.android.com | не перевірено | `app` | Кандидат | — | Ще не підключено |
| SW-005 | kotlinx-coroutines-core (`org.jetbrains.kotlinx:kotlinx-coroutines-core`) | 1.11.0 | github.com/Kotlin/kotlinx.coroutines; артефакт на Maven Central | Apache-2.0: `LICENSE.txt` **на тегу `1.11.0`** («Apache License, Version 2.0», Copyright 2000-2020 JetBrains s.r.o. and Kotlin Programming Language contributors) і `<license>` у POM `kotlinx-coroutines-core-jvm-1.11.0` | `:core-chess:api` як `api` (`Flow`, `StateFlow` у публічних сигнатурах), тому транзитивно в усіх модулях і в APK (runtime) | **Перевірено** | Шахове ядро / 2026-10-29 (POM, `master`); Архітектор / 2026-10-29 (тег `1.11.0`) | Apache-2.0 сумісна з GPLv3, отже й з GPL-3.0-or-later проєкту (односпрямовано: код Apache-2.0 можна включати в GPLv3-твір). Атрибуція на екрані «Відкриті ліцензії» (через звіт SW-006). Прямий дозвіл власника. Технічне, не ліцензійне: сумісність з Kotlin 2.4.21 за документацією не звірено (збірка й тести проходять). Звіт SW-006 у `app` перегенеровано 2026-10-29 (Android і UI): тепер 1.11.0 |
| SW-006 | AboutLibraries (Gradle-плагін `com.mikepenz.aboutlibraries.plugin.android`; бібліотеки `aboutlibraries-core`, за потреби `aboutlibraries-compose-m3`) | 15.3.0 (реліз 13 жовтня; підтверджено Android і UI та власником 2026-10-29) | github.com/mikepenz/AboutLibraries | Apache-2.0: `LICENSE` **на тегу `15.3.0`** (Copyright 2026 Mike Penz), за першоджерелом | плагін: лише збірка; `aboutlibraries-core`/UI ще не підключено (етап 2) | **Перевірено** (плагін) | Архітектор / 2026-10-29 | Підключено в режимі `offlineMode = true`, `fetchRemoteLicense` і `fetchRemoteFunding` вимкнено; відсутність мережевих звернень Android і UI перевірив непрямо (запуск із неробочим проксі дав ідентичний звіт). Плагін бачить лише залежності Gradle: Stockfish, NNUE, задачі, фігури, звуки додаються на екран ліцензій вручну з цього реєстру. У `offlineMode` звіт не містить текстів ліцензій: на етапі 2 тексти через `collect.configPath`. Бібліотеки `aboutlibraries-core`/`-compose-m3` при підключенні отримують окремий запис або рядок у звіті |
| SW-009 | AndroidX Activity Compose (`androidx.activity:activity-compose`) | 1.13.0 | github.com/androidx/androidx | Apache-2.0 за `LICENSE.txt` репозиторію androidx (рівень репозиторію) | APK (`app`) | Перевіряється | Архітектор / 2026-10-29 | Як SW-003 |
| SW-010 | Jetpack Compose BOM (`androidx.compose:compose-bom`): `compose.ui:ui`, `compose.material3:material3` | BOM 2026.09.00 (ui 1.12.1, material3 1.4.0 за записом Android і UI) | github.com/androidx/androidx | Apache-2.0 за `LICENSE.txt` репозиторію androidx (рівень репозиторію) | APK (`app`) | Перевіряється | Архітектор / 2026-10-29 | Як SW-003. Compose runtime 1.12.1 вимагає `compileSdk 37` (за записом Android і UI) |
| SW-011 | Kotlin: стандартна бібліотека, Gradle-плагіни (`kotlin.jvm`, `plugin.compose`), компілятор | 2.4.21 | github.com/JetBrains/kotlin | Apache-2.0 за `license/LICENSE.txt` гілки `master`, за першоджерелом | APK (stdlib), збірка (плагіни) | Перевіряється | Архітектор / 2026-10-29 | Ліцензію тегу `v2.4.21` і POM `kotlin-stdlib` не звіряли. AGP 9 має вбудований Kotlin для Android-модулів (за записом Android і UI) |
| SW-012 | JUnit 4 (`junit:junit`) | 4.13.2 | github.com/junit-team/junit4 | **EPL-1.0** за `LICENSE-junit.txt` гілки `main`, за першоджерелом | **лише тести** (`testImplementation`), у APK не потрапляє | Перевіряється | Архітектор / 2026-10-29 | EPL-1.0 FSF вважає несумісною з GPL, тому JUnit **ніколи не підключається** як `implementation`/`api` і не розповсюджується разом із застосунком. Для використання в тестах без розповсюдження сумісність не потрібна. Тег `r4.13.2` не звіряли. Транзитивна залежність: SW-013 |
| SW-013 | Hamcrest Core (транзитивно від JUnit 4) | 1.3 | Maven Central (`org.hamcrest:hamcrest-core:1.3`, батьківський `hamcrest-parent:1.3`) | New BSD License за `<licenses>` батьківського POM (у POM самого артефакту ліцензії немає), за записом Android і UI | лише тести (`debugUnitTestRuntimeClasspath`), у APK не потрапляє | Перевіряється | Android і UI / 2026-10-29 | Лише тестова; правило як для SW-012 |
| SW-014 | Gradle Build Tool і Gradle Wrapper (`gradle/wrapper/gradle-wrapper.jar` **лежить у репозиторії**) | 9.6.0 | github.com/gradle/gradle | Apache-2.0 за `LICENSE` гілки `master`, за першоджерелом | збірка; wrapper-jar розповсюджується у вихідному коді проєкту | Перевіряється | Архітектор / 2026-10-29 | Ліцензію тегу `v9.6.0` не звіряли. Контрольну суму wrapper-jar перевіряти за офіційним списком Gradle (окреме завдання для Android і UI). Оновлення до новіших версій лише після перевірки сумісності з Kotlin |
| SW-015 | Android Gradle Plugin (`com.android.application`, `com.android.library`) | 9.5.0 | Google Maven; вихідний код android.googlesource.com | Apache-2.0 («The Apache Software License, Version 2.0») за `<licenses>` POM `com.android.tools.build:gradle:9.5.0`, за записом Android і UI (кеш Gradle) | лише збірка, у APK не потрапляє | Перевіряється | Android і UI / 2026-10-29 | Транзитивні залежності самого AGP не переглядались (лише збірка) |
| SW-016 | Android SDK Platform 37 / 36 (компіляція), build-tools | compileSdk 37, targetSdk 36 | developer.android.com/studio | Android SDK License Agreement (developer.android.com/studio/terms), за першоджерелом | лише збірка | Перевіряється | Архітектор / 2026-10-29 | Угода дозволяє використовувати SDK для розробки застосунків під Android і не дає Google прав на сам застосунок (п. 3.1, 4.1); забороняє розповсюджувати сам SDK (п. 3.4). SDK у репозиторій не кладеться. Не юридична консультація |
| SW-017 | Android NDK і CMake (для збірки Stockfish) | NDK r30 (`30.0.16248370`, за `source.properties`); CMake `4.1.2` (SDK Manager, пакет `cmake;4.1.2`, з Ninja 1.12.1) | developer.android.com/ndk; локальна установка власника (`%ANDROID_HOME%\ndk`, `\cmake`) | NDK: Android SDK License Agreement (`package.xml` пакета); компоненти під відкритими ліцензіями керуються власними ліцензіями (п. 3.5 угоди), див. `NOTICE` і `NOTICE.toolchain` пакета (Apache-2.0; у `NOTICE.toolchain` також GPLv2 для частин тулчейна). CMake: BSD-3-Clause (Kitware) за `doc/cmake-4.1/LICENSE.rst` пакета. Ліцензію Ninja (бінарник у пакеті CMake) окремо **не читали, потребує перевірки** | лише збірка (`core-chess/engine-android`); у APK потрапляє лише статично злита `libc++` (SW-018) | Перевіряється | Шахове ядро / 2026-10-29 | `ndkVersion = "30.0.16248370"` зафіксовано в Gradle. `c++_static`. Вирівнювання 16 КБ: результат перевірки в `PROTOTYPE.md`. Перед «Перевірено»: ліцензія Ninja |
| SW-018 | `libc++` / `libc++abi` з NDK r30 (статично в `.so` рушія) | з NDK `30.0.16248370` | `toolchains/llvm/prebuilt/*/NOTICE` у NDK | Apache License v2.0 with LLVM Exceptions, за початком і розділом «LLVM Exceptions» файлу `NOTICE` тулчейна NDK r30 (рядки 2 і 208). У тому ж файлі частини під University of Illinois/NCSA (compiler-rt; у `.so` не лінкується) | `core-chess/engine-android` (runtime, статично в обох `.so`) | Перевіряється | Шахове ядро / 2026-10-29 | LLVM Exception знімає вимогу відтворювати ліцензію для виконуваного коду, зібраного з libc++; текст усе одно варто показати на екрані «Відкриті ліцензії» (Android і UI). Потребує підтвердження перед «Перевірено» |

### 3.2 Дані й контент

| ID | Ресурс | Версія/дата | Джерело (URL) | Ліцензія (і звідки відомо) | Де використовується | Статус | Хто / коли перевірив | Примітки |
|---|---|---|---|---|---|---|---|---|
| CT-001 | База тактичних задач (кандидат: відкрита база Lichess) | _дата вибірки_ | _додати першоджерело_ | очікується CC0, **підтвердити на сторінці бази** | `content/common/puzzles` | Кандидат | — | Береться підмножина за темами/рейтингом. Лише дані, без брендингу |
| CT-002 | Відкрита база дебютів (кандидат: репозиторій chess-openings у Lichess) | _дата_ | _додати першоджерело_ | очікується CC0, **підтвердити** | `content/common/openings` | Кандидат | — | Назви дебютів і ходи; коментарі пишемо самі |
| CT-003 | Тексти уроків і пояснень | — | власні (проєкт) | GPL-3.0-or-later (рішення власника, 2026-10-28) | `content/uk/`, `content/en/` | Власний | Власник / 2026-10-28 | Кожен файл має SPDX-заголовок `GPL-3.0-or-later` (MASTER_SPEC 10). Сторонні тексти сюди не додаються |
| CT-004 | Партії для розбору (історичні) | — | _додати_ | не перевірено | `content/…` | Кандидат | — | Записи ходів і коментарі можуть мати різний статус; коментарі сторонніх авторів не беремо |

### 3.3 Візуальні та звукові ресурси

| ID | Ресурс | Версія/дата | Джерело (URL) | Ліцензія (і звідки відомо) | Де використовується | Статус | Хто / коли перевірив | Примітки |
|---|---|---|---|---|---|---|---|---|
| AS-001 | Набір фігур №1 (кандидат, відкритий набір) | — | _додати_ | не перевірено | `app` | Кандидат | — | Кожен набір має свою ліцензію, одна перевірка на набір, а не «на джерело» |
| AS-002 | Набір фігур №2 (запасний) | — | _додати_ | не перевірено | `app` | Кандидат | — | — |
| AS-003 | Звукові ефекти ходів (кандидат) | — | _додати_ | не перевірено | `app` | Кандидат | — | Кожен файл окремо; уникати NC |
| AS-004 | Шрифти | — | _додати_ | не перевірено | `app` | Кандидат | — | Перевага системному шрифту Android (відсутність додаткових ліцензій); якщо власний, перевірити підтримку кирилиці |
| AS-005 | Іконка застосунку, ілюстрації | — | власні або _додати_ | — | `app` | Кандидат | — | Не копіювати чужі логотипи |
| AS-006 | Голос (TTS) | — | системний Android TTS | не застосовується (ресурс пристрою) | `app` | Кандидат | — | Перевірити доступність української мови на пристроях і режим без завантаження голосів |

## 4. Що потрібно для переведення в «Перевірено»

- SW-001: успішна збірка `sf_19` під `minSdk 26`; текст атрибуції Stockfish для екрана «Відкриті ліцензії»; спосіб надання вихідного коду (посилання на репозиторій проєкту з точною версією Stockfish).
- SW-008: ліцензія самого файлу мережі; текст атрибуції Lc0 (ODbL); розмір.
- SW-003, SW-009, SW-010, SW-011: ліцензії конкретних артефактів (POM) і транзитивних залежностей зі звіту SW-006.
- SW-012, SW-014: ліцензія на тегах відповідних версій; для SW-012 перевірка, що JUnit лише в тестових конфігураціях.
- SW-013, SW-015: звірити ліцензію на тегах версій (POM прочитано). SW-017, SW-018: ліцензії NDK r30 і CMake 4.1.2 прочитано (2026-10-29); лишається ліцензія Ninja й підтвердження.
- Інші рядки: вибір кандидатів, переведення зі статусу «Кандидат» у «Перевіряється».

Ліцензія проєкту вирішена (власник, 2026-10-28): GPL-3.0-or-later для коду й власних текстів. GPLv3-or-later у Stockfish і Apache-2.0 у chesslib за записом Шахового ядра цьому вибору не перешкоджають (підтвердити при переведенні в «Перевірено»).

## 5. Журнал перевірок

| Дата | ID | Хто | Результат | Посилання на докази (URL/скриншот/файл) |
|---|---|---|---|---|
| 2026-10-28 | SW-001 | Шахове ядро | GPLv3-or-later за заголовками й `Copying.txt` версії `sf_19`; опції UCI за `search.h`, `engine.cpp` | github.com/official-stockfish/Stockfish (тег `sf_19`) |
| 2026-10-28 | SW-002 | Шахове ядро | Apache-2.0 за `LICENSE`; perft не запускався | github.com/bhlangonijr/chesslib (1.3.7) |
| 2026-10-28 | SW-007 | Шахове ядро | Apache-2.0 (залежність chesslib); версію не зафіксовано | — |
| 2026-10-28 | SW-008 | Шахове ядро | CC0 за README репозиторію мереж; файл окремо не перевірено; Lc0 ODbL за README Stockfish | github.com/official-stockfish/networks |
| 2026-10-29 | SW-003, SW-009, SW-010 | Архітектор | Apache-2.0 за `LICENSE.txt` репозиторію (не POM артефакту) | github.com/androidx/androidx (гілка `androidx-main`) |
| 2026-10-29 | SW-006 | Архітектор | Apache-2.0 за `LICENSE`; README описує `offlineMode`, `fetchRemoteLicense`, `fetchRemoteFunding`; релізи: останній 15.2.0 (розбіжність із 15.3.0) | github.com/mikepenz/AboutLibraries |
| 2026-10-29 | SW-017, SW-018 | Шахове ядро | NDK r30 (`30.0.16248370`), CMake 4.1.2; `libc++`: Apache-2.0 WITH LLVM-exception за `NOTICE` тулчейна; CMake: BSD-3-Clause за `LICENSE.rst`; Ninja не перевірено | `ndk\30.0.16248370\toolchains\llvm\prebuilt\windows-x86_64\NOTICE`, `cmake\4.1.2\doc\cmake-4.1\LICENSE.rst` (локально) |
| 2026-10-29 | SW-006 (альтернатива) | Архітектор | gradle-license-plugin: Apache-2.0 за `LICENSE` гілки `master`; версію 0.9.91 не звіряли | github.com/jaredsburrows/gradle-license-plugin |
| 2026-10-29 | SW-011 | Архітектор | Apache-2.0 за `license/LICENSE.txt` | github.com/JetBrains/kotlin (гілка `master`) |
| 2026-10-29 | SW-012 | Архітектор | EPL-1.0 за `LICENSE-junit.txt` | github.com/junit-team/junit4 (гілка `main`) |
| 2026-10-29 | SW-014 | Архітектор | Apache-2.0 за `LICENSE` | github.com/gradle/gradle (гілка `master`) |
| 2026-10-29 | SW-016 | Архітектор | Умови Android SDK License Agreement прочитано (п. 3.1, 3.4, 3.5, 4.1) | developer.android.com/studio/terms |
| 2026-10-29 | SW-005 | Шахове ядро | Apache-2.0 за `LICENSE.txt` (`master`) і за `<license>` у POM 1.11.0 (Maven Central); версія 1.11.0 за сторінкою релізів | github.com/Kotlin/kotlinx.coroutines; repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.11.0/ |
| 2026-10-29 | SW-015 | Архітектор | Не вдалося: Google Maven (dl.google.com) недоступний із середовища | — |
| 2026-10-29 | SW-015, SW-003, SW-009, SW-010, SW-012, SW-013 | Android і UI | Ліцензії за `<licenses>` POM із кешу Gradle (див. розділ 6) | кеш Gradle `modules-2` |
| 2026-10-29 | SW-005 | Архітектор | Apache-2.0 за `LICENSE.txt` на тегу `1.11.0` | raw.githubusercontent.com/Kotlin/kotlinx.coroutines/1.11.0/LICENSE.txt |
| 2026-10-29 | SW-002, SW-007 | Шахове ядро | Координати й версію chesslib 1.3.7 звірено з README і POM (JitPack); commons-lang3 3.18.0 з дерева Gradle; ліцензії повторно не читали | github.com/bhlangonijr/chesslib; `~/.gradle/caches/modules-2` (POM) |
| 2026-10-29 | SW-006 | Архітектор | Apache-2.0 за `LICENSE` на тегу `15.3.0`; версію 15.3.0 підтверджено (власник, Android і UI) | raw.githubusercontent.com/mikepenz/AboutLibraries/15.3.0/LICENSE |
| 2026-10-29 | SW-002 | Архітектор | Apache-2.0 за `LICENSE` на тегу `1.3.7`; perft і CI зелені (PR #6) | raw.githubusercontent.com/bhlangonijr/chesslib/1.3.7/LICENSE |
| 2026-10-29 | SW-007 | Архітектор | Apache-2.0 за `LICENSE.txt` на тегу `rel/commons-lang-3.18.0`; прочитано `NOTICE.txt` | raw.githubusercontent.com/apache/commons-lang/rel/commons-lang-3.18.0/LICENSE.txt, …/NOTICE.txt |

## 6. Нотатка Android і UI для Архітектора (2026-10-29; Архітектор переніс дані в розділ 3 того ж дня)

Ліцензії прочитано в `<licenses>` POM із кешу Gradle (`~/.gradle/caches/modules-2`) після `./gradlew build`; дерево взято з `./gradlew :app:dependencies`. Статус «Перевірено» не ставився. Це не юридична консультація.

| Компонент | Версія | Ліцензія в POM | Де | Ідеться про |
|---|---|---|---|---|
| AGP `com.android.tools.build:gradle` | 9.5.0 | The Apache Software License, Version 2.0 | збірка | SW-015 |
| `androidx.core:core-ktx` | 1.19.1 | The Apache Software License, Version 2.0 | APK | SW-003 |
| `androidx.activity:activity-compose` | 1.13.0 | The Apache Software License, Version 2.0 | APK | SW-009 |
| `androidx.compose:compose-bom` | 2026.09.00 | The Apache Software License, Version 2.0 | APK | SW-010 |
| `junit:junit` | 4.13.2 | Eclipse Public License 1.0 | лише тести | SW-012 |
| `org.hamcrest:hamcrest-core` (від JUnit, `debugUnitTestRuntimeClasspath`) | 1.3 | у самому POM немає; у батьківському `hamcrest-parent:1.3`: New BSD License | лише тести | SW-013 |
| Плагін `com.mikepenz.aboutlibraries.plugin.android` | 15.3.0 | у POM маркера плагіна `<licenses>` немає; ліцензія репозиторію Apache-2.0 (SW-006) | збірка | SW-006 |

- Звіт SW-006: `app/licenses/aboutlibraries.json` (`releaseRuntimeClasspath`, 65 бібліотек, у POM усіх 65 лише `Apache-2.0`). Транзитивні: androidx.* (lifecycle, savedstate, collection тощо), `kotlin-stdlib` 2.4.21, `kotlinx-coroutines` 1.9.0, `kotlinx-serialization` 1.7.3, `org.jetbrains:annotations` 23.0.0, `org.jspecify:jspecify` 1.0.0, `com.google.guava:listenablefuture` 1.0 (ліцензія в звіті Apache-2.0). Тестових залежностей у звіті немає (hamcrest лише з таблиці вище).
- `:core-chess:api` поки без runtime-залежностей, окрім stdlib.
- Не охоплено: транзитивні залежності самого AGP і Kotlin-плагінів (збірка, у APK не потрапляють); ліцензії на тегах версій не звірялися.
- У режимі `offlineMode` текст ліцензії у звіті відсутній (Gradle виводить «does not contain the license text»); для екрана ліцензій на етапі 2 потрібні тексти вручну через `collect.configPath`.
- Версія AboutLibraries: **15.3.0** (сторінка релізів github.com/mikepenz/AboutLibraries/releases 2026-10-29: 15.3.0 позначено Latest, 13 жовтня; 15.2.0 попередня, 28 серпня). Розбіжність у записі Архітектора пояснюється застарілою сторінкою.
- Контрольна сума wrapper: SHA-256 `gradle-wrapper.jar` у репозиторії `497c8c2a…a194a9c7` збігається зі списком gradle.org/release-checksums для 9.6.0. Додано `distributionSha256Sum` для `gradle-9.6.0-bin.zip`: `bbaeb2fe…2a529a01` (список gradle.org і `services.gradle.org/distributions/gradle-9.6.0-bin.zip.sha256` однакові).
