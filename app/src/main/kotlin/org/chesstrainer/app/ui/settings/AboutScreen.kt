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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.chesstrainer.app.R
import org.chesstrainer.app.licenses.AboutInfo
import org.chesstrainer.app.licenses.LicenseAssets

/** «Про застосунок»: назва, версія, ліцензія, посилання на вихідний код (відкриває система). Прокручується при великому шрифті. */
@Composable
fun AboutScreen(onBack: () -> Unit, onOpenLicenses: () -> Unit) {
    val context = LocalContext.current
    val info by produceState<AboutInfo?>(null) {
        value = runCatching { LicenseAssets(context).about() }.getOrNull()
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsHeader(stringResource(R.string.about_title), onBack)
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                info?.let {
                    Text(stringResource(R.string.about_version, it.version), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.about_license, it.license), style = MaterialTheme.typography.bodyLarge)
                }
                Text(stringResource(R.string.about_description), style = MaterialTheme.typography.bodyMedium)
            }
            info?.let {
                SectionHeading(stringResource(R.string.about_source_code))
                LinkButton(it.sourceUrl)
            }
            FilledTonalButton(onClick = onOpenLicenses, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch)) {
                Text(stringResource(R.string.licenses_title))
            }
        }
    }
}
