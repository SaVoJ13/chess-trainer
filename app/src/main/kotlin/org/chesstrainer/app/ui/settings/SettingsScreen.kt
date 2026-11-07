// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.chesstrainer.app.R
import org.chesstrainer.app.settings.Appearance
import org.chesstrainer.app.settings.BoardThemeId
import org.chesstrainer.app.ui.theme.BoardPalette
import org.chesstrainer.app.ui.theme.BoardTheme
import org.chesstrainer.app.ui.theme.BoardThemes

/**
 * Екран «Налаштування» (CONTRACTS 5.1): група «Тема дошки» над списком «Про застосунок» і «Відкриті ліцензії».
 * Вибір набору фігур і перемикач анімацій додаються окремими групами в наступних PR.
 */
@Composable
fun SettingsScreen(
    appearance: Appearance,
    onBoardThemeChange: (BoardThemeId) -> Unit,
    onBack: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsHeader(stringResource(R.string.settings_title), onBack)
        LazyColumn(Modifier.fillMaxWidth()) {
            item {
                SectionHeading(stringResource(R.string.settings_group_board_theme), Modifier.padding(horizontal = 4.dp))
                Text(
                    stringResource(R.string.settings_board_theme_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
            items(BoardThemes.all, key = { it.id.value }) { theme ->
                BoardThemeOption(theme, selected = theme.id == appearance.boardTheme, onSelect = { onBoardThemeChange(theme.id) })
            }
            item {
                HorizontalDivider(Modifier.padding(top = 4.dp))
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

/**
 * Рядок вибору теми: ціла смуга це перемикач (`Role.RadioButton`), TalkBack читає назву й «вибрано».
 * Мініатюра лише декоративна й для екранних читачів прихована.
 */
@Composable
private fun BoardThemeOption(theme: BoardTheme, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouch)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        BoardThemePreview(theme.palette)
        Text(
            stringResource(boardThemeNameRes(theme.id)),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Мініатюра теми: 4×2 клітинки з підсвіткою останнього ходу, вибору й крапкою можливого ходу. */
@Composable
private fun BoardThemePreview(palette: BoardPalette) {
    Canvas(Modifier.size(width = 64.dp, height = 32.dp).clearAndSetSemantics {}) {
        val cell = size.height / 2f
        val cellSize = Size(cell, cell)
        for (row in 0..1) {
            for (column in 0..3) {
                val isLight = (row + column) % 2 == 0
                val origin = Offset(column * cell, row * cell)
                drawRect(if (isLight) palette.lightSquare else palette.darkSquare, origin, cellSize)
                if (row == 0 && column == 1) drawRect(palette.lastMove.on(isLight), origin, cellSize)
                if (row == 1 && column == 2) drawRect(palette.selected.on(isLight), origin, cellSize)
                if (row == 0 && column == 3) drawCircle(palette.targetHint, cell * 0.2f, origin + Offset(cell / 2, cell / 2))
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
