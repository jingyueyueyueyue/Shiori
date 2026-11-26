package org.galplayer.launcher.diagnostics

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DiagnosticsExporter(context: Context) {
    private val appContext = context.applicationContext

    suspend fun export(destination: Uri): Int = withContext(Dispatchers.IO) {
        val files = appContext.filesDir.walkTopDown().filter { file ->
            file.isFile && file.length() <= MAX_FILE_BYTES &&
                (file.extension.equals("log", true) ||
                    file.name.contains("diagnostic", true) || file.name.contains("error", true))
        }.take(MAX_FILES).toList()
        appContext.contentResolver.openOutputStream(destination)?.use { output ->
            ZipOutputStream(output).use { zip ->
                files.forEach { file ->
                    val path = file.relativeTo(appContext.filesDir).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(path))
                    file.inputStream().use { input -> input.copyTo(zip, 64 * 1024) }
                    zip.closeEntry()
                }
            }
        } ?: 0
        files.size
    }

    private companion object {
        const val MAX_FILE_BYTES = 4 * 1024 * 1024
        const val MAX_FILES = 40
    }
}
