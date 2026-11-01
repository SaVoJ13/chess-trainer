// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.pgn

import org.chesstrainer.app.storage.GameId

/** Що експортувати: одну партію або всі збережені. */
sealed interface ExportSelection {
    data class One(val id: GameId) : ExportSelection

    data object All : ExportSelection
}

/** Повідомлення про результат імпорту чи експорту. */
sealed interface TransferMessage {
    data class Import(val result: ImportResult) : TransferMessage

    data object ExportSaved : TransferMessage

    data object ExportFailed : TransferMessage

    data object ExportNothing : TransferMessage
}

/** Стан імпорту й експорту, який показує UI. */
sealed interface TransferState {
    data object Idle : TransferState

    /** Файл читається, розбирається чи записується. */
    data object Busy : TransferState

    /** Користувач обирає: зберегти у файл чи поділитися. */
    data class ChooseExport(val selection: ExportSelection) : TransferState

    data class Message(val message: TransferMessage) : TransferState
}
