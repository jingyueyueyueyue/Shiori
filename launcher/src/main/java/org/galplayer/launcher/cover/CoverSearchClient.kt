package org.galplayer.launcher.cover

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.galplayer.launcher.artwork.TitleNormalizer
import org.galplayer.launcher.model.CoverPriority
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

enum class CoverSource(val label: String) {
    BANGUMI("Bangumi"),
    VNDB("VNDB"),
    DLSITE("DLsite"),
}

data class CoverCandidate(
    val source: CoverSource,
    val title: String,
    val url: String,
    val thumbnailUrl: String = url,
    val width: Int = 0,
    val height: Int = 0,
    val sexual: Double = 0.0,
    val violence: Double = 0.0,
    val sensitive: Boolean = sexual >= 1.0 || violence >= 1.0 || source == CoverSource.DLSITE,
    val matchScore: Float = 0f,
    val aliases: List<String> = emptyList(),
)

data class CoverSearchReport(
    val results: List<CoverCandidate>,
    val failures: List<String>,
)

class CoverSearchClient {
    suspend fun search(query: String, priority: CoverPriority): List<CoverCandidate> =
        searchDetailed(query, priority).results

    suspend fun searchDetailed(query: String, priority: CoverPriority): CoverSearchReport = coroutineScope {
        val cleanQuery = TitleNormalizer.normalize(TitleNormalizer.withoutProductId(query))
        val bangumi = async { attempt(CoverSource.BANGUMI) { searchBangumi(cleanQuery) } }
        val vndb = async { attempt(CoverSource.VNDB) { searchVndb(cleanQuery) } }
        val dlsite = async {
            TitleNormalizer.productId(query)?.let { id -> attempt(CoverSource.DLSITE) { searchDlsite(id) } }
                ?: SourceOutcome(emptyList())
        }
        val outcomes = listOf(bangumi.await(), vndb.await(), dlsite.await())
        val titleAlternatives = TitleNormalizer.alternatives(TitleNormalizer.withoutProductId(query))
        val productId = TitleNormalizer.productId(query)
        val scored = outcomes.flatMap { it.results }.map { candidate ->
            val score = if (candidate.source == CoverSource.DLSITE && productId != null) {
                1f
            } else {
                titleAlternatives.maxOfOrNull { alternative ->
                    similarity(TitleNormalizer.normalize(alternative), candidate)
                } ?: 0f
            }
            candidate.copy(matchScore = score)
        }.distinctBy { it.url }
        CoverSearchReport(
            results = scored.sortedWith(
                compareByDescending<CoverCandidate> {
                    if (priority == CoverPriority.BANGUMI) it.source == CoverSource.BANGUMI else it.source == CoverSource.VNDB
                }.thenByDescending { it.matchScore }
                    .thenByDescending { it.height > it.width && it.height > 0 }
                    .thenBy { it.title },
            ),
            failures = outcomes.mapNotNull { it.failure },
        )
    }

    private data class SourceOutcome(val results: List<CoverCandidate>, val failure: String? = null)

    private suspend fun attempt(source: CoverSource, block: suspend () -> List<CoverCandidate>): SourceOutcome = try {
        SourceOutcome(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        android.util.Log.w(TAG, "${source.label} cover search failed", error)
        SourceOutcome(emptyList(), "${source.label}：${error.message ?: error.javaClass.simpleName}")
    }

    suspend fun bestAutomatic(query: String, priority: CoverPriority, adultProtection: Boolean): CoverCandidate? {
        val report = searchDetailed(query, priority)
        if (report.results.isEmpty() && report.failures.isNotEmpty()) {
            throw IOException(report.failures.joinToString("；"))
        }
        return report.results.filter { it.matchScore >= AUTO_MATCH_THRESHOLD }.sortedWith(
            compareByDescending<CoverCandidate> { it.matchScore }
                .thenBy { candidate ->
                    when (priority) {
                        CoverPriority.BANGUMI -> candidate.source != CoverSource.BANGUMI
                        CoverPriority.VNDB -> candidate.source != CoverSource.VNDB
                    }
                }
                .thenBy { adultProtection && it.sensitive }
                .thenByDescending { it.height > it.width && it.height > 0 },
        ).firstOrNull()
    }

    suspend fun download(url: String): ByteArray = Http.getBytes(url, MAX_COVER_BYTES)

    private suspend fun searchBangumi(query: String): List<CoverCandidate> {
        if (query.isBlank()) return emptyList()
        val body = JSONObject()
            .put("keyword", query)
            .put("filter", JSONObject().put("type", JSONArray().put(4)))
            .put("limit", 8)
            .toString()
        val response = JSONObject(Http.post(BANGUMI_URL, body))
        return response.optJSONArray("data")?.let { rows ->
            buildList {
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    val images = row.optJSONObject("images") ?: continue
                    val url = imageUrl(images.optString("large").ifBlank { images.optString("common") }) ?: continue
                    val thumbnail = imageUrl(images.optString("medium").ifBlank { images.optString("small") }) ?: url
                    val original = row.optString("name")
                    val translated = row.optString("name_cn")
                    val title = translated.ifBlank { original }
                    add(CoverCandidate(
                        CoverSource.BANGUMI, title, url, thumbnail,
                        aliases = listOf(original, translated).filter { it.isNotBlank() },
                    ))
                }
            }
        }.orEmpty()
    }

