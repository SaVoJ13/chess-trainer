// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.app.pgn

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Файловий ввід-вивід PGN через Storage Access Framework (CONTRACTS 5): адреси `content://` дає системний
 * вибір файлу, дозволів на сховище немає. Для меню «Поділитися» файл кладеться в підкаталог кешу, який один
 * відкриває `FileProvider` (`res/xml/export_paths.xml`); решта сховища застосунку недоступна.
 */
class PgnFiles(context: Context) {
    private val context = context.applicationContext

    /** Читає вибраний користувачем файл через імпортер. */
    suspend fun import(importer: PgnImporter, uri: Uri): ImportResult {
        val stream = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        } ?: return ImportResult.ReadError
        return importer.import(stream)
    }

    /** Записує [text] (UTF-8) у файл, створений користувачем. `false` при помилці запису. */
    suspend fun write(uri: Uri, text: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val stream = context.contentResolver.openOutputStream(uri, "wt") ?: return@withContext false
            stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            true
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    /** Кладе [text] у кеш експорту (попередні файли видаляються) і повертає `content://` адресу для `ACTION_SEND`. */
    suspend fun prepareShare(text: String, fileName: String): Uri? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, EXPORT_DIR)
            dir.deleteRecursively()
            if (!dir.mkdirs()) return@withContext null
            val file = File(dir, fileName)
            file.writeText(text, Charsets.UTF_8)
            FileProvider.getUriForFile(context, "${context.packageName}$AUTHORITY_SUFFIX", file)
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    companion object {
        const val MIME_TYPE = "application/x-chess-pgn"

        /** Збігається з `path` у `res/xml/export_paths.xml` і з `authorities` у маніфесті. */
        const val EXPORT_DIR = "exports"
        const val AUTHORITY_SUFFIX = ".exports"
    }
}
