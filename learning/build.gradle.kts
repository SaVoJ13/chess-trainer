// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

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
    implementation(project(":core-chess:api"))
    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":core-chess:api")))
}

// Інструменти підготовки контенту (запускаються лише на ПК розробника, у застосунок не входять).
// Шляхи в --args відносні до кореня репозиторію. Опис: content/README.md.
fun registerContentTool(name: String, description: String, main: String) =
    tasks.register<JavaExec>(name) {
        this.description = description
        group = "content"
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set(main)
        workingDir = rootProject.projectDir
    }

registerContentTool(
    "buildPuzzlePack",
    "Будує пакет задач із дампа Lichess (CSV) у content/common",
    "org.chesstrainer.learning.content.tools.BuildPuzzlePackMain",
)
registerContentTool(
    "validateContent",
    "Перевіряє content/common (manifest.json, puzzles/*.json) через ChesslibRules",
    "org.chesstrainer.learning.content.tools.ValidateContentMain",
).configure { args("--registry", "docs/THIRD_PARTY.md") }
