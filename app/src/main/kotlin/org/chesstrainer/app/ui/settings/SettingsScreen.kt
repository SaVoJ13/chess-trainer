// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.chesstrainer.app.R

/**
 * Екран «Налаштування» (CONTRACTS 5.1). Зараз у ньому лише «Про застосунок» і «Відкриті ліцензії»;
 * вибір теми дошки, набору фігур і перемикач анімацій додаються окремими групами над цим списком.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenAbout: () -> Unit, onOpenLicenses: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsHeader(stringResource(R.string.settings_title), onBack)
        LazyColumn(Modifier.fillMaxWidth()) {
            item {
                SettingsItem(R.string.settings_item_about, R.string.settings_item_about_summary, onOpenAbout)
                HorizontalDivider()
            }
            item {
                SettingsItem(R.string.settings_item_licenses, R.string.settings_item_licenses_summary, onOpenLicenses)
                HorizontalDivider()
            }
        }
    }
}

/** Рядок налаштувань: ціла смуга натискається, TalkBack читає назву й пояснення разом як кнопку. */
@Composable
private fun SettingsItem(title: Int, summary: Int, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouch)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(summary), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
