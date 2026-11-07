// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.chesstrainer.app.R
import org.chesstrainer.app.licenses.LicenseSectionId
import org.chesstrainer.app.licenses.LicenseTextFormat

internal val MinTouch = 48.dp

/** Шапка екранів налаштувань: «Назад» і заголовок. Заголовок позначено для TalkBack як заголовок. */
@Composable
internal fun SettingsHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = MinTouch)) {
            Text(stringResource(R.string.settings_back))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
    }
}

/** Кнопка-посилання: видно сам URL, для TalkBack підпис «Відкрити посилання: …». Відкриває система, застосунок у мережу не ходить. */
@Composable
internal fun LinkButton(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val description = stringResource(R.string.link_open, url)
    TextButton(
        onClick = { openLink(context, url) },
        enabled = LicenseTextFormat.isOpenableLink(url),
        modifier = modifier.heightIn(min = MinTouch).semantics { contentDescription = description },
    ) {
        Text(url, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.padding(top = 8.dp).semantics { heading() },
    )
}

/** Відкриття посилання системною дією `ACTION_VIEW` за дотиком користувача (дозвіл INTERNET не потрібен). */
internal fun openLink(context: Context, url: String) {
    if (!LicenseTextFormat.isOpenableLink(url)) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.link_no_app, Toast.LENGTH_SHORT).show()
    }
}

/** Заголовок розділу екрана ліцензій (uk, en). */
internal fun sectionTitleRes(id: LicenseSectionId): Int = when (id) {
    LicenseSectionId.APP -> R.string.licenses_section_app
    LicenseSectionId.NATIVE -> R.string.licenses_section_native
    LicenseSectionId.LIBRARIES -> R.string.licenses_section_libraries
    LicenseSectionId.CONTENT -> R.string.licenses_section_content
    LicenseSectionId.PIECE_SETS -> R.string.licenses_section_piece_sets
}

internal fun sectionIntroRes(id: LicenseSectionId): Int = when (id) {
    LicenseSectionId.APP -> R.string.licenses_section_app_intro
    LicenseSectionId.NATIVE -> R.string.licenses_section_native_intro
    LicenseSectionId.LIBRARIES -> R.string.licenses_section_libraries_intro
    LicenseSectionId.CONTENT -> R.string.licenses_section_content_intro
    LicenseSectionId.PIECE_SETS -> R.string.licenses_section_piece_sets_intro
}

/** Пояснення до запису за ключем із `components.json`; `null` для невідомого ключа (запис просто без пояснення). */
internal fun noteRes(noteId: String): Int? = when (noteId) {
    "app_source" -> R.string.licenses_note_app_source
    "stockfish_source" -> R.string.licenses_note_stockfish_source
    "nnue_lc0" -> R.string.licenses_note_nnue_lc0
    "libcxx_static" -> R.string.licenses_note_libcxx_static
    "puzzles_data_only" -> R.string.licenses_note_puzzles_data_only
    "own_pieces" -> R.string.licenses_note_own_pieces
    else -> null
}
