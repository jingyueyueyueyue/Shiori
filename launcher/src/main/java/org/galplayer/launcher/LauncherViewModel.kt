package org.galplayer.launcher

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.galplayer.launcher.artwork.ArtworkCache
import org.galplayer.launcher.artwork.TitleNormalizer
import org.galplayer.launcher.cover.CoverCandidate
import org.galplayer.launcher.cover.CoverSearchClient
import org.galplayer.launcher.model.CoverOrigin
import org.galplayer.launcher.data.LauncherStore
import org.galplayer.launcher.diagnostics.DiagnosticsExporter
import org.galplayer.launcher.launch.GameLauncher
import org.galplayer.launcher.model.GameEntry
import org.galplayer.launcher.model.GlobalSettings
import org.galplayer.launcher.model.LaunchOutcome
import org.galplayer.launcher.model.LibrarySnapshot
import org.galplayer.launcher.model.PerGameSettings
import org.galplayer.launcher.model.ScanMode
import org.galplayer.launcher.model.ScanRoot
import org.galplayer.launcher.model.TextMode
import org.galplayer.launcher.scan.GameScanner
import org.galplayer.launcher.scan.ScanBatch
import org.galplayer.launcher.settings.TranslationController
import org.galplayer.launcher.settings.TranslationSnapshot
import org.majiro.player.AppearanceSettings
import java.util.concurrent.ConcurrentHashMap

data class OperationState(
    val scanning: Boolean = false,
    val coverBatchRunning: Boolean = false,
    val coverBatchProgress: Pair<Int, Int>? = null,
    val message: String? = null,
)

data class CoverSearchState(
    val gameId: String? = null,
    val query: String = "",
    val loading: Boolean = false,
    val results: List<CoverCandidate> = emptyList(),
    val error: String? = null,
)

class LauncherViewModel(application: Application) : AndroidViewModel(application) {
    private val store = LauncherStore(application)
    private val scanner = GameScanner(application)
    private val artwork = ArtworkCache(application)
    private val covers = CoverSearchClient()
    private val gameLauncher = GameLauncher(application, store)
    private val translationController = TranslationController(application)
    private val diagnostics = DiagnosticsExporter(application)
    private val operation = MutableStateFlow(OperationState())
    private val coverSearchMutable = MutableStateFlow(CoverSearchState())
    private val pendingSingleUri = MutableStateFlow<String?>(null)
    private val translationMutable = MutableStateFlow(translationController.load())
    private var coverSearchJob: Job? = null
    private var coverBatchJob: Job? = null
    private val coverWriteLocks = ConcurrentHashMap<String, Any>()

    private enum class CoverFetchOutcome { SAVED, NO_MATCH, FAILED, UNCHANGED }

    val snapshot: StateFlow<LibrarySnapshot> = combine(
        store.games, store.roots, store.settings, operation,
    ) { games, roots, settings, state ->
        LibrarySnapshot(games, roots, settings, state.scanning, state.coverBatchRunning, state.coverBatchProgress, state.message)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        LibrarySnapshot(store.games.value, store.roots.value, store.settings.value),
    )
    val coverSearch: StateFlow<CoverSearchState> = coverSearchMutable.asStateFlow()
    val singleGameMissing: StateFlow<String?> = pendingSingleUri.asStateFlow()
    val translation: StateFlow<TranslationSnapshot> = translationMutable.asStateFlow()

    init {
        if (store.roots.value.isNotEmpty()) refresh(refreshAutomaticCovers = false)
    }

