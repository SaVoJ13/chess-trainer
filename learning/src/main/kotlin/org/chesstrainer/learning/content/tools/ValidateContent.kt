// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.learning.content.tools

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.system.exitProcess
import org.chesstrainer.core.rules.ChesslibRules
import org.chesstrainer.learning.content.ContentValidator
import org.chesstrainer.learning.content.SourceRegistry

private fun usageValidate() = """
Перевірка готового контенту (для CI; код виходу 0 = усе гаразд, 1 = є помилки, 2 = помилка запуску):
  ./gradlew :learning:validateContent
  ./gradlew :learning:validateContent --args="--content content/common --registry docs/THIRD_PARTY.md --require-verified"

  --content <каталог>   каталог content/common (типово content/common)
  --registry <файл>     docs/THIRD_PARTY.md: sourceId має бути в реєстрі
  --require-verified    sourceId зі статусом лише «Перевірено» або «Власний»
""".trimIndent()

/** Точка входу `:learning:validateContent`. */
object ValidateContentMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val a = try { parseArgs(args) } catch (e: IllegalArgumentException) { System.err.println(e.message); System.err.println(usageValidate()); exitProcess(2) }
        if ("help" in a) { println(usageValidate()); return }
        val content = Paths.get(a["content"] ?: "content/common")
        val registry = a["registry"]?.let { SourceRegistry.parse(String(Files.readAllBytes(Paths.get(it)), StandardCharsets.UTF_8)) }
        if (!Files.isDirectory(content)) { System.err.println("Каталог не знайдено: $content"); exitProcess(2) }
        val report = ContentValidator(ChesslibRules(), registry, requireVerifiedSources = "require-verified" in a).validateDirectory(content)
        report.puzzleCounts.forEach { (id, n) -> println("pack $id: $n puzzles") }
        if (report.ok) { println("OK"); return }
        report.findings.take(200).forEach { println("ПОМИЛКА ${it.where}: ${it.message}") }
        if (report.findings.size > 200) println("… ще ${report.findings.size - 200} помилок")
        println("Знайдено помилок: ${report.findings.size}")
        exitProcess(1)
    }
}
