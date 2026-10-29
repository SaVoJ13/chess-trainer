// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.chesstrainer.engine"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        // MVP: лише arm64-v8a (MASTER_SPEC 4, CONTRACTS 7.2)
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core-chess:api"))
}
