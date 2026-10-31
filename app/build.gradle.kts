// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.aboutlibraries.android)
    alias(libs.plugins.ksp)
}

// SW-006: лише офлайн-режим, без звернень до GitHub API (docs/THIRD_PARTY.md).
// Звіт про залежності зберігається в репозиторії й оновлюється разом із ними.
aboutLibraries {
    offlineMode = true
    collect {
        fetchRemoteLicense = false
        fetchRemoteFunding = false
    }
    export {
        outputFile = file("licenses/aboutlibraries.json")
        prettyPrint = true
    }
}

// Єдине місце, де задано applicationId (MASTER_SPEC 10). Остаточне значення визначається до етапу 5 (13.2).
val appId = "org.chesstrainer.app"

android {
    namespace = "org.chesstrainer.app"
    compileSdk = 37

    defaultConfig {
        applicationId = appId
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // MVP: лише arm64-v8a (MASTER_SPEC 4, CONTRACTS 7.2)
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":learning"))
    implementation(project(":core-chess:api"))
    implementation(project(":core-chess:engine-android"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":core-chess:api")))
}

// Room (SW-004): схема експортується з першої версії; кожна зміна схеми має міграцію й тест (CONTRACTS 5).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
