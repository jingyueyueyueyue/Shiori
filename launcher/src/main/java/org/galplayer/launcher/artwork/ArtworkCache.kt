package org.galplayer.launcher.artwork

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import org.majiro.player.GameIcon
import java.io.File

class ArtworkCache(context: Context) {
    private val appContext = context.applicationContext
    private val covers = File(appContext.filesDir, "library-covers").apply { mkdirs() }
    private val icons = File(appContext.filesDir, "library-icons").apply { mkdirs() }

    fun extractLocal(gameId: String, metadata: LocalMetadata): LocalArtwork {
        val icon = metadata.icon?.let { document ->
            if (document.name.endsWith(".ico", true)) GameIcon.loadIco(appContext.contentResolver, document.uri)
            else GameIcon.load(appContext.contentResolver, document.uri)
        }
        val iconPath = icon?.let { bitmap ->
            runCatching { saveBitmap(bitmap, fileFor(icons, gameId, ".png"), Bitmap.CompressFormat.PNG) }.getOrNull()
        }
        val coverPath = metadata.banner?.let { document ->
            runCatching {
                val bytes = appContext.contentResolver.openInputStream(document.uri)?.use { ImageCodec.readLimited(it, MAX_IMAGE_BYTES) }
                    ?: return@runCatching null
                val bitmap = ImageCodec.decode(bytes) ?: return@runCatching null
                saveBitmap(bitmap, fileFor(covers, gameId, ".jpg"), Bitmap.CompressFormat.JPEG)
            }.getOrNull()
        }
        return LocalArtwork(coverPath, iconPath)
    }

    fun importCover(gameId: String, uri: Uri): String? = runCatching {
        val bytes = appContext.contentResolver.openInputStream(uri)?.use { ImageCodec.readLimited(it, MAX_IMAGE_BYTES) }
            ?: return@runCatching null
        saveCover(gameId, bytes)
    }.getOrNull()

    fun saveCover(gameId: String, bytes: ByteArray): String? {
        val bitmap = ImageCodec.decode(bytes) ?: return null
        return runCatching { saveBitmap(bitmap, fileFor(covers, gameId, ".jpg"), Bitmap.CompressFormat.JPEG) }.getOrNull()
    }

    fun clearFor(gameId: String) {
        if (!GAME_ID.matches(gameId)) return
        fileFor(icons, gameId, ".png").delete()
        fileFor(covers, gameId, ".jpg").delete()
    }

    fun clearAll() {
        listOf(icons, covers).forEach { directory -> directory.listFiles()?.forEach { it.delete() } }
    }

    fun sizeBytes(): Long = listOf(icons, covers).sumOf { directory ->
        directory.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
    }

    private fun saveBitmap(bitmap: Bitmap, destination: File, format: Bitmap.CompressFormat): String {
        val temporary = File(destination.parentFile, destination.name + ".tmp")
        temporary.outputStream().use { output ->
            if (!bitmap.compress(format, 92, output)) throw IllegalStateException("图片编码失败")
        }
        if (destination.exists() && !destination.delete()) {
            temporary.delete()
            throw IllegalStateException("无法替换缓存")
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            throw IllegalStateException("无法保存缓存")
        }
        return destination.absolutePath
    }

    private fun fileFor(directory: File, gameId: String, suffix: String): File {
        require(GAME_ID.matches(gameId))
        val file = File(directory, gameId + suffix)
        require(file.canonicalFile.parentFile == directory.canonicalFile)
        return file
    }

    data class LocalArtwork(val coverPath: String?, val iconPath: String?)

    private companion object {
        const val MAX_IMAGE_BYTES = 32 * 1024 * 1024
        val GAME_ID = Regex("[0-9a-f]{64}")
    }
}