    fun scanFolder(uri: String, mode: ScanMode) {
        if (operation.value.scanning) return
        viewModelScope.launch {
            setOperation { it.copy(scanning = true, message = null) }
            try {
                val root = ScanRoot(uri, mode == ScanMode.RECURSIVE)
                val batch = scanner.scan(root)
                if (mode == ScanMode.SINGLE && batch.games.isEmpty()) {
                    pendingSingleUri.value = uri
                    return@launch
                }
                store.addRoot(root)
                val added = mergeBatch(batch)
                if (store.settings.value.autoCover) {
                    batch.games.forEach { game ->
                        store.games.value.firstOrNull {
                            it.id == game.id && it.coverOrigin != CoverOrigin.MANUAL &&
                                (it.coverPath == null || it.coverOrigin == CoverOrigin.LOCAL || it.coverOrigin == CoverOrigin.AUTOMATIC)
                        }?.let { fetchAutomatic(it) }
                    }
                }
                val message = buildString {
                    append("发现 ${batch.games.size} 个游戏，新增 $added 个")
                    if (batch.unreadable > 0) append("，${batch.unreadable} 个目录不可读")
                }
                setOperation { it.copy(message = message) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                setOperation { it.copy(message = error.message ?: "扫描失败") }
            } finally {
                setOperation { it.copy(scanning = false) }
            }
        }
    }

    fun refresh(refreshAutomaticCovers: Boolean = true) {
        if (operation.value.scanning) return
        val roots = store.roots.value
        if (roots.isEmpty()) {
            setOperation { it.copy(message = "请先添加游戏目录") }
            return
        }
        viewModelScope.launch {
            setOperation { it.copy(scanning = true, message = null) }
            var added = 0
            var unreadable = 0
            var failed = 0
            val foundIds = mutableSetOf<String>()
            try {
                roots.forEach { root ->
                    try {
                        val batch = scanner.scan(root)
                        added += mergeBatch(batch)
                        unreadable += batch.unreadable
                        foundIds += batch.games.map { it.id }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failed++
                    }
                }
                store.markUnavailableExcept(roots.map { it.uri }.toSet(), foundIds)
                val coverOutcomes = if (refreshAutomaticCovers && store.settings.value.autoCover) {
                    automaticCoverTargets().map { fetchAutomatic(it) }
                } else {
                    emptyList()
                }
                setOperation {
                    it.copy(message = "刷新完成，新增 $added 个；$unreadable 个目录不可读，$failed 个根目录扫描失败" +
                        coverOutcomeSummary(coverOutcomes))
                }
            } finally {
                setOperation { it.copy(scanning = false) }
            }
        }
    }

    fun launchGame(game: GameEntry) {
        when (val result = gameLauncher.launch(game, store.settings.value)) {
            LaunchOutcome.Dispatched -> Unit
            LaunchOutcome.StoragePermissionRequired -> setOperation { it.copy(message = "CS2 需要文件访问权限，请在设置中授权后重试") }
            is LaunchOutcome.Failed -> setOperation { it.copy(message = "启动失败：${result.detail}") }
        }
    }

    fun hasStorageAccess(): Boolean = gameLauncher.hasStorageAccess()
    fun storagePermissionIntent() = gameLauncher.storagePermissionIntent()

    fun updateSettings(transform: (GlobalSettings) -> GlobalSettings) {
        viewModelScope.launch(Dispatchers.IO) {
            val previous = store.settings.value
            store.updateSettings(transform)
            val updated = store.settings.value
            runCatching {
                if (updated.themeMode != previous.themeMode) {
                    AppearanceSettings.set(getApplication(), updated.themeMode.id)
                }
                if (updated.textMode != previous.textMode) {
                    val current = translationMutable.value
                    translationController.save(updated.textMode, current)
                }
            }.onFailure { error -> setOperation { it.copy(message = error.message ?: "设置保存失败") } }
        }
    }

    fun updateGameSettings(game: GameEntry, settings: PerGameSettings) {
        store.updateGame(game.id) { it.copy(settings = settings) }
        setOperation { it.copy(message = "已保存单游戏设置") }
    }

    fun removeGame(game: GameEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            store.removeGame(game.id)
            artwork.clearFor(game.id)
            setOperation { it.copy(message = "已移出游戏库，游戏文件与存档未改动") }
        }
    }

    fun removeRoot(uri: String) {
        store.removeRoot(uri)
        setOperation { it.copy(message = "已移除扫描根目录，已添加游戏与设备文件均保留") }
    }

