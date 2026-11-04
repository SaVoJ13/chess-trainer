# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 Chess Trainer contributors
#
# Пристроєві автоматичні перевірки QA (tests/PLAN.md, розд. 4). Потрібен підключений arm64-телефон з USB-налагодженням.
# Використання (з кореня репозиторію):
#   powershell -File tests/run-device.ps1 [-Serial RZCX326349A] [-Soak 240] [-SkipGradle] [-SkipSoak] [-DryRun]
# -DryRun: лише перевірка переліку сценаріїв і режимів проти коду, без adb і Gradle.
# Усі виклики am/pm/force-stop йдуть з --user 0 (на телефоні кілька профілів).
# connectedDebugAndroidTest видаляє застосунок наприкінці, тому installDebug виконується ще раз.
# Результати: tests/results/device-*.log. Скрипт нічого не видаляє на пристрої, окрім очищення logcat.
param(
    [string]$Serial = "RZCX326349A",
    [int]$Soak = 240,
    [switch]$SkipGradle,
    [switch]$SkipSoak,
    [switch]$DryRun
)
$ErrorActionPreference = 'Continue'
$env:ANDROID_SERIAL = $Serial
$repo = Split-Path -Parent $PSScriptRoot
$out = Join-Path $PSScriptRoot "results"
New-Item -ItemType Directory -Force $out | Out-Null
$pkg = "org.chesstrainer.app.debug"
$activity = "$pkg/org.chesstrainer.engine.debug.PrototypeActivity"
$userId = 0

# Сценарії прогону: (назва, режим, повтори, таймаут с). Режими: inproc | remote (PROTOTYPE.md, розд. 9).
$plan = @(
    ,@("contract", "remote", 1, 300)
    ,@("contract", "inproc", 1, 300)
    ,@("hang", "remote", 1, 180)
    ,@("hang", "inproc", 1, 180)
    ,@("crash", "remote", 1, 120)
)
if (-not $SkipSoak) { $plan += ,@("soak", "remote", $Soak, 3600) }  # CONTRACTS 3.4, умова G1

# Звірка переліку сценаріїв і режимів із кодом PrototypeActivity / PrototypeScenarios.
function Test-Plan {
    $src = Join-Path $repo "core-chess\engine-android\src\debug\kotlin\org\chesstrainer\engine\debug"
    $act = Get-Content -Raw -Encoding utf8 (Join-Path $src "PrototypeActivity.kt")
    $m = [regex]::Match($act, 'SCENARIOS\s*=\s*listOf\((.*?)\)', 'Singleline')
    if (-not $m.Success) { Write-Host "Не знайдено SCENARIOS у PrototypeActivity.kt"; return $false }
    $known = [regex]::Matches($m.Groups[1].Value, '"(\w+)"') | ForEach-Object { $_.Groups[1].Value }
    $sc = Get-Content -Raw -Encoding utf8 (Join-Path $src "PrototypeScenarios.kt")
    $ok = $true
    foreach ($p in $plan) {
        if ($known -notcontains $p[0]) { Write-Host "Сценарію '$($p[0])' немає в PrototypeActivity.SCENARIOS"; $ok = $false }
        if ($sc -notmatch ('"' + $p[0] + '"\s*(,|->)')) { Write-Host "Сценарій '$($p[0])' не оброблено в PrototypeScenarios.run"; $ok = $false }
        if (@("inproc", "remote") -notcontains $p[1]) { Write-Host "Недійсний режим '$($p[1])'"; $ok = $false }
    }
    if ($sc -notmatch '"inproc"\s*\|\s*"remote"') { Write-Host "Режими в PrototypeScenarios змінились, перевір скрипт"; $ok = $false }
    return $ok
}

if ($DryRun) {
    $ok = Test-Plan
    foreach ($p in $plan) { Write-Host ("  {0} / {1} / repeat={2} / timeout={3}s" -f $p[0], $p[1], $p[2], $p[3]) }
    if ($ok) { Write-Host "Сухий прогін: перелік сценаріїв відповідає коду."; exit 0 }
    Write-Host "Сухий прогін: ПОМИЛКА."; exit 1
}
if (-not (Test-Plan)) { Write-Host "Перелік сценаріїв не відповідає коду, зупинка."; exit 3 }

