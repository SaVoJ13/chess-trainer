// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** SQL схеми v2 для таблиці прогресу задач; має збігатися з `app/schemas/.../2.json` (перевіряє `GameSchemaTest`). */
internal object PuzzleAttemptsSql {
    const val CREATE_TABLE =
        "CREATE TABLE IF NOT EXISTS `puzzle_attempts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`puzzleId` TEXT NOT NULL, `state` TEXT NOT NULL, `wrongMoves` INTEGER NOT NULL, " +
            "`hintsUsed` INTEGER NOT NULL, `timeMs` INTEGER NOT NULL, `at` INTEGER NOT NULL)"
    const val CREATE_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_puzzle_attempts_puzzleId_id` ON `puzzle_attempts` (`puzzleId`, `id`)"
}

/**
 * Версія 1 → 2: таблиця прогресу задач (CONTRACTS 5, «Схема v2»). Таблиця `games` не змінюється,
 * тож наявні партії зберігаються.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(PuzzleAttemptsSql.CREATE_TABLE)
        db.execSQL(PuzzleAttemptsSql.CREATE_INDEX)
    }
}
