// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
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
    api(libs.kotlinx.coroutines.core)
    implementation(libs.chesslib)
    testImplementation(libs.junit)
}

// Perft (CONTRACTS 7.1): JUnit 4 Categories. `test` (і `build`) запускає все, крім глибокого perft.
val perftDeepCategory = "org.chesstrainer.core.testing.PerftDeep"
val perftCategory = "org.chesstrainer.core.testing.Perft"

tasks.test {
    useJUnit { excludeCategories(perftDeepCategory) }
}

fun registerCategoryTest(name: String, description: String, category: String, heapMb: Int = 1024) =
    tasks.register<Test>(name) {
        this.description = description
        group = "verification"
        val testSet = sourceSets.test.get()
        testClassesDirs = testSet.output.classesDirs
        classpath = testSet.runtimeClasspath
        maxHeapSize = "${heapMb}m"
        useJUnit { includeCategories(category) }
        testLogging { events("passed", "failed"); showStandardStreams = true }
    }

registerCategoryTest("perftFast", "Швидкий perft (Category Perft)", perftCategory)
registerCategoryTest("perftDeep", "Глибокий perft (Category PerftDeep)", perftDeepCategory)
