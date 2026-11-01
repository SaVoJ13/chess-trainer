// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.ui.games

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.chesstrainer.app.R
import org.chesstrainer.app.pgn.ExportSelection
import org.chesstrainer.app.pgn.ImportResult
import org.chesstrainer.app.pgn.PgnFiles
import org.chesstrainer.app.pgn.TransferMessage
import org.chesstrainer.app.pgn.TransferState
import org.chesstrainer.app.review.GamesViewModel

/** Дії імпорту й експорту для екранів; діалоги й системні вікна живуть у [rememberPgnTransfer]. */
class PgnTransferActions(val importFile: () -> Unit, val export: (ExportSelection) -> Unit)

/**
 * Імпорт і експорт PGN лише через Storage Access Framework та системне «Поділитися» (CONTRACTS 5): жодних
 * дозволів на сховище. Малює діалоги вибору, прогресу й підсумку; викликати один раз на екран.
 */
@Composable
fun rememberPgnTransfer(viewModel: GamesViewModel): PgnTransferActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.transfer.collectAsState()

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importFrom(uri)
    }
    val createLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PgnFiles.MIME_TYPE)) { uri ->
        if (uri != null) viewModel.saveTo(uri) else viewModel.cancelSave()
    }

    when (val s = state) {
        TransferState.Idle -> Unit
        TransferState.Busy -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(stringResource(R.string.transfer_busy)) },
            text = { CircularProgressIndicator() },
        )
        is TransferState.ChooseExport -> AlertDialog(
            onDismissRequest = viewModel::dismissTransfer,
            title = { Text(stringResource(R.string.transfer_export_title)) },
            text = {
                Column {
                    TextButton(
                        onClick = { viewModel.beginSave()?.let(createLauncher::launch) },
                        modifier = Modifier.heightIn(min = MinTouch),
                    ) { Text(stringResource(R.string.transfer_export_save)) }
                    TextButton(
                        onClick = {
                            scope.launch {
                                val uri = viewModel.prepareShare() ?: return@launch
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = PgnFiles.MIME_TYPE
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    clipData = ClipData.newRawUri(null, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                try {
                                    context.startActivity(Intent.createChooser(send, context.getString(R.string.transfer_share_chooser)))
                                } catch (e: ActivityNotFoundException) {
                                    // Немає застосунку для «Поділитися»: лишається збереження у файл.
                                }
                            }
                        },
                        modifier = Modifier.heightIn(min = MinTouch),
                    ) { Text(stringResource(R.string.transfer_export_share)) }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = viewModel::dismissTransfer, modifier = Modifier.heightIn(min = MinTouch)) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
        is TransferState.Message -> MessageDialog(s.message, viewModel::dismissTransfer)
    }

    return remember(viewModel, openLauncher) {
        PgnTransferActions(
            importFile = { openLauncher.launch(arrayOf("*/*")) },
            export = viewModel::requestExport,
        )
    }
}

@Composable
private fun MessageDialog(message: TransferMessage, onDismiss: () -> Unit) {
    val import = (message as? TransferMessage.Import)?.result
    AlertDialog(
        onDismissRequest = onDismiss,
        title = if (import is ImportResult.Done) {
            { Text(stringResource(R.string.transfer_import_title)) }
        } else {
            null
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    import is ImportResult.Done -> {
                        Text(stringResource(R.string.transfer_import_summary, import.imported, import.skipped.size))
                        if (import.skipped.isNotEmpty()) {
                            Text(
                                stringResource(R.string.transfer_import_skipped, import.skipped.joinToString(", ")),
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                    else -> Text(stringResource(messageText(message)))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouch)) {
                Text(stringResource(R.string.common_ok))
            }
        },
    )
}

private fun messageText(message: TransferMessage): Int = when (message) {
    is TransferMessage.Import -> when (message.result) {
        is ImportResult.Done -> R.string.transfer_import_summary
        ImportResult.NoGames -> R.string.transfer_import_no_games
        ImportResult.TooLarge -> R.string.transfer_import_too_large
        ImportResult.TooMany -> R.string.transfer_import_too_many
        ImportResult.ReadError -> R.string.transfer_import_read_error
    }
    TransferMessage.ExportSaved -> R.string.transfer_export_saved
    TransferMessage.ExportFailed -> R.string.transfer_export_failed
    TransferMessage.ExportNothing -> R.string.transfer_export_nothing
}
