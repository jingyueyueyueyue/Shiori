package org.galplayer.launcher.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.galplayer.launcher.BuildConfig
import org.galplayer.launcher.LauncherViewModel
import org.galplayer.launcher.model.EngineType
import org.galplayer.launcher.model.GameEntry
import org.galplayer.launcher.model.LibrarySnapshot
import org.galplayer.launcher.model.TextMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameManagement(
    game: GameEntry,
    snapshot: LibrarySnapshot,
    viewModel: LauncherViewModel,
    onChooseCover: (GameEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val expanded = LocalConfiguration.current.screenWidthDp >= 840
    val content: @Composable ColumnScope.() -> Unit = {
        GameManagementContent(game, snapshot, viewModel, onChooseCover, onDismiss)
    }
    if (expanded) {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {},
            modifier = Modifier.widthIn(max = 560.dp),
            text = { Column(Modifier.verticalScroll(rememberScrollState()), content = content) },
        )
    } else {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), content = content)
        }
    }
}

@Composable
private fun GameManagementContent(
    game: GameEntry,
    snapshot: LibrarySnapshot,
    viewModel: LauncherViewModel,
    onChooseCover: (GameEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(game.id) { mutableStateOf(game.displayTitle) }
    var textMode by remember(game.id) { mutableStateOf(game.settings.textMode) }
    var engine by remember(game.id) { mutableStateOf(game.settings.engineOverride) }
    var touchKeys by remember(game.id) { mutableStateOf(game.settings.touchKeys) }
    var fontQuality by remember(game.id) { mutableStateOf(game.settings.highQualityFont) }
    var hud by remember(game.id) { mutableStateOf(game.settings.hudEnabled) }
    var isolated by remember(game.id) { mutableStateOf(game.settings.isolated) }
    var removeConfirm by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        GameArtwork(game, snapshot.settings.adultProtection, Modifier.size(width = 86.dp, height = 116.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("单游戏管理", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            EngineBadge(game.engine)
            if (game.lastPlayed > 0) {
                Text("上次游玩：${DateUtils.getRelativeTimeSpanString(game.lastPlayed)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Text("目录：${game.documentId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(14.dp))
    OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("启动器显示名称") }, singleLine = true)
    Spacer(Modifier.height(8.dp))
    Text("游戏信息与封面", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text("封面来源：${game.coverOrigin.label} · 手动替换优先", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { viewModel.openCoverSearch(game) }, modifier = Modifier.weight(1f)) { Text("在线搜索") }
        OutlinedButton(onClick = { onChooseCover(game) }, modifier = Modifier.weight(1f)) { Text("本地图片") }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { viewModel.reextractArtwork(game) }, modifier = Modifier.weight(1f)) { Text("重新提取") }
        OutlinedButton(onClick = { viewModel.restoreDefaultArtwork(game) }, modifier = Modifier.weight(1f)) { Text("恢复默认") }
    }
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    ChoiceRow("文本与语种", textMode, TextMode.entries.map { Choice(it, it.label) }, { textMode = it },
        "CS2 使用在线翻译时回退为日语原文")
    ChoiceRow("引擎识别修正", engine, listOf<Choice<EngineType?>>(
        Choice(null, "自动识别（${game.detectedEngine.displayName}）"),
        Choice(EngineType.MAJIRO, "强制 Majiro"),
        Choice(EngineType.CATSYSTEM2, "强制 CatSystem2"),
    ), { engine = it })
    if (game.engine == EngineType.MAJIRO) {
        ChoiceRow("触屏快捷键", touchKeys, triStateChoices, { touchKeys = it })
        ChoiceRow("字体渲染质量", fontQuality, listOf<Choice<Boolean?>>(
            Choice(null, "跟随全局"),
            Choice(false, "标准"),
            Choice(true, "高清"),
        ), { fontQuality = it })
    } else {
        ChoiceRow("性能监控浮窗", hud, triStateChoices, { hud = it })
    }
    if (BuildConfig.DEBUG || snapshot.settings.advanced) {
        SettingSwitchRow("隔离存档测试启动", isolated, { isolated = it }, "使用独立临时存档，不读取正式存档")
    }
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = {
            viewModel.updateGameSettings(game, game.settings.copy(
                customTitle = title.trim().takeIf { it.isNotBlank() && it != game.title },
                textMode = textMode,
                engineOverride = engine,
                touchKeys = touchKeys,
                highQualityFont = fontQuality,
                hudEnabled = hud,
                isolated = isolated,
            ))
            onDismiss()
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("保存单游戏设置") }
    Spacer(Modifier.height(8.dp))
    TextButton(
        onClick = { removeConfirm = true },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) { Text("移出游戏库（不删除游戏文件）") }
    Spacer(Modifier.height(16.dp))
    if (removeConfirm) {
        AlertDialog(
            onDismissRequest = { removeConfirm = false },
            title = { Text("仅移出启动器列表？") },
            text = { Text("仅从启动器列表移除该游戏图标及本地封面／图标缓存，设备存储中的游戏本体文件夹和游戏存档均会完整保留，不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    removeConfirm = false
                    viewModel.removeGame(game)
                    onDismiss()
                }) { Text("确认移出", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removeConfirm = false }) { Text("取消") } },
        )
    }
}

private val triStateChoices: List<Choice<Boolean?>> = listOf(
    Choice<Boolean?>(null, "跟随全局"),
    Choice(true, "开启"),
    Choice(false, "关闭"),
)
