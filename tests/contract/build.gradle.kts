// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

// ПРОПОЗИЦІЯ QA. Модуль підключає Android і UI рядком `include(":tests:contract")` у settings.gradle.kts
// (див. запит у docs/project_log.md). Чистий JVM, без Android; лише наявні залежності (JUnit 4.13.2, testFixtures api).

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(project(":core-chess:api"))
    testImplementation(testFixtures(project(":core-chess:api")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