$devices = (adb devices) -join "`n"
if ($devices -notmatch "$Serial\s+device") { Write-Host "Пристрій $Serial не підключений:`n$devices"; exit 2 }
"commit: $(git rev-parse --short HEAD)  branch: $(git rev-parse --abbrev-ref HEAD)" | Tee-Object "$out\device-env.log"
"model: $(adb shell getprop ro.product.model)  android: $(adb shell getprop ro.build.version.release)  sdk: $(adb shell getprop ro.build.version.sdk)" | Tee-Object -Append "$out\device-env.log"

function Test-Installed {
    $r = (adb shell pm list packages --user $userId $pkg) -join "`n"
    return ($r -match ("package:" + [regex]::Escape($pkg) + "\s*(\r?\n|$)"))
}

Push-Location $repo
try {
    if (-not $SkipGradle) {
        ./gradlew :app:installDebug --max-workers=2 --console=plain *> "$out\device-install.log"
        "installDebug exit=$LASTEXITCODE" | Tee-Object -Append "$out\device-env.log"
        ./gradlew :app:connectedDebugAndroidTest --max-workers=2 --console=plain *> "$out\device-connected-test.log"
        "connectedDebugAndroidTest exit=$LASTEXITCODE" | Tee-Object -Append "$out\device-env.log"
        # connectedDebugAndroidTest видаляє застосунок наприкінці: ставимо знову.
        ./gradlew :app:installDebug --max-workers=2 --console=plain *> "$out\device-reinstall.log"
        "installDebug (після connected) exit=$LASTEXITCODE" | Tee-Object -Append "$out\device-env.log"
    }
} finally { Pop-Location }

if (-not (Test-Installed)) { Write-Host "Застосунок $pkg не встановлений для користувача $userId, зупинка."; exit 4 }
"user: $userId  installed: yes" | Tee-Object -Append "$out\device-env.log"

# Сценарій PrototypeActivity: чекаємо рядок "END scenario=<name>" у logcat (тег EnginePrototype).
# Фільтр лише EnginePrototype:I: для одного тега діє останній рівень у списку, тому перелік :I :W :E залишав тільки :E.
function Invoke-Scenario([string]$name, [string]$mode, [int]$repeat, [int]$timeoutSec) {
    $log = "$out\device-$name-$mode.log"
    $summary = "$out\device-summary.log"
    adb logcat -c
    adb shell am force-stop --user $userId $pkg | Out-Null
    $startOut = (adb shell "am start --user $userId -n $activity --es scenario $name --es mode $mode --ei repeat $repeat") -join "`n"
    if ($startOut -match "Error|Exception|does not exist|not started") {
        "[$name/$mode] finished=False (am start не вдався: $startOut)" | Tee-Object -Append $summary
        $startOut | Set-Content -Encoding utf8 $log
        return $false
    }
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    $finished = $false; $text = ""; $gone = 0; $reason = "timeout"
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 5
        $text = (adb logcat -d -s EnginePrototype:I) -join "`n"
        if ($text -match "END scenario=$name") { $finished = $true; $reason = "ok"; break }
        # Процес застосунку зник без END: далі чекати немає сенсу (дві перевірки поспіль).
        $appPid = ((adb shell pidof $pkg) -join "").Trim()
        if ($appPid) { $gone = 0 } else { $gone++ }
        if ($gone -ge 2) { $reason = "процес застосунку завершився без END"; break }
    }
    $text | Set-Content -Encoding utf8 $log
    $results = ($text -split "`n") | Where-Object { $_ -match "RESULT scenario=|FAILED scenario=|SOAK_STALL|SOAK_ANOMALY|SOAK_UNFINISHED" }
    "[$name/$mode] finished=$finished ($reason)" | Tee-Object -Append $summary
    $results | Tee-Object -Append $summary
    return $finished
}

"=== $(Get-Date -Format s) ===" | Out-File -Append "$out\device-summary.log"
foreach ($p in $plan) { [void](Invoke-Scenario $p[0] $p[1] $p[2] $p[3]) }
Write-Host "Готово. Підсумок: $out\device-summary.log"
