package org.galplayer.launcher.model

enum class EngineType(val id: String, val displayName: String) {
    MAJIRO("majiro", "Majiro"),
    CATSYSTEM2("cs2", "CatSystem2"),
}

enum class TextMode(val id: String, val label: String) {
    FOLLOW_GLOBAL("follow", "跟随全局"),
    AUTO("auto", "自动"),
    ORIGINAL("original", "日语原文"),
    LOCAL("local", "本地汉化"),
    ONLINE("online", "在线翻译（仅 Majiro）"),
}

enum class ThemeMode(val id: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色"),
}

enum class CoverPriority(val id: String, val label: String) {
    BANGUMI("bangumi", "Bangumi 优先"),
    VNDB("vndb", "VNDB 优先"),
}

enum class CoverOrigin(val id: String, val label: String) {
    NONE("none", "无封面"),
    LOCAL("local", "游戏内提取"),
    AUTOMATIC("automatic", "自动匹配"),
    MANUAL("manual", "手动替换"),
}

data class PerGameSettings(
    val customTitle: String? = null,
    val textMode: TextMode = TextMode.FOLLOW_GLOBAL,
    val engineOverride: EngineType? = null,
    val touchKeys: Boolean? = null,
    val highQualityFont: Boolean? = null,
    val hudEnabled: Boolean? = null,
    val isolated: Boolean = false,
)

data class GameEntry(
    val id: String,
    val treeUri: String,
    val documentId: String,
    val rootUri: String,
    val title: String,
    val detectedEngine: EngineType,
    val coverPath: String? = null,
    val iconPath: String? = null,
    val coverSensitive: Boolean = false,
    val coverOrigin: CoverOrigin = if (coverPath != null) CoverOrigin.MANUAL else CoverOrigin.NONE,
    val available: Boolean = true,
    val lastPlayed: Long = 0L,
    val addedAt: Long = System.currentTimeMillis(),
    val settings: PerGameSettings = PerGameSettings(),
) {
    val engine: EngineType get() = settings.engineOverride ?: detectedEngine
    val displayTitle: String get() = settings.customTitle?.takeIf { it.isNotBlank() } ?: title
}

data class ScanRoot(
    val uri: String,
    val recursive: Boolean,
    val addedAt: Long = System.currentTimeMillis(),
)

data class GlobalSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val posterMode: Boolean = true,
    val showRecent: Boolean = true,
    val alwaysBottomNavigation: Boolean = false,
    val autoCover: Boolean = false,
    val coverPriority: CoverPriority = CoverPriority.BANGUMI,
    val adultProtection: Boolean = true,
    val textMode: TextMode = TextMode.AUTO,
    val touchKeys: Boolean = false,
    val highQualityFont: Boolean = false,
    val cs2Hud: Boolean = false,
    val advanced: Boolean = false,
)

data class LibrarySnapshot(
    val games: List<GameEntry> = emptyList(),
    val roots: List<ScanRoot> = emptyList(),
    val settings: GlobalSettings = GlobalSettings(),
    val scanning: Boolean = false,
    val coverBatchRunning: Boolean = false,
    val coverBatchProgress: Pair<Int, Int>? = null,
    val message: String? = null,
)

enum class ScanMode {
    RECURSIVE,
    SINGLE,
}

sealed interface ScanOutcome {
    data class Completed(val added: Int, val updated: Int, val unreadable: Int) : ScanOutcome
    data class SingleGameMissing(val uri: String) : ScanOutcome
    data class Failed(val detail: String) : ScanOutcome
}

sealed interface LaunchOutcome {
    data object Dispatched : LaunchOutcome
    data object StoragePermissionRequired : LaunchOutcome
    data class Failed(val detail: String) : LaunchOutcome
}
