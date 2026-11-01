#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 Chess Trainer contributors
#
# Збирає консольний Stockfish для arm64 ТІЛЬКИ з вихідного коду sf_19 у репозиторії
# (core-chess/engine-android/src/main/cpp/stockfish) компілятором NDK (SW-017) і кладе його разом із мережею NNUE
# на телефон власника (adb, /data/local/tmp/chess-calib). Інструмент виміру Handicap (HANDICAP.md) говорить із ним
# по UCI через `adb shell`. Завантажених виконуваних файлів немає. Результат збірки в .calibration/, не комітиться.
#
# Чому не десктопний: Windows Smart App Control блокує непідписаний stockfish.exe, зібраний на ПК (запис SW-021).
#
# Прапорці перенесено з core-chess/engine-android/src/main/cpp/CMakeLists.txt (бібліотека armv8_dotprod, API 26).
# Змінні: ANDROID_SERIAL (телефон, якщо підключено кілька), SDK_DIR (типово sdk.dir із local.properties).
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
src="$root/core-chess/engine-android/src/main/cpp/stockfish"
out="$root/.calibration/stockfish-android"
net="nn-1a298aa575a0.nnue"
remote="/data/local/tmp/chess-calib"

sdk="${SDK_DIR:-$(sed -n 's/^sdk.dir=//p' "$root/local.properties" | tr '\\' '/' | sed 's#C/:#C:#; s#//*#/#g')}"
ndk="$(ls -d "$sdk"/ndk/* | sort | tail -1)"
bin="$ndk/toolchains/llvm/prebuilt/windows-x86_64/bin"
cxx="$bin/clang++.exe"
adb="$sdk/platform-tools/adb.exe"
adbs=("$adb"); [ -n "${ANDROID_SERIAL:-}" ] && adbs=("$adb" -s "$ANDROID_SERIAL")
mkdir -p "$out/obj"

if [ ! -f "$root/.nnue-cache/$net" ]; then
  echo "Немає $root/.nnue-cache/$net (її завантажує Gradle-збірка engine-android)" >&2; exit 1
fi
[ "nn-$(sha256sum "$root/.nnue-cache/$net" | cut -c1-12).nnue" = "$net" ] || { echo "SHA-256 мережі не збігається з ім'ям" >&2; exit 1; }

sources="attacks.cpp benchmark.cpp bitboard.cpp evaluate.cpp misc.cpp movegen.cpp movepick.cpp
  position.cpp search.cpp thread.cpp timeman.cpp tt.cpp uci.cpp ucioption.cpp tune.cpp
  syzygy/tbprobe.cpp nnue/nnue_accumulator.cpp nnue/nnue_misc.cpp nnue/network.cpp
  nnue/features/half_ka_v2_hm.cpp nnue/features/full_threats.cpp nnue/features/pp_3wide.cpp
  engine.cpp score.cpp memory.cpp main.cpp"
flags="--target=aarch64-linux-android26 -std=c++17 -O3 -funroll-loops -DNDEBUG -fno-exceptions -fPIE -DIS_64BIT -DUSE_POPCNT -DUSE_NEON=8 -DNNUE_EMBEDDING_OFF -march=armv8.2-a+dotprod -DUSE_NEON_DOTPROD"

compile() {
  o="$out/obj/$(echo "$1" | tr '/' '_').o"
  "$cxx" $flags -I"$src" -c "$src/$1" -o "$o"
}
export -f compile
export cxx flags src out

echo "$sources" | tr -s ' \n' '\n' | grep -v '^$' | xargs -P 6 -I{} bash -c 'compile {}'
"$cxx" --target=aarch64-linux-android26 -pie -static-libstdc++ -o "$out/stockfish" "$out"/obj/*.o
echo "Зібрано: $out/stockfish"

# adb (програма Windows) потребує віконних шляхів для локальних файлів і не повинен чіпати віддалені шляхи.
adbx() { MSYS_NO_PATHCONV=1 "${adbs[@]}" "$@"; }
adbx shell "mkdir -p $remote"
adbx push "$(cygpath -m "$out/stockfish")" "$remote/stockfish"
adbx shell "chmod 755 $remote/stockfish"
if ! adbx shell "ls $remote/$net" >/dev/null 2>&1; then
  adbx push "$(cygpath -m "$root/.nnue-cache/$net")" "$remote/$net"
fi
printf 'uci\nquit\n' | adbx shell -T "$remote/stockfish" | head -2
