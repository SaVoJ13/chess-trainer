// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Охоронець правила «кожна зміна схеми має міграцію й тест» (CONTRACTS 5, SW-004) для JVM-збірки без пристрою.
 * Саму міграцію на SQLite виконує інструментований тест `GameMigrationTest` (androidTest, запускається на телефоні,
 * CONTRACTS 5, v0.16); тут стережемо структуру: експорт схем, ланцюжок міграцій і збіг SQL міграції з `2.json`.
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

    @Test
    fun versionTwoAddsPuzzleAttemptsAndKeepsGames() {
        val text = File(schemaDir, "2.json").readText()
        listOf("puzzleId", "state", "wrongMoves", "hintsUsed", "timeMs", "at").forEach {
            assertTrue("у схемі v2 немає стовпця $it", text.contains("\"columnName\": \"$it\""))
        }
        // Таблиця партій у v2 не змінилась відносно v1.
        assertEquals(createSqlOf(File(schemaDir, "1.json").readText(), "games"), createSqlOf(text, "games"))
    }

    @Test
    fun migrationSqlMatchesExportedVersionTwoSchema() {
        val text = File(schemaDir, "2.json").readText()
        assertEquals(createSqlOf(text, "puzzle_attempts"), PuzzleAttemptsSql.CREATE_TABLE)
        val index = Regex(""""createSql"\s*:\s*"(CREATE INDEX[^"]*puzzle_attempts[^"]*)"""").find(text)!!.groupValues[1]
        assertEquals(index.replace("\${TABLE_NAME}", "puzzle_attempts"), PuzzleAttemptsSql.CREATE_INDEX)
    }

    /** SQL створення таблиці з експортованої схеми, де `${TABLE_NAME}` підставлено іменем таблиці. */
    private fun createSqlOf(schema: String, table: String): String {
        val entity = Regex(""""tableName"\s*:\s*"$table",\s*"createSql"\s*:\s*"([^"]*)"""").find(schema)!!
        return entity.groupValues[1].replace("\${TABLE_NAME}", table)
    }
}
