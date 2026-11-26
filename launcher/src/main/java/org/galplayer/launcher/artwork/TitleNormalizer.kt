package org.galplayer.launcher.artwork

object TitleNormalizer {
    private val bracketNoise = Regex("\\[(?:\\d{6,8}|[^\\]]{1,80})\\]")
    private val packageNoise = Regex("(?i)\\((?:files?|iso(?:\\+[^)]*)?|[^)]*\\+(?:files?|jpg|png|rr)[^)]*)\\)")
    private val suffixNoise = Regex("(?i)(?:[_\\-\\s]*(?:steam版|steam|chs|chinese|_cn|汉化版?|中文版|简体中文版))+$")
    private val archiveSuffix = Regex("(?i)\\.(?:7z|zip|rar|iso|mds)$")
    private val editionNoise = Regex("(?i)(?:[_\\-\\s]*(?:初回特典同梱|特典同梱|初回限定版|限定版|通常版|dl版|download版|完整版|full version|v\\d+(?:\\.\\d+)*))+$")
    private val languagePrefix = Regex("^(?:\\$[0-9A-Fa-f-]+\\$)+")
    private val productId = Regex("(?i)\\b(?:RJ|VJ|BJ)\\d{6,8}\\b")

    fun normalize(value: String): String {
        var text = value.trim().replace(Regex("\\^\\d+$"), "").replace('^', ' ')
        text = languagePrefix.replace(text, "")
        text = bracketNoise.replace(text, " ")
        text = packageNoise.replace(text, " ")
        text = archiveSuffix.replace(text, "")
        text = editionNoise.replace(text, "")
        text = suffixNoise.replace(text, "")
        text = text.replace(Regex("[_]+"), " ")
        text = text.replace(Regex("\\s+"), " ").trim(' ', '-', '–', '—', '.')
        return text.ifBlank { value.substringAfterLast('/').substringAfterLast('\\').trim() }
    }

    fun alternatives(value: String): List<String> {
        val primary = normalize(value)
        val withoutVolume = primary.replace(Regex("(?i)\\s+(?:vol\\.?|volume)\\s*\\d+$"), "").trim()
        return listOf(primary, withoutVolume).filter { it.isNotBlank() }.distinct()
    }

    fun productId(value: String): String? = productId.find(value)?.value?.uppercase()

    fun withoutProductId(value: String): String = productId.replace(value, " ")
}
