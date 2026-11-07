// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.licenses

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.chesstrainer.app.R

/** Шляхи до даних ліцензій в assets. Файли туди копіює Gradle під час збірки (`prepareLicenseAssets`). */
object LicenseTexts {
    const val REPORT_PATH = "licenses/aboutlibraries.json"
    const val COMPONENTS_PATH = "licenses/components.json"

    /** Повний текст GPL-3.0 це файл `LICENSE` репозиторію (не копія вручну); решта лежить у `licenses/texts/`. */
    fun assetPath(id: String): String =
        if (id == GPL_TEXT_ID) "licenses/GPL-3.0.txt" else "licenses/texts/$id.txt"
}

/** Читає дані екрана ліцензій з assets: мережа не потрібна. Усі читання йдуть поза головним потоком. */
class LicenseAssets(context: Context) {
    private val appContext = context.applicationContext

    suspend fun load(): LicensesData = withContext(Dispatchers.IO) {
        val report = LicenseParsing.parseReport(read(LicenseTexts.REPORT_PATH))
        val catalog = LicenseParsing.parseComponents(read(LicenseTexts.COMPONENTS_PATH))
        LicenseSections.build(appContext.getString(R.string.app_name), versionName(), report, catalog)
    }

    /** Назва, версія, ліцензія й посилання на код для екрана «Про застосунок» (без розбору звіту бібліотек). */
    suspend fun about(): AboutInfo = withContext(Dispatchers.IO) {
        val catalog = LicenseParsing.parseComponents(read(LicenseTexts.COMPONENTS_PATH))
        AboutInfo(appContext.getString(R.string.app_name), versionName(), catalog.app.license, catalog.app.source)
    }

    /** Текст ліцензії чи NOTICE за ідентифікатором; `null`, якщо файлу немає. */
    suspend fun text(id: String): String? = withContext(Dispatchers.IO) {
        runCatching { read(LicenseTexts.assetPath(id)) }.getOrNull()
    }

    private fun read(path: String): String =
        appContext.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }

    @Suppress("DEPRECATION")
    private fun versionName(): String =
        runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName }.getOrNull().orEmpty()
}
