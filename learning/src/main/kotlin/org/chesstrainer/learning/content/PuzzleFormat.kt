// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.learning.content

/** Версія схеми `MAJOR.MINOR` (CONTRACTS 6.1). Незнайомий MAJOR = помилка, вищий MINOR допустимий. */
data class FormatVersion(val major: Int, val minor: Int) {
    override fun toString() = "$major.$minor"

    companion object {
        fun parse(text: String): FormatVersion? {
            val m = Regex("(\\d{1,4})\\.(\\d{1,5})").matchEntire(text) ?: return null
            return FormatVersion(m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }
    }
}

object PuzzleFormat {
    val SUPPORTED = FormatVersion(1, 0)
    const val MANIFEST_FILE = "manifest.json"
    const val PUZZLES_DIR = "puzzles"
    const val KIND_PUZZLES = "PUZZLES"
    const val MIN_RATING = 100
    const val MAX_RATING = 4000
    fun packPath(packId: String) = "$PUZZLES_DIR/$packId.json"
    val PACK_ID_REGEX = Regex("[a-z0-9][a-z0-9-]{0,47}")
    val SOURCE_ID_REGEX = Regex("[A-Z]{2}-\\d{3}")
}

/** Запис пакета в `manifest.json`; поля відповідають `ContentPack` (CONTRACTS 4.2). */
data class PackEntry(
    val id: String,
    val kind: String,
    val languages: List<String>,
    val itemCount: Int,
    val sourceIds: List<String>,
)

data class Manifest(val schemaVersion: String, val packs: List<PackEntry>)

/** Файл пакета задач `puzzles/<packId>.json`. [params] лише для відтворюваності, застосунок їх не читає. */
data class PuzzlePack(
    val schemaVersion: String,
    val packId: String,
    val sourceId: String,
    val params: Map<String, String>,
    val puzzles: List<PuzzleRecord>,
)

/** Читання й детермінований запис JSON-файлів контенту. */
object ContentCodec {

    // ---------------------------------------------------------------- запис

    fun encodeManifest(m: Manifest): String {
        val sb = StringBuilder()
        sb.append("{\n  \"schemaVersion\": ").append(Json.quote(m.schemaVersion)).append(",\n  \"packs\": [")
        m.packs.forEachIndexed { i, p ->
            sb.append(if (i == 0) "\n" else ",\n")
            sb.append("    {\"id\": ").append(Json.quote(p.id))
            sb.append(", \"kind\": ").append(Json.quote(p.kind))
            sb.append(", \"languages\": ").append(strings(p.languages))
            sb.append(", \"itemCount\": ").append(p.itemCount)
            sb.append(", \"sourceIds\": ").append(strings(p.sourceIds)).append('}')
        }
        sb.append(if (m.packs.isEmpty()) "]\n}\n" else "\n  ]\n}\n")
        return sb.toString()
    }

    fun encodePack(p: PuzzlePack): String {
        val sb = StringBuilder()
        sb.append("{\n\"schemaVersion\":").append(Json.quote(p.schemaVersion))
        sb.append(",\n\"packId\":").append(Json.quote(p.packId))
        sb.append(",\n\"sourceId\":").append(Json.quote(p.sourceId))
        sb.append(",\n\"params\":{")
        p.params.toSortedMap().entries.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append(',')
            sb.append(Json.quote(k)).append(':').append(Json.quote(v))
        }
        sb.append("},\n\"puzzles\":[")
        p.puzzles.forEachIndexed { i, q ->
            sb.append(if (i == 0) "\n" else ",\n")
            sb.append("{\"id\":").append(Json.quote(q.id))
            sb.append(",\"fen\":").append(Json.quote(q.fen))
            sb.append(",\"solution\":").append(strings(q.solution, ","))
            sb.append(",\"themes\":").append(strings(q.themes, ","))
            sb.append(",\"rating\":").append(q.rating).append('}')
        }
        sb.append("\n]\n}\n")
        return sb.toString()
    }

    private fun strings(list: List<String>, sep: String = ", ") =
        list.joinToString(sep, "[", "]") { Json.quote(it) }

    // ---------------------------------------------------------------- читання

    /** Кидає [ContentFormatException] при структурній помилці (не JSON, немає поля, не той тип). */
    fun decodeManifest(text: String): Manifest {
        val root = obj(Json.parse(text), "manifest")
        val packs = list(root["packs"], "manifest.packs").mapIndexed { i, e ->
            val o = obj(e, "manifest.packs[$i]")
            PackEntry(
                id = str(o["id"], "packs[$i].id"),
                kind = str(o["kind"], "packs[$i].kind"),
                languages = list(o["languages"], "packs[$i].languages").map { str(it, "packs[$i].languages[]") },
                itemCount = int(o["itemCount"], "packs[$i].itemCount"),
                sourceIds = list(o["sourceIds"], "packs[$i].sourceIds").map { str(it, "packs[$i].sourceIds[]") },
            )
        }
        return Manifest(str(root["schemaVersion"], "manifest.schemaVersion"), packs)
    }

    /** Лише заголовок версії: щоб відхилити незнайомий MAJOR до розбору решти. */
    fun peekSchemaVersion(text: String): String = str(obj(Json.parse(text), "file")["schemaVersion"], "schemaVersion")

    fun decodePack(text: String): PuzzlePack {
        val root = obj(Json.parse(text), "pack")
        val params = root["params"]?.let { obj(it, "pack.params").mapValues { (k, v) -> str(v, "params.$k") } } ?: emptyMap()
        val puzzles = list(root["puzzles"], "pack.puzzles").mapIndexed { i, e ->
            val o = obj(e, "puzzles[$i]")
            PuzzleRecord(
                id = str(o["id"], "puzzles[$i].id"),
                fen = str(o["fen"], "puzzles[$i].fen"),
                solution = list(o["solution"], "puzzles[$i].solution").map { str(it, "puzzles[$i].solution[]") },
                themes = list(o["themes"], "puzzles[$i].themes").map { str(it, "puzzles[$i].themes[]") },
                rating = int(o["rating"], "puzzles[$i].rating"),
            )
        }
        return PuzzlePack(
            schemaVersion = str(root["schemaVersion"], "pack.schemaVersion"),
            packId = str(root["packId"], "pack.packId"),
            sourceId = str(root["sourceId"], "pack.sourceId"),
            params = params,
            puzzles = puzzles,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun obj(v: Any?, what: String): Map<String, Any?> =
        v as? Map<String, Any?> ?: throw ContentFormatException("$what: object expected")

    @Suppress("UNCHECKED_CAST")
    private fun list(v: Any?, what: String): List<Any?> =
        v as? List<Any?> ?: throw ContentFormatException("$what: array expected")

    private fun str(v: Any?, what: String): String = v as? String ?: throw ContentFormatException("$what: string expected")

    private fun int(v: Any?, what: String): Int {
        val l = v as? Long ?: throw ContentFormatException("$what: integer expected")
        if (l < Int.MIN_VALUE || l > Int.MAX_VALUE) throw ContentFormatException("$what: out of range")
        return l.toInt()
    }
}
