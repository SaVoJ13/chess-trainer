# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 Chess Trainer contributors
#
# Збірка Stockfish sf_19 (каталог stockfish/) для Android під minSdk 26.
# Власний CMakeLists замість Makefile Stockfish: той прив'язаний до API 29 і до запуску скриптів.
# Прапорці перенесено з Makefile sf_19 (розділи arch = armv8 / armv8-dotprod, COMP = ndk).
#
# Бібліотеки:
#   stockfish_armv8          базова (NEON), працює на кожному arm64-v8a
#   stockfish_armv8_dotprod  armv8.2-a+dotprod; вантажити лише коли HWCAP містить asimddp
#   sfprobe                  крихітна бібліотека без Stockfish: getauxval(AT_HWCAP)
#
# Нативний код завжди збирається з оптимізацією Release, навіть у debug-APK.

cmake_minimum_required(VERSION 3.22)
project(chess_trainer_engine CXX)

set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -funroll-loops -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -funroll-loops -DNDEBUG")

# Лише для debug-збірки застосунку (build.gradle.kts): експортує nativeCrash() -> abort().
option(ENGINE_TEST_CRASH "Export abort() test hook" OFF)

set(SF_DIR ${CMAKE_CURRENT_SOURCE_DIR}/stockfish)
set(SF_SOURCES
    attacks.cpp benchmark.cpp bitboard.cpp evaluate.cpp misc.cpp movegen.cpp movepick.cpp
    position.cpp search.cpp thread.cpp timeman.cpp tt.cpp uci.cpp ucioption.cpp tune.cpp
    syzygy/tbprobe.cpp
    nnue/nnue_accumulator.cpp nnue/nnue_misc.cpp nnue/network.cpp
    nnue/features/half_ka_v2_hm.cpp nnue/features/full_threats.cpp nnue/features/pp_3wide.cpp
    engine.cpp score.cpp memory.cpp)
list(TRANSFORM SF_SOURCES PREPEND ${SF_DIR}/)

function(add_engine_library target dotprod)
    add_library(${target} SHARED ${SF_SOURCES} bridge/sf_bridge.cpp)
    target_include_directories(${target} PRIVATE ${SF_DIR})
    # Прапорці Stockfish: -fno-exceptions, IS_64BIT, USE_POPCNT, USE_NEON=8 (Makefile, armv8).
    # NNUE_EMBEDDING_OFF: мережа не вбудовується в .so, а читається з файлу (див. PROTOTYPE.md).
    target_compile_options(${target} PRIVATE -Wall -fno-exceptions -fvisibility=hidden)
    target_compile_definitions(${target} PRIVATE
        IS_64BIT USE_POPCNT USE_NEON=8 NNUE_EMBEDDING_OFF)
    if(ENGINE_TEST_CRASH)
        target_compile_definitions(${target} PRIVATE ENGINE_TEST_CRASH=1)
    endif()
    if(dotprod)
        target_compile_options(${target} PRIVATE -march=armv8.2-a+dotprod)
        target_compile_definitions(${target} PRIVATE USE_NEON_DOTPROD)
    endif()
    target_link_libraries(${target} PRIVATE log)
    # 16 КБ: NDK r28+ робить це типово; явно, щоб не залежати від версії.
    target_link_options(${target} PRIVATE
        -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
        -Wl,--no-undefined -Wl,--gc-sections -Wl,-s)
endfunction()

add_engine_library(stockfish_armv8 FALSE)
add_engine_library(stockfish_armv8_dotprod TRUE)

add_library(sfprobe SHARED bridge/cpu_probe.cpp)
target_link_options(sfprobe PRIVATE
    -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,--no-undefined -Wl,-s)
