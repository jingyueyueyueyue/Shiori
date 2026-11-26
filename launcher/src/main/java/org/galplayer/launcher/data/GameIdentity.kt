package org.galplayer.launcher.data

import android.net.Uri
import android.provider.DocumentsContract
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

object GameIdentity {
    fun documentId(tree: Uri, documentId: String? = null): String =
        documentId?.takeIf { it.isNotBlank() } ?: DocumentsContract.getTreeDocumentId(tree)

    fun canonicalTreeUri(tree: Uri, documentId: String): Uri =
        DocumentsContract.buildTreeDocumentUri(tree.authority, documentId)

    fun documentUri(tree: Uri, documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, documentId)

    fun id(tree: Uri, documentId: String): String = digest(canonicalTreeUri(tree, documentId).toString())

    fun digest(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }
}
