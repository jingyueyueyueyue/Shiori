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

enum class ThemePalette(
    val id: String,
    val label: String,
    val lightPrimary: Long,
    val darkPrimary: Long,
    val secondary: Long,
    val tertiary: Long,
) {
    CLASSIC_BLUE("classic_blue", "经典蓝", 0xFF2589FF, 0xFF8AC7FF, 0xFF8872D9, 0xFF4DAE9A),
    INDIGO("indigo", "靛青", 0xFF4A56A6, 0xFFB4BCFF, 0xFF8B79C9, 0xFF4D9FAE),
    PURPLE("purple", "紫罗兰", 0xFF7351A6, 0xFFD2B4FF, 0xFF4D8FC4, 0xFFB879A9),
    SAKURA("sakura", "樱花粉", 0xFFAD345F, 0xFFFFB1C8, 0xFF8B6FB2, 0xFF4E9B93),
    ROSE("rose", "玫瑰红", 0xFFB93A48, 0xFFFFB3BA, 0xFF8C6E9F, 0xFFB48A52),
    ORANGE("orange", "落日橙", 0xFFB54B22, 0xFFFFB59A, 0xFF527FA5, 0xFF7D9A52),
    AMBER("amber", "琥珀金", 0xFF8A6200, 0xFFFFD58A, 0xFF5D8BA0, 0xFF9A7A52),
    GREEN("green", "森林绿", 0xFF2E7D32, 0xFFA8DAB0, 0xFF567FAF, 0xFF9B8750),
    TEAL("teal", "青瓷绿", 0xFF00796B, 0xFF8FD8CC, 0xFF6D83B5, 0xFF9A7BAA),
    CYAN("cyan", "晴空青", 0xFF007C91, 0xFF91DDEA, 0xFF777FC0, 0xFF4D9B7B),
    SLATE("slate", "石墨灰", 0xFF485563, 0xFFC2CBD6, 0xFF71809A, 0xFF8C7C70),
    MOCHA("mocha", "摩卡棕", 0xFF795548, 0xFFD7BBB0, 0xFF6D8292, 0xFF8A7B9E),
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
    val themePalette: ThemePalette = ThemePalette.CLASSIC_BLUE,
    val posterMode: Boolean = true,
    val showRecent: Boolean = true,
    val alwaysBottomNavigation: Boolean = false,
    val autoCover: Boolean = false,
    val coverPriority: CoverPriority = CoverPriority.BANGUMI,
    val adultProtection: Boolean = true,
    val textMode: TextMode = TextMode.AUTO,
    val touchKeys: Boolean = false,
    val highQualityFont: Boolean = false,
    val performanceHud: Boolean = false,
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
