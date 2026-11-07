// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.licenses

import org.chesstrainer.learning.content.ContentFormatException
import org.chesstrainer.learning.content.Json

/**
 * Читання даних екрана ліцензій. Звіт `aboutlibraries.json` генерує плагін (SW-006), `components.json` ведеться
 * за `docs/THIRD_PARTY.md`. Нового JSON-розбирача не додаємо: беремо наявний без залежностей з `learning`.
 * Помилка формату це [ContentFormatException]; екран тоді показує повідомлення, а не падає.
 */
object LicenseParsing {

    fun parseReport(json: String): LibraryReport {
        val root = obj(Json.parse(json), "звіт")
        val licenses = obj(root["licenses"] ?: emptyMap<String, Any?>(), "licenses").mapValues { (id, value) ->
            val o = obj(value, "licenses.$id")
            LicenseInfo(id = id, name = str(o["name"]).ifBlank { id }, url = strOrNull(o["url"]))
        }
        val libraries = list(root["libraries"], "libraries").mapIndexed { i, value ->
            val o = obj(value, "libraries[$i]")
            val uniqueId = str(o["uniqueId"]).also { if (it.isBlank()) fail("libraries[$i] без uniqueId") }
            LibraryEntry(
                uniqueId = uniqueId,
                name = str(o["name"]).ifBlank { uniqueId },
                version = str(o["artifactVersion"]),
                developers = list(o["developers"], "developers").mapNotNull { strOrNull(obj(it, "developer")["name"]) },
                licenseIds = list(o["licenses"], "licenses").map { str(it) }.filter { it.isNotBlank() },
            )
        }
        return LibraryReport(libraries, licenses)
    }

    fun parseComponents(json: String): ComponentCatalog {
        val root = obj(Json.parse(json), "components")
        val app = obj(root["app"], "app")
        return ComponentCatalog(
            app = AppLicenseInfo(
                license = str(app["license"]),
                copyright = str(app["copyright"]),
                source = str(app["source"]),
            ),
            native = components(root["native"], "native"),
            content = components(root["content"], "content"),
            pieceSets = components(root["pieceSets"], "pieceSets"),
            notices = obj(root["notices"] ?: emptyMap<String, Any?>(), "notices").mapValues { str(it.value) },
        )
    }

    private fun components(value: Any?, where: String): List<ComponentEntry> =
        list(value, where).mapIndexed { i, item ->
            val o = obj(item, "$where[$i]")
            ComponentEntry(
                id = str(o["id"]).also { if (it.isBlank()) fail("$where[$i] без id") },
                name = str(o["name"]),
                version = str(o["version"]),
                authors = str(o["authors"]),
                license = str(o["license"]),
                texts = list(o["texts"], "texts").map { str(it) },
                source = str(o["source"]),
                links = list(o["links"], "links").map { str(it) },
                noteIds = list(o["notes"], "notes").map { str(it) },
                converted = o["converted"] == true,
            )
        }

    @Suppress("UNCHECKED_CAST")
    private fun obj(value: Any?, where: String): Map<String, Any?> =
        value as? Map<String, Any?> ?: fail("$where: очікувався об'єкт")

    private fun list(value: Any?, where: String): List<Any?> = when (value) {
        null -> emptyList()
        is List<*> -> value
        else -> fail("$where: очікувався масив")
    }

    private fun str(value: Any?): String = (value as? String).orEmpty()

    private fun strOrNull(value: Any?): String? = (value as? String)?.takeIf { it.isNotBlank() }

    private fun fail(message: String): Nothing = throw ContentFormatException(message)
}
