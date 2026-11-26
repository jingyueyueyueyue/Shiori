package org.galplayer.launcher.artwork

import android.net.Uri
import org.galplayer.launcher.model.EngineType
import org.galplayer.launcher.scan.SafDocument
import org.galplayer.launcher.scan.SafReader
import org.json.JSONObject

data class LocalMetadata(
    val title: String,
    val searchTitle: String,
    val icon: SafDocument?,
    val banner: SafDocument?,
)

class MetadataExtractor(private val reader: SafReader) {
    fun extract(tree: Uri, documentId: String, folderName: String, engine: EngineType): LocalMetadata {
        val children = reader.children(tree, documentId)
        val fallback = TitleNormalizer.normalize(folderName)
        var title: String? = null
        var icon: SafDocument? = null
        var banner: SafDocument? = null
        if (engine == EngineType.MAJIRO) {
            reader.child(children, "majiro-game.json", false)?.let { file ->
                title = runCatching { JSONObject(reader.readText(file)).optString("title") }.getOrNull()
            }
            if (title.isNullOrBlank()) {
                children.firstOrNull { !it.directory && it.name.endsWith(".env", true) }?.let { file ->
                    title = runCatching {
                        reader.readText(file).lineSequence().firstOrNull { it.trimStart().startsWith("TITLEBAR", true) }
                            ?.substringAfter('=')?.trim()?.trim('"', '\'')
                    }.getOrNull()
                }
            }
        } else {
            val startupFiles = linkedMapOf<String, SafDocument>()
            reader.child(children, "startup.xml", false)?.let { startupFiles[documentId] = it }
            reader.child(children, "config", true)?.let { config ->
                runCatching { reader.child(reader.children(tree, config.id), "startup.xml", false) }.getOrNull()
                    ?.let { startupFiles[config.id] = it }
            }
            reader.child(children, "system", true)?.let { system ->
                val systemChildren = runCatching { reader.children(tree, system.id) }.getOrDefault(emptyList())
                reader.child(systemChildren, "config", true)?.let { config ->
                    runCatching { reader.child(reader.children(tree, config.id), "startup.xml", false) }.getOrNull()
                        ?.let { startupFiles[config.id] = it }
                }
            }
            for ((parentId, startup) in startupFiles) {
                val xml = runCatching { reader.readText(startup) }.getOrNull() ?: continue
                val app = Regex("<APP\\b[^>]*>(.*?)</APP>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .find(xml)?.groupValues?.get(1) ?: continue
                fun tag(name: String): String? = Regex("<$name\\b[^>]*>(.*?)</$name>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .find(app)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
                title = tag("title")?.let(::metadataTitle)
                icon = tag("icon")?.let { ref ->
                    reader.resolveRelative(tree, parentId, ref) ?: reader.resolveRelative(tree, documentId, ref)
                }
                banner = tag("banner")?.let { ref ->
                    reader.resolveRelative(tree, parentId, ref) ?: reader.resolveRelative(tree, documentId, ref)
                }
                if (!title.isNullOrBlank() || icon != null || banner != null) break
            }
        }

        val binChildren = children.firstOrNull { it.directory && it.name.equals("bin", true) }
            ?.let { runCatching { reader.children(tree, it.id) }.getOrDefault(emptyList()) } ?: emptyList()
        val images = children.filterNot { it.directory }
        if (icon == null) icon = images.firstOrNull { it.name.endsWith(".ico", true) }
        val executables = (images + binChildren).filter {
            !it.directory && it.name.endsWith(".exe", true) && !EXCLUDED_EXE.containsMatchIn(it.name)
        }
        if (icon == null) {
            val preferred = if (engine == EngineType.CATSYSTEM2 && executables.size > 1) {
                executables.filterNot { it.name.equals("cs2.exe", true) }
            } else executables
            icon = preferred.sortedWith(compareBy<SafDocument> {
                if (it.name.equals("cs2.exe", true)) 0 else 1
            }.thenByDescending { it.name.length }).firstOrNull()
        }
        if (banner == null) banner = images.firstOrNull { file ->
            IMAGE_EXTENSIONS.any { file.name.endsWith(it, true) } && COVER_NAMES.any { file.name.startsWith(it, true) }
        }
        val resolvedTitle = title?.takeIf { it.isNotBlank() } ?: fallback
        return LocalMetadata(resolvedTitle, TitleNormalizer.normalize(resolvedTitle), icon, banner)
    }

    private fun metadataTitle(value: String): String? = value
        .replace(Regex("^(?:\\$[0-9A-Fa-f-]+\\$)+"), "")
        .trim()
        .takeIf { it.isNotBlank() && '\uFFFD' !in it && it.none { char -> char < ' ' && char != '\t' } }

    private companion object {
        val EXCLUDED_EXE = Regex("(?i)(?:uninst|unins\\d*|install|setup|menu|launcher|crash|report)")
        val IMAGE_EXTENSIONS = listOf(".bmp", ".png", ".jpg", ".jpeg", ".webp")
        val COVER_NAMES = listOf("cover", "banner", "package", "thumbnail")
    }
}
