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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

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

    // MigrationTestHelper читає експортовані схеми з assets інструментованого тесту.
    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")
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

    // Лише інструментовані тести (SW-019, SW-023): тест міграції Room на телефоні. У APK не потрапляє.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room.testing)
}

// Room (SW-004): схема експортується з першої версії; кожна зміна схеми має міграцію й тест (CONTRACTS 5).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Навчальний контент у APK (CONTRACTS 6.1, «Пакування»): лише manifest.json і файли пакетів із content/common,
// без інструментів, дампів і параметрів перевірки. У assets вони лежать за тим самим шляхом, що й у репозиторії:
// content/common/manifest.json, content/common/puzzles/<пакет>.json.
val contentAssetsDir = layout.buildDirectory.dir("generated/contentAssets")
val prepareContentAssets by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory.dir("content/common")) {
        include("manifest.json", "puzzles/*.json")
    }
    into(contentAssetsDir.map { it.dir("content/common") })
}
android.sourceSets.getByName("main").assets.directories.add(contentAssetsDir.get().asFile.path)
// Каталог згенерований, тому його читачі (злиття assets і перевірки lint) явно залежать від копіювання.
tasks.matching { (it.name.startsWith("merge") && it.name.endsWith("Assets")) || it.name.contains("lint", ignoreCase = true) }
    .configureEach { dependsOn(prepareContentAssets) }
