package org.galplayer.launcher.ui

import org.galplayer.launcher.BuildConfig

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.galplayer.launcher.LauncherViewModel
import org.galplayer.launcher.model.CoverPriority
import org.galplayer.launcher.model.LibrarySnapshot
import org.galplayer.launcher.model.TextMode
import org.galplayer.launcher.model.ThemeMode
import org.galplayer.launcher.model.ThemePalette
import org.galplayer.launcher.settings.TranslationSnapshot
import java.io.File

private enum class SettingsCategory(val label: String) {
    APPEARANCE("外观与交互"),
    LIBRARY("游戏库与封面"),
    TEXT("文本与翻译"),
    ENGINES("播放与显示"),
    ABOUT("诊断与关于"),
}

@Composable
fun SettingsScreen(
    snapshot: LibrarySnapshot,
    translation: TranslationSnapshot,
    viewModel: LauncherViewModel,
    onRequestStorageAccess: () -> Unit,
    onExportDiagnostics: () -> Unit,
    onImportFont: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val expanded = LocalConfiguration.current.screenWidthDp >= 840
    var category by remember { mutableStateOf(SettingsCategory.APPEARANCE) }
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("设置", Modifier.padding(start = 2.dp, bottom = 12.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (expanded) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(0.35f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsCategory.entries.forEach { item ->
                        SurfaceChoice(item.label, category == item) { category = item }
                    }
                }
                Column(Modifier.weight(0.65f).verticalScroll(rememberScrollState())) {
                    SettingsSection(category, snapshot, translation, viewModel, onRequestStorageAccess, onExportDiagnostics, onImportFont)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsCategory.entries.forEach { item ->
                    item(key = item.name) {
                        SettingsSection(item, snapshot, translation, viewModel, onRequestStorageAccess, onExportDiagnostics, onImportFont)
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemePalettePicker(selected: ThemePalette, onSelect: (ThemePalette) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text("主题配色", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ThemePalette.entries.forEach { palette ->
                FilterChip(
                    selected = selected == palette,
                    onClick = { onSelect(palette) },
                    label = { Text(palette.label) },
                    leadingIcon = {
                        Box(
                            Modifier.size(17.dp).clip(CircleShape).background(Color(palette.lightPrimary))
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), CircleShape),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun SurfaceChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
    ) {
        Text(label, Modifier.fillMaxWidth().padding(16.dp), color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun SettingsSection(
    category: SettingsCategory,
    snapshot: LibrarySnapshot,
    translation: TranslationSnapshot,
    viewModel: LauncherViewModel,
    onRequestStorageAccess: () -> Unit,
    onExportDiagnostics: () -> Unit,
    onImportFont: () -> Unit,
) {
    when (category) {
        SettingsCategory.APPEARANCE -> SectionCard("外观与交互") {
            ChoiceRow("主题模式", snapshot.settings.themeMode, ThemeMode.entries.map { Choice(it, it.label) }, { mode ->
                viewModel.updateSettings { it.copy(themeMode = mode) }
            })
            ThemePalettePicker(snapshot.settings.themePalette) { palette ->
                viewModel.updateSettings { it.copy(themePalette = palette) }
            }
            SettingSwitchRow("精美海报模式", snapshot.settings.posterMode, { enabled ->
                viewModel.updateSettings { it.copy(posterMode = enabled) }
            }, "关闭后使用紧凑图标卡片")
            SettingSwitchRow("显示最近游玩", snapshot.settings.showRecent, { enabled ->
                viewModel.updateSettings { it.copy(showRecent = enabled) }
            }, "在游戏库顶部显示可点击续玩的精选横幅")
            SettingSwitchRow("始终使用底部导航", snapshot.settings.alwaysBottomNavigation, { enabled ->
                viewModel.updateSettings { it.copy(alwaysBottomNavigation = enabled) }
            }, "关闭时，平板与横屏自动使用左侧导航")
        }
        SettingsCategory.LIBRARY -> SectionCard("游戏库与封面") {
            Text("已授权扫描目录", fontWeight = FontWeight.Bold)
            if (snapshot.roots.isEmpty()) Text("尚未添加目录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            snapshot.roots.forEach { root ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(root.uri, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                        Text(if (root.recursive) "含子文件夹" else "单独游戏目录", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { viewModel.removeRoot(root.uri) }) { Text("移除") }
                }
            }
            Text("移除根目录只停止后续扫描，不删除游戏或已入库记录。", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SettingSwitchRow("新增游戏时自动匹配封面", snapshot.settings.autoCover, { enabled ->
                viewModel.updateSettings { it.copy(autoCover = enabled) }
            })
            ChoiceRow("封面源优先级", snapshot.settings.coverPriority, CoverPriority.entries.map { Choice(it, it.label) }, { priority ->
                viewModel.updateSettings { it.copy(coverPriority = priority) }
            })
            SettingSwitchRow("成人／敏感封面保护", snapshot.settings.adultProtection, { enabled ->
                viewModel.updateSettings { it.copy(adultProtection = enabled) }
            }, "默认开启；自动与手动获取的敏感封面均模糊显示")
            val cacheSize by produceState(0L, snapshot.games) { value = viewModel.artworkCacheSize() }
            Text("本地图片缓存：${"%.1f".format(cacheSize / 1024f / 1024f)} MB")
            var clearConfirm by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { clearConfirm = true }) { Text("清理启动器封面缓存") }
            if (clearConfirm) {
                AlertDialog(
                    onDismissRequest = { clearConfirm = false },
                    title = { Text("清理图片缓存？") },
                    text = { Text("仅删除启动器私有目录中的封面和图标，不影响游戏文件与存档。") },
                    confirmButton = { TextButton(onClick = {
                        clearConfirm = false
                        viewModel.clearArtworkCache()
                    }) { Text("清理") } },
                    dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("取消") } },
                )
            }
        }
        SettingsCategory.TEXT -> SectionCard("文本与翻译") {
            ChoiceRow("全局默认文本模式", snapshot.settings.textMode,
                TextMode.entries.filter { it != TextMode.FOLLOW_GLOBAL }.map { Choice(it, it.label) }, { mode ->
                    viewModel.updateSettings { it.copy(textMode = mode) }
                })
            Text("在线翻译仅 Majiro 生效；CS2 选择在线翻译时回退为日语原文。", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TranslationEditor(translation, viewModel)
        }
        SettingsCategory.ENGINES -> SectionCard("播放与显示") {
            ChoiceRow("默认字体渲染质量", snapshot.settings.highQualityFont, listOf(
                Choice(false, "标准"), Choice(true, "高清"),
            ), { high -> viewModel.updateSettings { it.copy(highQualityFont = high) } },
                "在支持字体质量切换的游戏中生效")
            SettingSwitchRow("默认显示触屏快捷键", snapshot.settings.touchKeys, { enabled ->
                viewModel.updateSettings { it.copy(touchKeys = enabled) }
            }, "在支持触屏快捷键的游戏中显示")
            val context = LocalContext.current
            val fontInstalled = remember(snapshot.message) { File(context.filesDir, "fonts/msgothic.ttc").isFile }
            Text(if (fontInstalled) "私有字体：已安装" else "私有字体：未安装", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onImportFont) { Text("导入私有字体") }
            SettingSwitchRow("默认显示性能监控", snapshot.settings.performanceHud, { enabled ->
                viewModel.updateSettings { it.copy(performanceHud = enabled) }
            }, "Majiro 与 CatSystem2 共用；显示 FPS / GPU / CPU / RAM / PWR")
            Text(if (viewModel.hasStorageAccess()) "文件访问权限：已授予" else "文件访问权限：未授予")
            OutlinedButton(onClick = onRequestStorageAccess) { Text("检查并授予文件访问") }
        }
        SettingsCategory.ABOUT -> SectionCard("诊断与关于") {
            SettingSwitchRow("显示高级调试选项", snapshot.settings.advanced, { enabled ->
                viewModel.updateSettings { it.copy(advanced = enabled) }
            }, "开启后可在单游戏管理中使用隔离存档启动")
            OutlinedButton(onClick = onExportDiagnostics) { Text("导出双引擎诊断日志 ZIP") }
            Spacer(Modifier.height(12.dp))
            Text("Shiori ${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.Bold)
            Text("applicationId：io.jingyue.shiori")
            Text("Majiro：org.majiro.player · :game")
            Text("CatSystem2：org.catsystem2.player · :cs2")
            Text("两套引擎与原生库已内置，无需另外下载。", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("GitHub 项目", fontWeight = FontWeight.Bold)
            ProjectLink("Shiori", "https://github.com/jingyueyueyueyue/Shiori")
            ProjectLink("Majiro", "https://github.com/jingyueyueyueyue/majiro-emulator")
            ProjectLink("CatSystem2", "https://github.com/jingyueyueyueyue/CatSystem2-emulator")
            var licenses by remember { mutableStateOf(false) }
            TextButton(onClick = { licenses = true }) { Text("查看开源许可") }
            if (licenses) LicenseDialog { licenses = false }
        }
    }
}

@Composable
private fun ProjectLink(name: String, url: String) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    TextButton(onClick = { uriHandler.openUri(url) }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Text(name, fontWeight = FontWeight.Medium)
            Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun TranslationEditor(translation: TranslationSnapshot, viewModel: LauncherViewModel) {
    var url by remember { mutableStateOf(translation.url) }
    var key by remember { mutableStateOf(translation.key) }
    var model by remember { mutableStateOf(translation.model) }
    var contextLines by remember { mutableStateOf(translation.contextLines.toString()) }
    LaunchedEffect(translation.models) { translation.models.find { it == model }?.let { model = it } }
    OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("API 地址（以 /v1 结尾）") }, singleLine = true)
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("API Key") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation())
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("模型") }, singleLine = true)
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(contextLines, { value -> contextLines = value.filter { it.isDigit() }.take(3) }, Modifier.fillMaxWidth(),
        label = { Text("上下文句数（2–200）") }, singleLine = true)
    if (translation.models.isNotEmpty()) {
        Text("已读取模型：${translation.models.joinToString()}", style = MaterialTheme.typography.bodySmall, maxLines = 3)
    }
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val snapshot = TranslationSnapshot(url, key, model, contextLines.toIntOrNull() ?: 20)
        OutlinedButton(onClick = { viewModel.fetchTranslationModels(snapshot) }) { Text("拉取模型") }
        Button(onClick = { viewModel.saveTranslation(snapshot) }) { Text("加密保存") }
    }
}

@Composable
private fun LicenseDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text by produceState("", context) {
        value = runCatching {
            buildString {
                listOf("third-party/CS2-THIRD-PARTY.md", "licenses/SDL3.txt", "licenses/Lua.txt").forEach { path ->
                    appendLine("—— $path ——")
                    context.assets.open(path).bufferedReader().use { append(it.readText()) }
                    appendLine()
                }
            }
        }.getOrElse { "许可文件读取失败：${it.message}" }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开源许可") },
        text = { Text(text, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
