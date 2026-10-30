// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Рядок таблиці партій. Лише в `app`, у контракти не потрапляє (CONTRACTS 5).
 * Переліки зберігаються іменами (стабільними), час це мілісекунди Unix.
 */
@Entity(tableName = "games", indices = [Index(value = ["finished", "updatedAt"])])
data class GameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pgn: String,
    val mode: String,
    val playerColor: String?,
    val difficultyId: Int?,
    val finished: Boolean,
    val termination: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
