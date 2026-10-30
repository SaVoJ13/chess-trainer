// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Охоронець правила «кожна зміна схеми має міграцію й тест» (CONTRACTS 5, SW-004) для JVM-збірки без пристрою.
 * Саму міграцію на SQLite виконує лише ручна перевірка на пристрої (журнал): `room-testing` потребує
 * інструментованих тестів, а `androidx.test` у MVP не підключається (SW-019).
 *
 * Тест падає, якщо версію бази піднято без експортованої схеми, без ланцюжка міграцій або якщо схему
 * вже випущеної версії змінено.
 */
class GameSchemaTest {

    private val schemaDir = File("schemas/org.chesstrainer.app.storage.GameDatabase")

    private fun versionOf(file: File): Int {
        val match = Regex("\"version\"\\s*:\\s*(\\d+)").find(file.readText())
        return requireNotNull(match) { "no version in ${file.name}" }.groupValues[1].toInt()
    }

    @Test
    fun schemaOfEveryVersionIsExportedAndCommitted() {
        assertTrue("каталог схем ${schemaDir.absolutePath} відсутній", schemaDir.isDirectory)
        for (v in 1..GameDatabase.VERSION) {
            val file = File(schemaDir, "$v.json")
            assertTrue("немає експортованої схеми версії $v", file.isFile)
            assertEquals(v, versionOf(file))
        }
    }

    @Test
    fun noSchemaIsNewerThanTheDatabaseVersion() {
        val versions = schemaDir.listFiles { f -> f.extension == "json" }!!.map { it.nameWithoutExtension.toInt() }
        assertEquals(GameDatabase.VERSION, versions.max())
    }

    @Test
    fun everyVersionStepHasAMigration() {
        val migrations = GameDatabase.MIGRATIONS
        assertEquals("міграція для кожного кроку 1…${GameDatabase.VERSION}", GameDatabase.VERSION - 1, migrations.size)
        migrations.forEachIndexed { i, migration ->
            assertEquals(i + 1, migration.startVersion)
            assertEquals(i + 2, migration.endVersion)
        }
    }

    @Test
    fun versionOneSchemaIsTheShippedGamesTable() {
        val text = File(schemaDir, "1.json").readText()
        listOf("pgn", "mode", "playerColor", "difficultyId", "finished", "termination", "createdAt", "updatedAt").forEach {
            assertTrue("у схемі v1 немає стовпця $it", text.contains("\"columnName\": \"$it\""))
        }
        assertTrue("схема v1 не має бути змінена після випуску", text.contains("\"tableName\": \"games\""))
    }
}
