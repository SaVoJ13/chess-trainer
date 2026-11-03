# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 Chess Trainer contributors
# Генерує PGN-файли для ручного сценарію M6 (tests/MANUAL.md) у теку -Out і (за -Push) кладе їх на пристрій.
# Використання: powershell -File tests/pgn/make-pgn.ps1 -Out $env:TEMP\qa-pgn [-Push]
param([string]$Out = "$env:TEMP\qa-pgn", [switch]$Push)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force $Out | Out-Null
$enc = New-Object System.Text.UTF8Encoding($false)

function NewGame($n, $white = "Білі$n", $black = "Чорні$n", $moves = "1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 4. Ba4 Nf6 5. O-O Be7 1-0") {
@"
[Event "QA game $n"]
[Site "?"]
[Date "2026.11.04"]
[Round "$n"]
[White "$white"]
[Black "$black"]
[Result "1-0"]

$moves

"@
}
function Save($name, $text) { [IO.File]::WriteAllText("$Out\$name", $text, $enc) }

# 6.1: 3 коректні партії (кирилиця в тегах — 6.9)
Save "ok3.pgn" ((1..3 | % { NewGame $_ }) -join "")
# 6.2: 1 коректна, 1 нелегальний хід, 1 синтаксична помилка
Save "mixed3.pgn" ((NewGame 1) + (NewGame 2 "A" "B" "1. e4 e5 2. Ke3 Nc6 1-0") + (NewGame 3 "A" "B" "1. e4 ((( e5 xx 1-0"))
# 6.4: рівно 500 і 501 партія
Save "games500.pgn" ((1..500 | % { NewGame $_ }) -join "")
Save "games501.pgn" ((1..501 | % { NewGame $_ }) -join "")
# 6.3: рівно 2 МБ (2 097 152 байти) і 2 МБ + 1; заповнювач — коментар у тегу Event усередині однієї партії
function Sized($target) {
  # заповнювач — коментар у рядку ходів єдиної партії
  $base = NewGame 1
  $text = $base.Replace("Be7 1-0", "Be7 {}" + " 1-0")
  $pad = $target - $enc.GetByteCount($text)
  $text.Replace("Be7 {}", "Be7 {" + ("x" * $pad) + "}")
}
Save "size_exact_2mb.pgn" (Sized 2097152)
Save "size_2mb_plus1.pgn" (Sized 2097153)
# 6.5
Save "empty.pgn" ""
Save "not_pgn.pgn" "Це не PGN. Просто текст.`n"

Get-ChildItem $Out | Select-Object Name, Length | Format-Table -AutoSize | Out-String | Write-Host
if ($Push) {
  adb shell mkdir -p /sdcard/Download/chesstrainer-qa
  Get-ChildItem $Out | ForEach-Object { adb push $_.FullName /sdcard/Download/chesstrainer-qa/ | Out-Null }
  adb shell ls -l /sdcard/Download/chesstrainer-qa
}
