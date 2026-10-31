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

// Статична перевірка API старіших за minSdk 26 у класах модуля (CONTRACTS 7.2). Не входить у `build`;
// потребує ANDROID_HOME. Перевірка APK: `java scripts/ApiCompatCheck.java --apk <apk>`.
tasks.register<Exec>("apiCompatCheck") {
    description = "Перевіряє байткод модуля на виклики API Android вище minSdk 26 (scripts/ApiCompatCheck.java)"
    group = "verification"
    dependsOn(tasks.classes)
    val javaBin = File(System.getProperty("java.home"), "bin/java").path
    val script = rootProject.file("scripts/ApiCompatCheck.java").path
    val classesDir = layout.buildDirectory.dir("classes/kotlin/main").get().asFile.path
    commandLine(javaBin, script, "--classes", classesDir)
}
