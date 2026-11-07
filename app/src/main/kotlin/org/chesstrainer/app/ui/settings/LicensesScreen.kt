// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.chesstrainer.app.R
import org.chesstrainer.app.licenses.LicenseAssets
import org.chesstrainer.app.licenses.LicenseEntry
import org.chesstrainer.app.licenses.LicenseSection
import org.chesstrainer.app.licenses.LicenseTextFormat
import org.chesstrainer.app.licenses.LicensesData

private sealed interface LicensesLoad {
    data object Loading : LicensesLoad
    data object Failed : LicensesLoad
    data class Ready(val data: LicensesData) : LicensesLoad
}

/**
 * «Відкриті ліцензії» (CONTRACTS 5.1): п'ять розділів. Дані з генерованого звіту й `components.json` в assets,
 * тексти ліцензій лежать у застосунку, мережа не потрібна. Назви, автори й тексти ліцензій не перекладаються.
 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val assets = remember { LicenseAssets(context) }
    val load by produceState<LicensesLoad>(LicensesLoad.Loading) {
        value = runCatching { LicensesLoad.Ready(assets.load()) }.getOrElse { LicensesLoad.Failed }
    }
    // Які тексти розгорнуто (ключ запису + "|" + id тексту); переживає поворот екрана.
    val expanded: SnapshotStateList<String> = rememberSaveable(
        saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() }),
    ) { mutableListOf<String>().toMutableStateList() }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsHeader(stringResource(R.string.licenses_title), onBack)
        when (val state = load) {
            LicensesLoad.Loading -> Text(stringResource(R.string.licenses_loading), style = MaterialTheme.typography.bodyLarge)
            LicensesLoad.Failed -> Text(stringResource(R.string.licenses_failed), style = MaterialTheme.typography.bodyLarge)
            is LicensesLoad.Ready -> LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (section in state.data.sections) {
                    sectionItems(section, assets, expanded)
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.sectionItems(
    section: LicenseSection,
    assets: LicenseAssets,
    expanded: SnapshotStateList<String>,
) {
    item(key = "section:${section.id}") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionHeading(stringResource(sectionTitleRes(section.id)))
            Text(stringResource(sectionIntroRes(section.id)), style = MaterialTheme.typography.bodyMedium)
        }
    }
    for (entry in section.entries) {
        item(key = entry.key) { EntryCard(entry, assets, expanded) }
    }
    if (section.sharedTexts.isNotEmpty()) {
        item(key = "texts:${section.id}") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.licenses_texts_heading),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                for (shared in section.sharedTexts) {
                    ExpandableText("shared:${section.id}", shared.id, shared.label, assets, expanded)
                }
            }
        }
    }
}

@Composable
private fun EntryCard(entry: LicenseEntry, assets: LicenseAssets, expanded: SnapshotStateList<String>) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Назва, версія, автори й ліцензія читаються TalkBack одним блоком; кнопки нижче окремо.
        Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (entry.version.isNotBlank()) Text(stringResource(R.string.licenses_version, entry.version), style = MaterialTheme.typography.bodyMedium)
            if (entry.authors.isNotBlank()) Text(stringResource(R.string.licenses_authors, entry.authors), style = MaterialTheme.typography.bodyMedium)
            Text(
                if (entry.licenseLabel.isBlank()) stringResource(R.string.licenses_license_unknown)
                else stringResource(R.string.licenses_license, entry.licenseLabel),
                style = MaterialTheme.typography.bodyMedium,
            )
            for (noteId in entry.noteIds) {
                noteRes(noteId)?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
            }
            if (entry.converted) Text(stringResource(R.string.licenses_converted), style = MaterialTheme.typography.bodySmall)
        }
        for (url in entry.links) LinkButton(url)
        for (textId in entry.textIds) ExpandableText(entry.key, textId, textId, assets, expanded)
        entry.noticeId?.let { ExpandableText(entry.key, it, stringResource(R.string.licenses_notice), assets, expanded) }
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

/** Кнопка «Показати/Сховати текст: …» і, якщо розгорнуто, сам текст абзацами (читається з assets за потреби). */
@Composable
private fun ExpandableText(
    owner: String,
    textId: String,
    label: String,
    assets: LicenseAssets,
    expanded: SnapshotStateList<String>,
) {
    val key = "$owner|$textId"
    val isOpen = key in expanded
    TextButton(
        onClick = { if (isOpen) expanded.remove(key) else expanded.add(key) },
        modifier = Modifier.heightIn(min = MinTouch),
    ) {
        Text(
            stringResource(if (isOpen) R.string.licenses_hide_text else R.string.licenses_show_text, label),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (isOpen) {
        val paragraphs by produceState<List<String>?>(null, textId) {
            value = assets.text(textId)?.let(LicenseTextFormat::paragraphs).orEmpty()
        }
        val loaded = paragraphs
        Column(Modifier.fillMaxWidth().padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                loaded == null -> Unit
                loaded.isEmpty() -> Text(stringResource(R.string.licenses_text_missing), style = MaterialTheme.typography.bodySmall)
                else -> for (p in loaded) Text(p, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
