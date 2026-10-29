// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // chesslib (SW-002) розповсюджується лише через JitPack; дозволено тільки цю групу.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.bhlangonijr") }
        }
    }
}

rootProject.name = "chess-trainer"

include(":app")
include(":core-chess:api")
include(":core-chess:engine-android")
include(":learning")
