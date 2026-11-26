package org.galplayer.launcher.scan

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.ByteArrayOutputStream
import java.io.IOException

data class SafDocument(
    val tree: Uri,
    val id: String,
    val name: String,
    val directory: Boolean,
) {
    val uri: Uri get() = DocumentsContract.buildDocumentUriUsingTree(tree, id)
}

class SafReader(private val resolver: ContentResolver) {
    fun children(tree: Uri, documentId: String): List<SafDocument> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val result = mutableListOf<SafDocument>()
        resolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idColumn) ?: continue
                val name = cursor.getString(nameColumn) ?: continue
                if (name == "." || name == ".." || name.contains('/') || name.contains('\\') || name.indexOf('\u0000') >= 0) continue
                result += SafDocument(
                    tree = tree,
                    id = id,
                    name = name,
                    directory = cursor.getString(mimeColumn) == DocumentsContract.Document.MIME_TYPE_DIR,
                )
            }
        } ?: throw IOException("无法读取目录")
        return result.sortedWith(compareBy<SafDocument> { it.name.lowercase() }.thenBy { it.id })
    }

    fun readBytes(document: SafDocument, maximumBytes: Int): ByteArray {
        resolver.openInputStream(document.uri)?.use { input ->
            val output = ByteArrayOutputStream(minOf(maximumBytes, 16 * 1024))
            val buffer = ByteArray(8192)
            while (output.size() < maximumBytes) {
                val count = input.read(buffer, 0, minOf(buffer.size, maximumBytes - output.size()))
                if (count < 0) break
                if (count == 0) continue
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
        throw IOException("无法读取文件：${document.name}")
    }

    fun readText(document: SafDocument, maximumBytes: Int = 64 * 1024): String {
        val bytes = readBytes(document, maximumBytes)
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
        }
        val header = bytes.copyOf(minOf(256, bytes.size)).toString(Charsets.ISO_8859_1)
        val declared = Regex("encoding\\s*=\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.get(1)
        val charsets = buildList {
            declared?.let { add(it) }
            add("UTF-8")
            add("Shift_JIS")
            add("GB18030")
        }.distinctBy { it.lowercase().replace('_', '-') }
        for (name in charsets) {
            val normalized = when (name.lowercase().replace('_', '-')) {
                "shift-jis", "shiftjis", "windows-31j", "cp932", "ms932" -> "Shift_JIS"
                "gbk", "gb2312", "gb18030" -> "GB18030"
                else -> name
            }
            try {
                val charset = java.nio.charset.Charset.forName(normalized)
                return charset.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (_: Exception) {
                continue
            }
        }
        return bytes.toString(Charsets.UTF_8)
    }

    fun child(children: List<SafDocument>, name: String, directory: Boolean? = null): SafDocument? =
        children.firstOrNull { it.name.equals(name, true) && (directory == null || it.directory == directory) }

    fun resolveRelative(tree: Uri, rootDocumentId: String, relative: String): SafDocument? {
        val normalized = relative.replace('\\', '/').trim().trimStart('.')
        if (normalized.isBlank() || normalized.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(normalized)) return null
        val segments = normalized.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty() || segments.any { it == ".." || it == "." }) return null
        var documentId = rootDocumentId
        var current: SafDocument? = null
        for ((index, segment) in segments.withIndex()) {
            current = child(children(tree, documentId), segment) ?: return null
            if (index < segments.lastIndex && !current.directory) return null
            documentId = current.id
        }
        return current
    }

    private companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
    }
}
