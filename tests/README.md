<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# tests/ — теку веде роль QA

QA не виправляє код інших ролей, лише пише тести, ручні сценарії й звіти про дефекти (`docs/MASTER_SPEC.md`, розд. 8).

| Файл / тека | Що це |
|---|---|
| `PLAN.md` | План: що покрито автоматично, прогалини, пріоритети, регресійний набір, тест-кейси на слабкі рівні, реєстр perft |
| `MANUAL.md` | Покрокові сценарії на пристрої (частина через adb, частина для власника) |
| `DEFECTS.md` | Дефекти, блокери, спостереження + шаблон запису |
| `contract/` | Контрактні тести `EngineService` (JVM, JUnit 4). Потребують Gradle-модуля `:tests:contract` (запит у `docs/project_log.md`) |
| `run-device.ps1` | Пристроєві автоматичні перевірки: `connectedDebugAndroidTest`, `contract`, `hang`, `crash`, `soak 240` |
| `pgn/make-pgn.ps1` | Генерує PGN-файли для сценарію імпорту (межі 2 МБ, 500 партій, пошкоджені партії) |
| `results/RESULTS-*.md` | Зведення прогонів. Повні логи `results/*.log` лишаються локально (`*.log` у `.gitignore`) |

## Як запускати

```
./gradlew test --max-workers=2                         # усі JVM-тести (пам'ять ПК обмежена)
./gradlew :core-chess:api:perftFast                    # швидкий perft
./gradlew :core-chess:api:perftDeep                    # глибокий perft, ≈ 14 хв
./gradlew :tests:contract:test                         # після підключення модуля до settings.gradle.kts
./gradlew -I <init.gradle.kts> :tests:contract:test    # до того: init-скрипт з рядком
                                                       #   gradle.settingsEvaluated { include(":tests:contract") }
powershell -File tests/run-device.ps1                  # пристрій (SM-S928B, RZCX326349A), потрібен USB
powershell -File tests/pgn/make-pgn.ps1 -Push          # PGN-файли на пристрій для MANUAL M6
```

`platform-tools` (adb) у `PATH`. Gradle-файли в корені QA не змінює; потрібні зміни йдуть запитом до Android і UI.

## Як додати контрактний тест

Новий сценарій додається в `EngineServiceContractTest` з ідентифікатором `C-NN` і рядком у таблиці `PLAN.md`, розд. 3. Нова реалізація `EngineService` підключається класом-спадкоємцем із власним `EngineHarness` (див. `FakeEngineContractTest.kt`).
