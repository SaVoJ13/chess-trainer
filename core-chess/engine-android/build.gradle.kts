// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
}

// Мережа NNUE за замовчуванням для sf_19 (`EvalFileDefaultName` в evaluate.h), SW-008.
// Ім'я файлу = перші 12 hex-цифр його SHA-256; Stockfish і fishtest спираються на це правило.
val nnueFileName = "nn-1a298aa575a0.nnue"
val nnueUrl = "https://github.com/official-stockfish/networks/raw/master/$nnueFileName"

/**
 * Завантажує файл мережі (лише дані, без виконання) в кеш репозиторію `.nnue-cache/` і перевіряє
 * SHA-256 за іменем. Файл не комітиться (98 МБ), у збірку потрапляє як asset `nnue/<ім'я>`.
 */
abstract class FetchNnueTask : DefaultTask() {
    @get:Input
    abstract val fileName: Property<String>

    @get:Input
    abstract val url: Property<String>

    @get:Internal
    abstract val cacheDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    private fun sha12(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(12)
    }

    private fun valid(file: File) =
        file.isFile && "nn-${sha12(file)}.nnue" == fileName.get()

    @TaskAction
    fun fetch() {
        val cached = cacheDir.get().asFile.resolve(fileName.get())
        if (!valid(cached)) {
            cached.parentFile.mkdirs()
            logger.lifecycle("Downloading ${fileName.get()} (~98 MB) from ${url.get()}")
            URI(url.get()).toURL().openStream().use { input ->
                cached.outputStream().use { input.copyTo(it) }
            }
            check(valid(cached)) {
                cached.delete()
                "SHA-256 of the downloaded network does not match ${fileName.get()}"
            }
        }
        val target = outputDir.get().asFile.resolve("nnue/${fileName.get()}")
        target.parentFile.mkdirs()
        if (!target.isFile || target.length() != cached.length()) cached.copyTo(target, overwrite = true)
    }
}

android {
    namespace = "org.chesstrainer.engine"
    compileSdk = 37

    // Версії NDK і CMake зафіксовано; ліцензії в docs/THIRD_PARTY.md (SW-017, SW-018).
    ndkVersion = "30.0.16248370"

    defaultConfig {
        minSdk = 26
        // MVP: лише arm64-v8a (MASTER_SPEC 4, CONTRACTS 7.2)
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    buildTypes {
        debug {
            // Тестовий abort() у нативному коді існує лише в debug (CONTRACTS 3.4, критерій «ізоляція збою»).
            externalNativeBuild {
                cmake {
                    arguments += "-DENGINE_TEST_CRASH=ON"
                }
            }
        }
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants { variant ->
        val fetch = tasks.register<FetchNnueTask>("fetchNnue${variant.name.replaceFirstChar { it.uppercase() }}") {
            fileName.set(nnueFileName)
            url.set(nnueUrl)
            cacheDir.set(rootProject.layout.projectDirectory.dir(".nnue-cache"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(fetch, FetchNnueTask::outputDir)
    }
}

dependencies {
    api(project(":core-chess:api"))
}
