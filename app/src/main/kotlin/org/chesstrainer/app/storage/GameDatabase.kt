// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * Єдина база застосунку (SW-004): системний SQLite Android, схема експортується в `app/schemas/`.
 *
 * Правило зміни схеми: збільшити [VERSION], додати [Migration] у [MIGRATIONS], закомітити нову схему
 * `app/schemas/.../<версія>.json` і тест міграції. Деструктивної міграції немає: вона знищила б партії.
 * `GameSchemaTest` перевіряє, що ці умови не порушено.
 */
@Database(entities = [GameEntity::class], version = GameDatabase.VERSION, exportSchema = true)
abstract class GameDatabase : RoomDatabase() {
    abstract fun games(): GameDao

    companion object {
        const val VERSION = 1
        const val FILE_NAME = "games.db"

        /** Міграції з версії N на N+1 по порядку; для версії 1 порожньо. */
        val MIGRATIONS: List<Migration> = emptyList()

        fun create(context: Context): GameDatabase =
            Room.databaseBuilder(context.applicationContext, GameDatabase::class.java, FILE_NAME)
                .addMigrations(*MIGRATIONS.toTypedArray())
                .build()
    }
}
