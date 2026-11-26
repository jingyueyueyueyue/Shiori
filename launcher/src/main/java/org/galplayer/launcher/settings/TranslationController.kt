package org.galplayer.launcher.settings

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.galplayer.launcher.model.TextMode
import org.json.JSONObject
import org.majiro.player.TranslationSettings
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class TranslationSnapshot(
    val url: String = "",
    val key: String = "",
    val model: String = "",
    val contextLines: Int = 20,
    val models: List<String> = emptyList(),
)

class TranslationController(context: Context) {
    private val appContext = context.applicationContext

    fun load(): TranslationSnapshot {
        val settings = TranslationSettings.load(appContext)
        return TranslationSnapshot(settings.url, settings.key, settings.model, settings.context)
    }

    suspend fun save(mode: TextMode, snapshot: TranslationSnapshot) = withContext(Dispatchers.IO) {
        TranslationSettings.save(appContext, mode.id, snapshot.url, snapshot.key, snapshot.model, snapshot.contextLines)
    }

    suspend fun fetchModels(url: String, key: String): List<String> = withContext(Dispatchers.IO) {
        val endpoint = TranslationSettings.normalizeUrl(url) + "/models"
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "Shiori/0.1.2 (Android)")
            connection.setRequestProperty("Accept", "application/json")
            if (key.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer ${key.trim()}")
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val data = JSONObject(text).optJSONArray("data")
            buildList {
                if (data != null) for (index in 0 until data.length()) {
                    data.optJSONObject(index)?.optString("id")?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }.distinct().sorted()
        } finally {
            connection.disconnect()
        }
    }
}
