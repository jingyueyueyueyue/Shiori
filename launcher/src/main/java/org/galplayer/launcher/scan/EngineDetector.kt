package org.galplayer.launcher.scan

import android.net.Uri
import org.galplayer.launcher.model.EngineType

data class EngineDetection(
    val engine: EngineType,
    val majiroScore: Int,
    val cs2Score: Int,
)

class EngineDetector(private val reader: SafReader) {
    fun detect(tree: Uri, documentId: String): EngineDetection? {
        val children = reader.children(tree, documentId)
        val system = reader.child(children, "system", true)
        val config = reader.child(children, "config", true)
        val patches = reader.child(children, "patches", true)
        val systemChildren = system?.let { runCatching { reader.children(tree, it.id) }.getOrDefault(emptyList()) } ?: emptyList()
        val configChildren = config?.let { runCatching { reader.children(tree, it.id) }.getOrDefault(emptyList()) } ?: emptyList()
        val systemConfigChildren = systemChildren.firstOrNull { it.directory && it.name.equals("config", true) }
            ?.let { runCatching { reader.children(tree, it.id) }.getOrDefault(emptyList()) } ?: emptyList()
        val patchChildren = patches?.let { runCatching { reader.children(tree, it.id) }.getOrDefault(emptyList()) } ?: emptyList()

        var majiroScore = 0
        var cs2Score = 0
        children.filterNot { it.directory }.forEach { file ->
            val name = file.name.lowercase()
            when {
                name.endsWith(".arc") && name != "savedata.arc" && hasArcMagic(file) -> majiroScore += 110
                (name == "majiro-game.json" || name == "majiro.env") -> majiroScore += 100
                name.endsWith(".mjo") && hasMjoMagic(file) -> majiroScore += 105
                name == "cs2-localization.json" -> cs2Score += 100
                isCs2Startup(name) -> cs2Score += 115
                name == "startup.xml" && hasStartupNodes(file) -> cs2Score += 100
            }
        }
        if (reader.child(patchChildren, "manifest.json", false) != null) majiroScore += 90
        systemChildren.filterNot { it.directory }.forEach { file ->
            if (isCs2Startup(file.name.lowercase())) cs2Score += 115
        }
        if (configChildren.any { !it.directory && it.name.equals("startup.xml", true) && hasStartupNodes(it) }) cs2Score += 100
        if (systemConfigChildren.any { !it.directory && it.name.equals("startup.xml", true) && hasStartupNodes(it) }) cs2Score += 100

        val engine = when {
            majiroScore == 0 && cs2Score == 0 -> return null
            majiroScore > cs2Score -> EngineType.MAJIRO
            cs2Score > majiroScore -> EngineType.CATSYSTEM2
            else -> EngineType.CATSYSTEM2
        }
        return EngineDetection(engine, majiroScore, cs2Score)
    }

    private fun hasArcMagic(file: SafDocument): Boolean {
        val bytes = runCatching { reader.readBytes(file, 16) }.getOrNull() ?: return false
        if (bytes.size < 16 || bytes[15] != 0.toByte()) return false
        val header = bytes.copyOfRange(0, 15).toString(Charsets.US_ASCII)
        return header == "MajiroArcV1.000" || header == "MajiroArcV2.000" || header == "MajiroArcV3.000"
    }

    private fun hasMjoMagic(file: SafDocument): Boolean {
        val bytes = runCatching { reader.readBytes(file, 10) }.getOrNull() ?: return false
        val header = bytes.toString(Charsets.US_ASCII)
        return header == "MajiroObjX" || header == "MajiroObjV"
    }

    private fun hasStartupNodes(file: SafDocument): Boolean {
        val text = runCatching { reader.readText(file) }.getOrNull() ?: return false
        return Regex("<(?:APP|SCRIPT)(?:\\s|>)", RegexOption.IGNORE_CASE).containsMatchIn(text)
    }

    companion object {
        fun isCs2Startup(name: String): Boolean =
            name == "kcs.int" || name == "main.kcs" || name == "start.kcs" || name == "startup.kcs"
    }
}