    private suspend fun searchVndb(query: String): List<CoverCandidate> {
        if (query.isBlank()) return emptyList()
        val body = JSONObject()
            .put("filters", JSONArray().put("search").put("=").put(query))
            .put("fields", "id, title, alttitle, titles{title, latin, lang}, image.url, image.thumbnail, image.dims, image.sexual, image.violence")
            .put("results", 8)
            .toString()
        val response = JSONObject(Http.post(VNDB_URL, body))
        return response.optJSONArray("results")?.let { rows ->
            buildList {
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    val image = row.optJSONObject("image") ?: continue
                    val url = imageUrl(image.optString("url")) ?: continue
                    val dimensions = image.optJSONArray("dims")
                    val alternate = row.optString("alttitle")
                    val title = row.optString("title") + if (alternate.isNotBlank()) " $alternate" else ""
                    val aliases = buildList {
                        add(row.optString("title"))
                        add(alternate)
                        row.optJSONArray("titles")?.let { titles ->
                            for (titleIndex in 0 until titles.length()) {
                                val entry = titles.optJSONObject(titleIndex) ?: continue
                                add(entry.optString("title"))
                                add(entry.optString("latin"))
                            }
                        }
                    }.filter { it.isNotBlank() }
                    add(CoverCandidate(
                        source = CoverSource.VNDB,
                        title = title,
                        url = url,
                        thumbnailUrl = imageUrl(image.optString("thumbnail")) ?: url,
                        width = dimensions?.optInt(0) ?: 0,
                        height = dimensions?.optInt(1) ?: 0,
                        sexual = image.optDouble("sexual"),
                        violence = image.optDouble("violence"),
                        aliases = aliases,
                    ))
                }
            }
        }.orEmpty()
    }

    private suspend fun searchDlsite(productId: String): List<CoverCandidate> {
        for (section in listOf("maniax", "pro", "home")) {
            val parsed = ignoreFailures {
                val raw = Http.get("https://www.dlsite.com/$section/api/=/product.json?workno=$productId&locale=ja_JP")
                val json = JSONTokener(raw).nextValue()
                val row = when (json) {
                    is JSONArray -> json.optJSONObject(0)
                    is JSONObject -> json.optJSONObject(productId) ?: json
                    else -> null
                } ?: return@ignoreFailures null
                val image = sequenceOf("image_main_url", "work_image").map { row.optString(it) }
                    .firstOrNull { it.isNotBlank() } ?: return@ignoreFailures null
                val url = imageUrl(image, "https://www.dlsite.com") ?: return@ignoreFailures null
                val title = sequenceOf("work_name", "title", "maker_name").map { row.optString(it) }
                    .firstOrNull { it.isNotBlank() }.orEmpty()
                CoverCandidate(CoverSource.DLSITE, title, url)
            }
            if (parsed != null) return listOf(parsed)
        }
        return emptyList()
    }

    private fun imageUrl(value: String, base: String = ""): String? {
        val trimmed = value.trim()
        return when {
            trimmed.startsWith("https://", true) || trimmed.startsWith("http://", true) -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") && base.isNotBlank() -> base + trimmed
            trimmed.isNotBlank() && trimmed.contains('.') && !trimmed.contains(' ') -> "https://$trimmed"
            else -> null
        }
    }

    private fun similarity(query: String, candidate: CoverCandidate): Float {
        fun key(value: String) = value.lowercase().filter { it.isLetterOrDigit() }
        val left = key(query)
        val rightCandidates = (candidate.aliases + candidate.title).flatMap { alias ->
            TitleNormalizer.alternatives(alias).map(::key) + key(alias)
        }.filter { it.isNotBlank() }.distinct()
        val best = rightCandidates.maxOfOrNull { right ->
            when {
                left.isBlank() || right.isBlank() -> 0f
                left == right -> 1f
                left.contains(right) || right.contains(left) -> minOf(left.length, right.length).toFloat() /
                    maxOf(left.length, right.length).toFloat() * 0.2f + 0.68f
                else -> {
                    val leftTokens = query.lowercase().split(Regex("\\s+")).filter { it.length > 1 }.toSet()
                    val rightTokens = (candidate.aliases + candidate.title).flatMap { alias ->
                        alias.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
                    }.toSet()
                    val union = leftTokens + rightTokens
                    if (union.isEmpty()) 0f else leftTokens.intersect(rightTokens).size.toFloat() / union.size * 0.8f
                }
            }
        } ?: 0f
        return best
    }

    private suspend fun <T> ignoreFailures(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private object Http {
        suspend fun get(url: String): String = request(url, null, TEXT_LIMIT).toString(Charsets.UTF_8)
        suspend fun post(url: String, body: String): String = request(url, body, TEXT_LIMIT).toString(Charsets.UTF_8)
        suspend fun getBytes(url: String, maximumBytes: Int): ByteArray = request(url, null, maximumBytes)

        private suspend fun request(url: String, body: String?, maximumBytes: Int): ByteArray = withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = if (body == null) "GET" else "POST"
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", "application/json, image/*, */*")
                connection.setRequestProperty("Accept-Language", "ja-JP,zh-CN;q=0.9,en;q=0.8")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                val stream = connection.inputStream ?: throw IOException("空响应")
                stream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer, 0, minOf(buffer.size, maximumBytes - output.size()))
                        if (count < 0) break
                        if (count == 0) {
                            if (output.size() == maximumBytes) break
                            continue
                        }
                        if (count > maximumBytes - output.size()) throw IOException("响应过大")
                        output.write(buffer, 0, count)
                        if (output.size() == maximumBytes) break
                    }
                    output.toByteArray()
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private companion object {
        const val BANGUMI_URL = "https://api.bgm.tv/v0/search/subjects"
        const val VNDB_URL = "https://api.vndb.org/kana/vn"
        const val USER_AGENT = "Shiori/0.1.2 (https://github.com/jingyueyueyueyue/Shiori)"
        const val TAG = "ShioriCover"
        const val TEXT_LIMIT = 4 * 1024 * 1024
        const val MAX_COVER_BYTES = 32 * 1024 * 1024
        const val AUTO_MATCH_THRESHOLD = 0.72f
    }
}
