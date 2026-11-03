# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 Chess Trainer contributors
#
# Пристроєві автоматичні перевірки QA (tests/PLAN.md, розд. 4). Потрібен підключений arm64-телефон з USB-налагодженням.
# Використання (з кореня репозиторію):
#   powershell -File tests/run-device.ps1 [-Serial RZCX326349A] [-Soak 240] [-SkipGradle] [-SkipSoak]
# Результати: tests/results/device-*.log. Скрипт нічого не видаляє на пристрої, окрім очищення logcat.
param(
    [string]$Serial = "RZCX326349A",
    [int]$Soak = 240,
    [switch]$SkipGradle,
    [switch]$SkipSoak
)
$ErrorActionPreference = 'Continue'
$env:ANDROID_SERIAL = $Serial
$out = Join-Path $PSScriptRoot "results"
New-Item -ItemType Directory -Force $out | Out-Null
$pkg = "org.chesstrainer.app.debug"
$activity = "$pkg/org.chesstrainer.engine.debug.PrototypeActivity"

$devices = (adb devices) -join "`n"
if ($devices -notmatch "$Serial\s+device") { Write-Host "Пристрій $Serial не підключений:`n$devices"; exit 2 }
"commit: $(git rev-parse --short HEAD)  branch: $(git rev-parse --abbrev-ref HEAD)" | Tee-Object "$out\device-env.log"
"model: $(adb shell getprop ro.product.model)  android: $(adb shell getprop ro.build.version.release)  sdk: $(adb shell getprop ro.build.version.sdk)" | Tee-Object -Append "$out\device-env.log"

if (-not $SkipGradle) {
    ./gradlew :app:installDebug --max-workers=2 --console=plain *> "$out\device-install.log"
    "installDebug exit=$LASTEXITCODE" | Tee-Object -Append "$out\device-env.log"
    ./gradlew :app:connectedDebugAndroidTest --max-workers=2 --console=plain *> "$out\device-connected-test.log"
    "connectedDebugAndroidTest exit=$LASTEXITCODE" | Tee-Object -Append "$out\device-env.log"
}

# Сценарій PrototypeActivity: чекаємо рядок "END scenario=<name>" у logcat (тег EnginePrototype).
function Invoke-Scenario([string]$name, [string]$mode, [int]$repeat, [int]$timeoutSec, [string]$extra = "") {
    $log = "$out\device-$name-$mode.log"
    adb logcat -c
    adb shell am force-stop $pkg
    $cmd = "am start -n $activity --es scenario $name --es mode $mode --ei repeat $repeat $extra"
    adb shell $cmd | Out-Null
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    $finished = $false
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 5
        $text = (adb logcat -d -s EnginePrototype:I EnginePrototype:W EnginePrototype:E) -join "`n"
        if ($text -match "END scenario=$name") { $finished = $true; break }
    }
    $text | Set-Content -Encoding utf8 $log
    $results = ($text -split "`n") | Where-Object { $_ -match "RESULT scenario=|FAILED scenario=|SOAK_STALL" }
    "[$name/$mode] finished=$finished" | Tee-Object -Append "$out\device-summary.log"
    $results | Tee-Object -Append "$out\device-summary.log"
}

"=== $(Get-Date -Format s) ===" | Out-File -Append "$out\device-summary.log"
Invoke-Scenario "contract" "remote" 1 300
Invoke-Scenario "contract" "inprocess" 1 300
Invoke-Scenario "hang"     "remote" 1 180
Invoke-Scenario "hang"     "inprocess" 1 180
Invoke-Scenario "crash"    "remote" 1 120
if (-not $SkipSoak) {
    # 240 циклів start/analyse/stop у remote-режимі (CONTRACTS 3.4, умова G1; регресія перед релізом)
    Invoke-Scenario "soak" "remote" $Soak 3600
}
Write-Host "Готово. Підсумок: $out\device-summary.log"
