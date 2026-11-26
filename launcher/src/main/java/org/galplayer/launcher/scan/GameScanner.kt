package org.galplayer.launcher.scan

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.galplayer.launcher.artwork.LocalMetadata
import org.galplayer.launcher.artwork.MetadataExtractor
import org.galplayer.launcher.data.GameIdentity
import org.galplayer.launcher.model.EngineType
import org.galplayer.launcher.model.GameEntry
import org.galplayer.launcher.model.ScanMode
import org.galplayer.launcher.model.ScanRoot
import java.io.IOException

data class ScanBatch(
    val games: List<GameEntry>,
    val metadata: Map<String, LocalMetadata>,
    val unreadable: Int,
)

class GameScanner(context: Context) {
    private val appContext = context.applicationContext
    private val reader = SafReader(appContext.contentResolver)
    private val detector = EngineDetector(reader)
    private val metadataExtractor = MetadataExtractor(reader)

    suspend fun scan(root: ScanRoot): ScanBatch = withContext(Dispatchers.IO) {
        val tree = Uri.parse(root.uri)
        val documentId = DocumentsContract.getTreeDocumentId(tree)
        val rootName = DocumentFile.fromTreeUri(appContext, tree)?.name
            ?: documentId.substringAfter(':').substringAfterLast('/').ifBlank { documentId }
        val queue = ArrayDeque<Folder>()
        queue += Folder(tree, documentId, rootName, 0)
        val visited = mutableSetOf<String>()
        val games = mutableListOf<GameEntry>()
        val metadata = mutableMapOf<String, LocalMetadata>()
        var unreadable = 0
        while (queue.isNotEmpty()) {
            val folder = queue.removeFirst()
            if (!visited.add(folder.documentId)) continue
            if (visited.size > MAX_NODES) throw IOException("待扫描目录太多，请选择更具体的文件夹")
            val detection = try {
                detector.detect(folder.tree, folder.documentId)
            } catch (error: Exception) {
                if (folder.depth == 0) throw error
                unreadable++
                continue
            }
            if (detection != null) {
                val resolved = if (root.recursive.not() && folder.name.equals("system", true) && detection.engine == EngineType.CATSYSTEM2) {
                    authorizedParent(folder) ?: folder
                } else folder
                val resolvedDetection = if (resolved == folder) detection else detector.detect(resolved.tree, resolved.documentId) ?: detection
                val gameId = GameIdentity.id(resolved.tree, resolved.documentId)
                val local = runCatching {
                    metadataExtractor.extract(resolved.tree, resolved.documentId, resolved.name, resolvedDetection.engine)
                }.getOrElse { LocalMetadata(resolved.name, resolved.name, null, null) }
                games += GameEntry(
                    id = gameId,
                    treeUri = resolved.tree.toString(),
                    documentId = resolved.documentId,
                    rootUri = root.uri,
                    title = local.title,
                    detectedEngine = resolvedDetection.engine,
                )
                metadata[gameId] = local
            } else if (root.recursive && folder.depth < MAX_DEPTH) {
                val children = try {
                    reader.children(folder.tree, folder.documentId)
                } catch (error: Exception) {
                    if (folder.depth == 0) throw error
                    unreadable++
                    continue
                }
                children.filter { it.directory && it.name.lowercase() !in NOISE_DIRECTORIES }.forEach {
                    queue += Folder(folder.tree, it.id, it.name, folder.depth + 1)
                }
            }
        }
        ScanBatch(games, metadata, unreadable)
    }

    suspend fun metadataFor(game: GameEntry): LocalMetadata = withContext(Dispatchers.IO) {
        val tree = Uri.parse(game.treeUri)
        val name = DocumentFile.fromTreeUri(appContext, tree)?.name
            ?: game.documentId.substringAfter(':').substringAfterLast('/')
        metadataExtractor.extract(tree, game.documentId, name, game.engine)
    }

    private fun authorizedParent(selected: Folder): Folder? {
        val separator = selected.documentId.lastIndexOf('/').let { slash ->
            if (slash >= 0) slash else selected.documentId.indexOf(':') + 1
        }
        if (separator <= 0 || separator > selected.documentId.length) return null
        val parentId = selected.documentId.substring(0, separator)
        val grants = appContext.contentResolver.persistedUriPermissions.map { it.uri }
            .filter { it.authority == selected.tree.authority && it != selected.tree }
        for (grant in grants) {
            val grantedRoot = runCatching { DocumentsContract.getTreeDocumentId(grant) }.getOrNull() ?: continue
            if (grantedRoot != parentId && !parentId.startsWith("$grantedRoot/")) continue
            try {
                val parentChildren = reader.children(grant, parentId)
                val selectedEntry = parentChildren.firstOrNull { it.id == selected.documentId && it.name.equals("system", true) }
                val hasCs2Resources = parentChildren.any {
                    !it.directory && (it.name.equals("scene.int", true) ||
                        (it.name.startsWith("image", true) && it.name.endsWith(".int", true)))
                } || parentChildren.any { it.directory && it.name.equals("config", true) }
                if (selectedEntry != null && hasCs2Resources) {
                    val name = DocumentFile.fromTreeUri(appContext, grant)?.name
                        ?: parentId.substringAfter(':').substringAfterLast('/')
                    return Folder(grant, parentId, name, 0)
                }
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    private data class Folder(val tree: Uri, val documentId: String, val name: String, val depth: Int)

    private companion object {
        const val MAX_DEPTH = 8
        const val MAX_NODES = 4096
        val NOISE_DIRECTORIES = setOf(
            "savedata", "save", "save_chs", "movie", "bgm", "voice", "archive", "manual",
            "dsfilter", "原版备份", "__macosx",
        )
    }
}
