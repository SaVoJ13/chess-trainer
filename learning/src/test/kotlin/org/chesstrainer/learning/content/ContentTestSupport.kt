// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.learning.content

import org.chesstrainer.core.rules.ChesslibRules

internal object ContentTestSupport {
    val rules = ChesslibRules()

    const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    /** Придатна задача: перший хід білих з початкової позиції. */
    fun valid(id: String = "t-1") = PuzzleRecord(id, START_FEN, listOf("e2e4"), listOf("fork"), 1000)

    fun manifestFor(vararg packs: PackEntry, version: String = "1.0") =
        ContentCodec.encodeManifest(Manifest(version, packs.toList()))

    fun entry(id: String = "p", count: Int = 1, source: String = "CT-001") =
        PackEntry(id, PuzzleFormat.KIND_PUZZLES, emptyList(), count, listOf(source))

    fun pack(
        puzzles: List<PuzzleRecord>,
        id: String = "p",
        version: String = "1.0",
        source: String = "CT-001",
    ) = ContentCodec.encodePack(PuzzlePack(version, id, source, mapOf("seed" to "t"), puzzles))

    /** Перевіряє набір з одного пакета `p`; повертає всі знахідки. */
    fun validate(
        puzzles: List<PuzzleRecord>,
        packVersion: String = "1.0",
        manifestVersion: String = "1.0",
        registry: SourceRegistry? = null,
        requireVerified: Boolean = false,
    ): List<Finding> {
        val manifest = manifestFor(entry(count = puzzles.size), version = manifestVersion)
        val text = pack(puzzles, version = packVersion)
        return ContentValidator(rules, registry, requireVerified).validate(manifest) { if (it == "p") text else null }.findings
    }
}