    fun importCover(game: GameEntry, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            coverWrite(game.id) {
                val path = artwork.importCover(game.id, uri)
                if (path != null) {
                    store.updateGame(game.id) {
                        it.copy(coverPath = path, coverSensitive = false, coverOrigin = CoverOrigin.MANUAL)
                    }
                    setOperation { it.copy(message = "封面已更新") }
                } else {
                    setOperation { it.copy(message = "无法读取所选图片") }
                }
            }
        }
    }

    fun reextractArtwork(game: GameEntry) = reextractWithOrigin(game, CoverOrigin.MANUAL)

    private fun reextractWithOrigin(game: GameEntry, origin: CoverOrigin) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val metadata = scanner.metadataFor(game)
                coverWrite(game.id) {
                    val extracted = artwork.extractLocal(game.id, metadata)
                    store.updateGame(game.id) {
                        it.copy(
                            title = metadata.title,
                            coverPath = extracted.coverPath,
                            iconPath = extracted.iconPath,
                            coverSensitive = false,
                            coverOrigin = if (extracted.coverPath == null) CoverOrigin.NONE else origin,
                        )
                    }
                }
                setOperation { it.copy(message = "已重新读取本地标题与图像") }
            }.onFailure { error -> setOperation { it.copy(message = error.message ?: "本地取图失败") } }
        }
    }

    fun restoreDefaultArtwork(game: GameEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            artwork.clearFor(game.id)
            store.updateGame(game.id) {
                it.copy(coverPath = null, iconPath = null, coverSensitive = false, coverOrigin = CoverOrigin.NONE)
            }
            reextractWithOrigin(game, CoverOrigin.LOCAL)
        }
    }

    fun openCoverSearch(game: GameEntry) {
        coverSearchJob?.cancel()
        val productId = TitleNormalizer.productId(game.documentId)
        val query = listOfNotNull(game.displayTitle, productId).joinToString(" ")
        coverSearchMutable.value = CoverSearchState(game.id, query)
        searchCovers(query)
    }

    fun closeCoverSearch() {
        coverSearchJob?.cancel()
        coverSearchMutable.value = CoverSearchState()
    }

    fun searchCovers(query: String) {
        if (query.isBlank()) return
        coverSearchJob?.cancel()
        coverSearchJob = viewModelScope.launch(Dispatchers.IO) {
            coverSearchMutable.update { it.copy(query = query, loading = true, error = null) }
            try {
                val report = covers.searchDetailed(query, store.settings.value.coverPriority)
                val error = when {
                    report.results.isEmpty() && report.failures.isNotEmpty() ->
                        "封面源连接失败：${report.failures.joinToString("；")}"
                    report.results.isEmpty() -> "未找到封面"
                    report.failures.isNotEmpty() -> "部分来源不可用：${report.failures.joinToString("；")}"
                    else -> null
                }
                coverSearchMutable.update { it.copy(results = report.results, loading = false, error = error) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                coverSearchMutable.update { it.copy(loading = false, error = error.message ?: "封面搜索失败") }
            }
        }
    }

    fun selectCover(candidate: CoverCandidate) {
        val gameId = coverSearchMutable.value.gameId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            coverSearchMutable.update { it.copy(loading = true, error = null) }
            try {
                val bytes = covers.download(candidate.url)
                coverWrite(gameId) {
                    val path = artwork.saveCover(gameId, bytes)
                    if (path == null) throw IllegalStateException("图片解码失败")
                    store.updateGame(gameId) {
                        it.copy(coverPath = path, coverSensitive = candidate.sensitive, coverOrigin = CoverOrigin.MANUAL)
                    }
                }
                closeCoverSearch()
                setOperation { it.copy(message = "封面已下载并保存") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                coverSearchMutable.update { it.copy(loading = false, error = error.message ?: "封面下载失败") }
            }
        }
    }

    fun refreshCovers() {
        if (coverBatchJob?.isActive == true) return
        val targets = automaticCoverTargets()
        if (targets.isEmpty()) {
            setOperation { it.copy(message = "没有需要在线更新的封面，本地与手动封面保持不变") }
            return
        }
        coverBatchJob = viewModelScope.launch(Dispatchers.IO) {
            setOperation { it.copy(coverBatchRunning = true, coverBatchProgress = 0 to targets.size, message = null) }
            try {
                val outcomes = targets.mapIndexed { index, game ->
                    val outcome = fetchAutomatic(game)
                    setOperation { it.copy(coverBatchProgress = index + 1 to targets.size) }
                    outcome
                }
                setOperation { it.copy(message = "封面处理完成" + coverOutcomeSummary(outcomes)) }
            } finally {
                setOperation { it.copy(coverBatchRunning = false, coverBatchProgress = null) }
            }
        }
    }

    fun cancelCoverBatch() {
        coverBatchJob?.cancel()
    }

    fun clearArtworkCache() {
        viewModelScope.launch(Dispatchers.IO) {
            artwork.clearAll()
            store.putGames(store.games.value.map {
                it.copy(coverPath = null, iconPath = null, coverSensitive = false, coverOrigin = CoverOrigin.NONE)
            })
            setOperation { it.copy(message = "启动器封面与图标缓存已清理") }
        }
    }

    suspend fun artworkCacheSize(): Long = withContext(Dispatchers.IO) { artwork.sizeBytes() }

    fun reloadTranslation() {
        translationMutable.value = translationController.load()
    }

    fun saveTranslation(snapshot: TranslationSnapshot) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                translationController.save(store.settings.value.textMode, snapshot)
                translationMutable.value = snapshot.copy(models = translationMutable.value.models)
                setOperation { it.copy(message = "翻译服务设置已加密保存") }
            }.onFailure { error -> setOperation { it.copy(message = error.message ?: "翻译设置保存失败") } }
        }
    }

    fun fetchTranslationModels(snapshot: TranslationSnapshot) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val models = translationController.fetchModels(snapshot.url, snapshot.key)
                translationMutable.update { it.copy(url = snapshot.url, key = snapshot.key, models = models) }
                setOperation { it.copy(message = "已读取 ${models.size} 个模型") }
            }.onFailure { error -> setOperation { it.copy(message = error.message ?: "模型读取失败") } }
        }
    }

    fun importFont(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                    org.galplayer.launcher.artwork.ImageCodec.readLimited(it, 64 * 1024 * 1024)
                } ?: throw IllegalArgumentException("无法读取字体")
                if (bytes.size < 12) throw IllegalArgumentException("字体文件无效")
                val signature = bytes.copyOf(4).toString(Charsets.US_ASCII)
                val valid = signature == "OTTO" || signature == "ttcf" || signature == "true" ||
                    bytes.copyOf(4).contentEquals(byteArrayOf(0, 1, 0, 0))
                if (!valid) throw IllegalArgumentException("请选择 TTF、OTF 或 TTC 字体")
                val directory = java.io.File(getApplication<Application>().filesDir, "fonts").apply { mkdirs() }
                val destination = java.io.File(directory, "msgothic.ttc")
                val pending = java.io.File(directory, "msgothic.pending")
                pending.writeBytes(bytes)
                if (destination.exists() && !destination.delete()) throw IllegalStateException("无法替换现有字体")
                if (!pending.renameTo(destination)) throw IllegalStateException("字体保存失败")
                setOperation { it.copy(message = "私有字体已导入") }
            }.onFailure { error ->
                setOperation { it.copy(message = error.message ?: "字体导入失败") }
            }
        }
    }

    fun exportDiagnostics(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val count = diagnostics.export(uri)
                setOperation { it.copy(message = "已导出 $count 个诊断文件") }
            }.onFailure { error -> setOperation { it.copy(message = error.message ?: "诊断导出失败") } }
        }
    }

    fun consumeSingleGameMissing() {
        pendingSingleUri.value = null
    }

    fun clearMessage() {
        setOperation { it.copy(message = null) }
    }

    fun showMessage(message: String) {
        setOperation { it.copy(message = message) }
    }

    private suspend fun mergeBatch(batch: ScanBatch): Int = withContext(Dispatchers.IO) {
        val before = store.games.value.associateBy { it.id }
        val added = batch.games.count { it.id !in before }
        store.upsertGames(batch.games)
        batch.games.forEach { game ->
            val current = store.games.value.firstOrNull { it.id == game.id } ?: return@forEach
            if (current.coverPath != null && current.iconPath != null) return@forEach
            val local = batch.metadata[game.id] ?: return@forEach
            runCatching {
                val extracted = artwork.extractLocal(game.id, local)
                store.updateGame(game.id) {
                    it.copy(
                        coverPath = it.coverPath ?: extracted.coverPath,
                        iconPath = it.iconPath ?: extracted.iconPath,
                        coverOrigin = if (it.coverPath == null && extracted.coverPath != null) {
                            CoverOrigin.LOCAL
                        } else {
                            it.coverOrigin
                        },
                    )
                }
            }
        }
        added
    }

    private fun automaticCoverTargets(): List<GameEntry> = store.games.value.filter {
        it.coverOrigin != CoverOrigin.MANUAL && (it.coverPath == null || it.coverOrigin == CoverOrigin.AUTOMATIC)
    }

    private suspend fun fetchAutomatic(game: GameEntry): CoverFetchOutcome {
        try {
            val settings = store.settings.value
            val productId = TitleNormalizer.productId(game.documentId)
            val titles = listOf(game.displayTitle, game.title).map { it.trim() }.filter { it.isNotBlank() }.distinct()
            val queries = (titles.map { title -> listOfNotNull(title, productId).joinToString(" ") } + listOfNotNull(productId))
                .filter { it.isNotBlank() }.distinct()
            val candidate = queries.firstNotNullOfOrNull { query ->
                covers.bestAutomatic(query, settings.coverPriority, settings.adultProtection)
            } ?: return CoverFetchOutcome.NO_MATCH
            val bytes = covers.download(candidate.url)
            var saved = false
            coverWrite(game.id) {
                val current = store.games.value.firstOrNull { it.id == game.id } ?: return@coverWrite
                if (current.coverOrigin == CoverOrigin.MANUAL || current.coverPath != game.coverPath) return@coverWrite
                val path = artwork.saveCover(game.id, bytes) ?: throw IllegalStateException("图片解码或保存失败")
                store.updateGame(game.id) {
                    if (it.coverOrigin == CoverOrigin.MANUAL || it.coverPath != game.coverPath) {
                        it
                    } else {
                        it.copy(coverPath = path, coverSensitive = candidate.sensitive, coverOrigin = CoverOrigin.AUTOMATIC)
                    }
                }
                saved = true
            }
            return if (saved) CoverFetchOutcome.SAVED else CoverFetchOutcome.UNCHANGED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return CoverFetchOutcome.FAILED
        }
    }

    private fun coverOutcomeSummary(outcomes: List<CoverFetchOutcome>): String {
        if (outcomes.isEmpty()) return ""
        val saved = outcomes.count { it == CoverFetchOutcome.SAVED }
        val unmatched = outcomes.count { it == CoverFetchOutcome.NO_MATCH }
        val failed = outcomes.count { it == CoverFetchOutcome.FAILED }
        return buildString {
            append("；更新 $saved 张封面")
            if (unmatched > 0) append("，$unmatched 个未可靠匹配")
            if (failed > 0) append("，$failed 个下载或连接失败")
            if (saved == 0 && unmatched == 0 && failed == 0) append("，现有封面未改动")
        }
    }

    private fun <T> coverWrite(gameId: String, block: () -> T): T =
        synchronized(coverWriteLocks.getOrPut(gameId) { Any() }, block)

    private fun setOperation(transform: (OperationState) -> OperationState) {
        operation.value = transform(operation.value)
    }

    private inline fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
        value = transform(value)
    }
}
