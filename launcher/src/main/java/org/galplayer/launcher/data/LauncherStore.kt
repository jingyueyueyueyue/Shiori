package org.galplayer.launcher.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.galplayer.launcher.model.CoverOrigin
import org.galplayer.launcher.model.CoverPriority
import org.galplayer.launcher.model.EngineType
import org.galplayer.launcher.model.GameEntry
import org.galplayer.launcher.model.GlobalSettings
import org.galplayer.launcher.model.PerGameSettings
import org.galplayer.launcher.model.ScanRoot
import org.galplayer.launcher.model.TextMode
import org.galplayer.launcher.model.ThemeMode
import org.json.JSONArray
import org.json.JSONObject

class LauncherStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val gamesState = MutableStateFlow(readGames())
    private val rootsState = MutableStateFlow(readRoots())
    private val settingsState = MutableStateFlow(readSettings())

    val games: StateFlow<List<GameEntry>> = gamesState.asStateFlow()
    val roots: StateFlow<List<ScanRoot>> = rootsState.asStateFlow()
    val settings: StateFlow<GlobalSettings> = settingsState.asStateFlow()

    init {
        if (preferences.getInt(KEY_SCHEMA, 0) != SCHEMA_VERSION) {
            preferences.edit().putInt(KEY_SCHEMA, SCHEMA_VERSION).apply()
        }
    }

    @Synchronized
    fun putRoots(values: List<ScanRoot>) {
        rootsState.value = values.distinctBy { it.uri }.sortedBy { it.addedAt }
        preferences.edit().putString(KEY_ROOTS, JSONArray().apply {
            rootsState.value.forEach { root ->
                put(JSONObject().put("uri", root.uri).put("recursive", root.recursive).put("addedAt", root.addedAt))
            }
        }.toString()).apply()
    }

    @Synchronized
    fun addRoot(root: ScanRoot) = putRoots(rootsState.value.filterNot { it.uri == root.uri } + root)

    @Synchronized
    fun removeRoot(uri: String) = putRoots(rootsState.value.filterNot { it.uri == uri })

    @Synchronized
    fun putGames(values: List<GameEntry>) {
        gamesState.value = values.distinctBy { it.id }.sortedWith(
            compareBy<GameEntry> { it.displayTitle.lowercase() }.thenBy { it.id },
        )
        preferences.edit().putString(KEY_GAMES, JSONArray().apply {
            gamesState.value.forEach { put(it.toJson()) }
        }.toString()).apply()
    }

    @Synchronized
    fun upsertGames(values: Collection<GameEntry>) {
        if (values.isEmpty()) return
        val merged = gamesState.value.associateBy { it.id }.toMutableMap()
        values.forEach { game ->
            val previous = merged[game.id]
            merged[game.id] = if (previous == null) game else game.copy(
                title = previous.settings.customTitle?.let { game.title } ?: game.title,
                coverPath = previous.coverPath,
                iconPath = previous.iconPath,
                coverSensitive = previous.coverSensitive,
                coverOrigin = previous.coverOrigin,
                lastPlayed = previous.lastPlayed,
                addedAt = previous.addedAt,
                settings = previous.settings,
            )
        }
        putGames(merged.values.toList())
    }

    @Synchronized
    fun updateGame(id: String, transform: (GameEntry) -> GameEntry) {
        putGames(gamesState.value.map { if (it.id == id) transform(it) else it })
    }

    @Synchronized
    fun removeGame(id: String) {
        putGames(gamesState.value.filterNot { it.id == id })
    }

    @Synchronized
    fun markUnavailableExcept(rootUris: Set<String>, foundIds: Set<String>) {
        putGames(gamesState.value.map { game ->
            if (game.rootUri in rootUris && game.id !in foundIds) game.copy(available = false) else game
        })
    }

    @Synchronized
    fun updateSettings(transform: (GlobalSettings) -> GlobalSettings) {
        val updated = transform(settingsState.value).let {
            if (it.textMode == TextMode.FOLLOW_GLOBAL) it.copy(textMode = TextMode.AUTO) else it
        }
        settingsState.value = updated
        preferences.edit().putString(KEY_SETTINGS, updated.toJson().toString()).apply()
    }

    private fun readGames(): List<GameEntry> = readArray(KEY_GAMES).mapNotNull { item ->
        runCatching {
            val settings = item.optJSONObject("settings") ?: JSONObject()
            GameEntry(
                id = item.getString("id"),
                treeUri = item.getString("treeUri"),
                documentId = item.getString("documentId"),
                rootUri = item.getString("rootUri"),
                title = item.getString("title"),
                detectedEngine = enumValue(item.getString("engine"), EngineType.MAJIRO),
                coverPath = item.optionalString("coverPath"),
                iconPath = item.optionalString("iconPath"),
                coverSensitive = item.optBoolean("coverSensitive"),
                coverOrigin = enumValue(item.optString("coverOrigin"),
                    if (item.optionalString("coverPath") != null) CoverOrigin.MANUAL else CoverOrigin.NONE),
                available = item.optBoolean("available", true),
                lastPlayed = item.optLong("lastPlayed"),
                addedAt = item.optLong("addedAt", System.currentTimeMillis()),
                settings = PerGameSettings(
                    customTitle = settings.optionalString("customTitle"),
                    textMode = enumValue(settings.optString("textMode"), TextMode.FOLLOW_GLOBAL),
                    engineOverride = settings.optionalString("engineOverride")?.let { enumValue(it, EngineType.MAJIRO) },
                    touchKeys = settings.optionalBoolean("touchKeys"),
                    highQualityFont = settings.optionalBoolean("highQualityFont"),
                    hudEnabled = settings.optionalBoolean("hudEnabled"),
                    isolated = settings.optBoolean("isolated"),
                ),
            )
        }.getOrNull()
    }

    private fun readRoots(): List<ScanRoot> = readArray(KEY_ROOTS).mapNotNull { item ->
        runCatching {
            ScanRoot(item.getString("uri"), item.optBoolean("recursive", true), item.optLong("addedAt"))
        }.getOrNull()
    }

    private fun readSettings(): GlobalSettings {
        val item = runCatching { JSONObject(preferences.getString(KEY_SETTINGS, "{}") ?: "{}") }.getOrElse { JSONObject() }
        return GlobalSettings(
            themeMode = enumValue(item.optString("themeMode"), ThemeMode.SYSTEM),
            posterMode = item.optBoolean("posterMode", true),
            showRecent = item.optBoolean("showRecent", true),
            alwaysBottomNavigation = item.optBoolean("alwaysBottomNavigation"),
            autoCover = item.optBoolean("autoCover"),
            coverPriority = enumValue(item.optString("coverPriority"), CoverPriority.BANGUMI),
            adultProtection = item.optBoolean("adultProtection", true),
            textMode = enumValue(item.optString("textMode"), TextMode.AUTO),
            touchKeys = item.optBoolean("touchKeys"),
            highQualityFont = item.optBoolean("highQualityFont"),
            cs2Hud = item.optBoolean("cs2Hud"),
            advanced = item.optBoolean("advanced"),
        )
    }

    private fun readArray(key: String): List<JSONObject> {
        val array = runCatching { JSONArray(preferences.getString(key, "[]") ?: "[]") }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until array.length()) array.optJSONObject(index)?.let(::add)
        }
    }

    private fun GameEntry.toJson() = JSONObject()
        .put("id", id)
        .put("treeUri", treeUri)
        .put("documentId", documentId)
        .put("rootUri", rootUri)
        .put("title", title)
        .put("engine", detectedEngine.id)
        .put("coverPath", coverPath)
        .put("iconPath", iconPath)
        .put("coverSensitive", coverSensitive)
        .put("coverOrigin", coverOrigin.id)
        .put("available", available)
        .put("lastPlayed", lastPlayed)
        .put("addedAt", addedAt)
        .put("settings", JSONObject()
            .put("customTitle", settings.customTitle)
            .put("textMode", settings.textMode.id)
            .put("engineOverride", settings.engineOverride?.id)
            .putOptionalBoolean("touchKeys", settings.touchKeys)
            .putOptionalBoolean("highQualityFont", settings.highQualityFont)
            .putOptionalBoolean("hudEnabled", settings.hudEnabled)
            .put("isolated", settings.isolated))

    private fun GlobalSettings.toJson() = JSONObject()
        .put("themeMode", themeMode.id)
        .put("posterMode", posterMode)
        .put("showRecent", showRecent)
        .put("alwaysBottomNavigation", alwaysBottomNavigation)
        .put("autoCover", autoCover)
        .put("coverPriority", coverPriority.id)
        .put("adultProtection", adultProtection)
        .put("textMode", textMode.id)
        .put("touchKeys", touchKeys)
        .put("highQualityFont", highQualityFont)
        .put("cs2Hud", cs2Hud)
        .put("advanced", advanced)

    private fun JSONObject.putOptionalBoolean(name: String, value: Boolean?): JSONObject =
        if (value == null) put(name, JSONObject.NULL) else put(name, value)

    private fun JSONObject.optionalBoolean(name: String): Boolean? =
        if (!has(name) || isNull(name)) null else optBoolean(name)

    private fun JSONObject.optionalString(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    private inline fun <reified T : Enum<T>> enumValue(value: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value || (it is EngineType && it.id == value) ||
            (it is TextMode && it.id == value) || (it is ThemeMode && it.id == value) ||
            (it is CoverPriority && it.id == value) || (it is CoverOrigin && it.id == value) } ?: fallback

    private companion object {
        const val PREF_NAME = "unified-launcher"
        const val SCHEMA_VERSION = 1
        const val KEY_SCHEMA = "schema"
        const val KEY_ROOTS = "roots"
        const val KEY_GAMES = "games"
        const val KEY_SETTINGS = "settings"
    }
}
