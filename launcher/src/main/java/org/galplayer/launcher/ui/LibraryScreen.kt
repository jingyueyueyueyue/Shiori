package org.galplayer.launcher.ui

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.galplayer.launcher.LauncherViewModel
import org.galplayer.launcher.model.EngineType
import org.galplayer.launcher.model.GameEntry
import org.galplayer.launcher.model.LibrarySnapshot
import org.galplayer.launcher.model.ScanMode

@Composable
fun LibraryScreen(
    snapshot: LibrarySnapshot,
    viewModel: LauncherViewModel,
    onChooseDirectory: (ScanMode) -> Unit,
    onManageGame: (GameEntry) -> Unit,
    onRequestStorageAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var engineFilter by remember { mutableStateOf<EngineType?>(null) }
    var addExpanded by remember { mutableStateOf(false) }
    val width = LocalConfiguration.current.screenWidthDp
    val columns = when {
        width < 360 -> 2
        width < 600 -> 3
        width < 840 -> 4
        else -> (width / 176).coerceIn(5, 6)
    }
    val games = snapshot.games.filter { game ->
        (engineFilter == null || game.engine == engineFilter) &&
            (query.isBlank() || listOf(game.displayTitle, game.title, game.documentId).any { it.contains(query, true) })
    }
    val recent = snapshot.games.filter { it.lastPlayed > 0 && it.available }.maxByOrNull { it.lastPlayed }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("游戏库", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                val majiro = snapshot.games.count { it.engine == EngineType.MAJIRO }
                Text(
                    "${snapshot.games.size} 个游戏 · Majiro $majiro / CS2 ${snapshot.games.size - majiro}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { addExpanded = true }) { Icon(Icons.Rounded.Add, "添加游戏目录") }
                DropdownMenu(addExpanded, { addExpanded = false }) {
                    DropdownMenuItem(text = { Text("扫描游戏目录（含子文件夹）") }, onClick = {
                        addExpanded = false
                        onChooseDirectory(ScanMode.RECURSIVE)
                    })
                    DropdownMenuItem(text = { Text("单独添加游戏文件夹") }, onClick = {
                        addExpanded = false
                        onChooseDirectory(ScanMode.SINGLE)
                    })
                }
            }
            IconButton(onClick = { if (snapshot.coverBatchRunning) viewModel.cancelCoverBatch() else viewModel.fetchMissingCovers() }) {
                Icon(if (snapshot.coverBatchRunning) Icons.Rounded.Close else Icons.Rounded.CloudDownload, "批量获取封面或取消")
            }
            if (snapshot.scanning) {
                CircularProgressIndicator(Modifier.size(34.dp).padding(7.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = { viewModel.refresh() }) { Icon(Icons.Rounded.Refresh, "刷新扫描") }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
            placeholder = { Text("按游戏名、别名或目录名搜索") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.74f),
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
        val filterColors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
            labelColor = MaterialTheme.colorScheme.onSurface,
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
            selectedLabelColor = MaterialTheme.colorScheme.primary,
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = engineFilter == null, onClick = { engineFilter = null }, label = { Text("全部") }, colors = filterColors)
            FilterChip(selected = engineFilter == EngineType.MAJIRO, onClick = { engineFilter = EngineType.MAJIRO }, label = { Text("Majiro") }, colors = filterColors)
            FilterChip(selected = engineFilter == EngineType.CATSYSTEM2, onClick = { engineFilter = EngineType.CATSYSTEM2 }, label = { Text("CS2") }, colors = filterColors)
        }
        if (snapshot.coverBatchProgress != null) {
            val (done, total) = snapshot.coverBatchProgress
            Text("封面获取 $done / $total", Modifier.padding(horizontal = 18.dp), style = MaterialTheme.typography.bodySmall)
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (snapshot.settings.showRecent && recent != null) {
                item(key = "recent", span = { GridItemSpan(maxLineSpan) }) {
                    RecentGameCard(recent, snapshot.settings.adultProtection, onPlay = { viewModel.launchGame(recent) })
                }
            }
            if (snapshot.games.any { it.engine == EngineType.CATSYSTEM2 } && !viewModel.hasStorageAccess()) {
                item(key = "storage", span = { GridItemSpan(maxLineSpan) }) {
                    GlassCard {
                        Text("CS2 文件访问权限", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text("CatSystem2 使用本地文件路径。授予“所有文件访问”后即可直接启动，不会修改游戏文件。")
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = onRequestStorageAccess) { Text("前往授权") }
                    }
                }
            }
            if (games.isEmpty() && !snapshot.scanning) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        if (snapshot.games.isEmpty()) "还没有游戏。点击右上角 + 选择总目录或单独的游戏文件夹。" else "没有符合当前筛选条件的游戏。",
                        Modifier.fillMaxWidth().padding(vertical = 56.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(games, key = { it.id }) { game ->
                GameCard(game, snapshot.settings.posterMode, snapshot.settings.adultProtection,
                    onClick = { viewModel.launchGame(game) }, onLongClick = { onManageGame(game) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameCard(
    game: GameEntry,
    posterMode: Boolean,
    adultProtection: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        shadowElevation = 2.dp,
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(if (posterMode) 0.75f else 1f)) {
                GameArtwork(game, adultProtection, Modifier.fillMaxSize(), posterMode)
                Box(Modifier.align(Alignment.TopStart).padding(7.dp)) { EngineBadge(game.engine) }
                if (!game.available) {
                    Surface(Modifier.align(Alignment.BottomCenter).padding(6.dp), color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(50)) {
                        Text("目录不可达", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Text(
                game.displayTitle,
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                maxLines = if (posterMode) 2 else 1,
                minLines = if (posterMode) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun RecentGameCard(game: GameEntry, adultProtection: Boolean, onPlay: () -> Unit) {
    val cover = rememberArtwork(game.coverPath)
    Surface(
        modifier = Modifier.fillMaxWidth().height(174.dp).clip(RoundedCornerShape(20.dp)),
        color = Color(0xFF243346),
        onClick = onPlay,
    ) {
        Box(Modifier.fillMaxSize()) {
            if (cover != null) {
                Image(
                    cover,
                    null,
                    Modifier.fillMaxSize().blur(20.dp),
                    contentScale = ContentScale.Crop,
                    alpha = 0.55f,
                )
            }
            Box(Modifier.fillMaxSize().background(Color(0xFF101A28).copy(alpha = 0.55f)))
            Row(Modifier.fillMaxSize().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    EngineBadge(game.engine)
                    Spacer(Modifier.height(12.dp))
                    Text("最近游玩", color = Color.White.copy(alpha = 0.76f), style = MaterialTheme.typography.labelLarge)
                    Text(game.displayTitle, color = Color.White, style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        DateUtils.getRelativeTimeSpanString(game.lastPlayed).toString(),
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                GameArtwork(game, adultProtection, Modifier.width(88.dp).height(118.dp))
                IconButton(onClick = onPlay, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.PlayArrow, "直接续玩", tint = Color.White, modifier = Modifier.size(34.dp))
                }
            }
        }
    }
}
