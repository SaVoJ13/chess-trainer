// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.storage

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Тест міграції Room 1 → 2 на пристрої (CONTRACTS 5, v0.16). Запуск: `./gradlew :app:connectedDebugAndroidTest`
 * (потрібен підключений телефон з розблокованим екраном; у CI цей код лише компілюється).
 */
@RunWith(AndroidJUnit4::class)
class GameMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GameDatabase::class.java)

    private val dbName = "migration-test.db"

    private fun insertV1Game(db: androidx.sqlite.db.SupportSQLiteDatabase, pgn: String, finished: Int) {
        db.execSQL(
            "INSERT INTO games (pgn, mode, playerColor, difficultyId, finished, termination, createdAt, updatedAt) " +
                "VALUES ('$pgn', 'VS_ENGINE', 'WHITE', 3, $finished, NULL, 1000, 2000)",
        )
    }

    @Test
    @Throws(IOException::class)
    fun migration1To2KeepsGamesAndCreatesEmptyPuzzleTable() {
        helper.createDatabase(dbName, 1).apply {
            insertV1Game(this, "1. e4 e5 *", 0)
            insertV1Game(this, "1. d4 d5 2. c4 *", 1)
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2)

        db.query("SELECT pgn, finished, difficultyId FROM games ORDER BY id").use { c ->
            assertEquals(2, c.count)
            c.moveToFirst()
            assertEquals("1. e4 e5 *", c.getString(0))
            assertEquals(0, c.getInt(1))
            assertEquals(3, c.getInt(2))
            c.moveToNext()
            assertEquals("1. d4 d5 2. c4 *", c.getString(0))
            assertEquals(1, c.getInt(1))
        }
        db.query("SELECT COUNT(*) FROM puzzle_attempts").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
        db.execSQL("INSERT INTO puzzle_attempts (puzzleId, state, wrongMoves, hintsUsed, timeMs, at) VALUES ('p1', 'SOLVED', 0, 1, 500, 3000)")
        db.close()
    }

    @Test
    fun roomOpensMigratedDatabaseWithTheShippedMigrationList() {
        helper.createDatabase(dbName, 1).apply {
            insertV1Game(this, "1. e4 *", 0)
            close()
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = Room.databaseBuilder(context, GameDatabase::class.java, dbName)
            .addMigrations(*GameDatabase.MIGRATIONS.toTypedArray())
            .build()
        try {
            runBlocking {
                val summaries = room.games().lastUnfinished()
                assertTrue("партія з v1 має зберегтися", summaries != null && summaries.pgn == "1. e4 *")
                assertEquals(emptyList<String>(), room.puzzleProgress().failedIds(10))
            }
        } finally {
            room.close()
            context.deleteDatabase(dbName)
        }
    }
}
