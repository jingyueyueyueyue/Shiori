package org.galplayer.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.galplayer.launcher.LauncherViewModel
import org.galplayer.launcher.model.GameEntry
import org.galplayer.launcher.model.ScanMode

@Composable
fun LauncherApp(
    viewModel: LauncherViewModel,
    onChooseDirectory: (ScanMode) -> Unit,
    onChooseCover: (GameEntry) -> Unit,
    onExportDiagnostics: () -> Unit,
    onRequestStorageAccess: () -> Unit,
    onImportFont: () -> Unit,
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val translation by viewModel.translation.collectAsStateWithLifecycle()
    val coverSearch by viewModel.coverSearch.collectAsStateWithLifecycle()
    val singleMissing by viewModel.singleGameMissing.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var managedGameId by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val configuration = LocalConfiguration.current
    val rail = !snapshot.settings.alwaysBottomNavigation &&
        (configuration.screenWidthDp >= 840 || (configuration.screenWidthDp >= 600 && configuration.screenWidthDp > configuration.screenHeightDp))
    val managedGame = snapshot.games.firstOrNull { it.id == managedGameId }

    LaunchedEffect(snapshot.message) {
        snapshot.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    LauncherTheme(snapshot.settings.themeMode) {
        AmbientBackground {
            Row(Modifier.fillMaxSize()) {
                if (rail) {
                    NavigationRail(
                        modifier = Modifier.fillMaxHeight().statusBarsPadding().navigationBarsPadding().padding(12.dp)
                            .width(112.dp).clip(RoundedCornerShape(28.dp)),
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                    ) {
                        NavigationRailItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            modifier = Modifier.height(76.dp),
                            icon = { Icon(Icons.Rounded.SportsEsports, "游戏库", Modifier.size(30.dp)) },
                            label = { Text("游戏库", style = MaterialTheme.typography.bodyMedium) },
                        )
                        NavigationRailItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            modifier = Modifier.height(76.dp),
                            icon = { Icon(Icons.Rounded.Settings, "设置", Modifier.size(30.dp)) },
                            label = { Text("设置", style = MaterialTheme.typography.bodyMedium) },
                        )
                    }
                }
                Scaffold(
                    modifier = Modifier.weight(1f),
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        if (!rail) {
                            NavigationBar(
                                modifier = Modifier.navigationBarsPadding().padding(horizontal = 48.dp, vertical = 10.dp)
                                    .height(66.dp).clip(RoundedCornerShape(28.dp)),
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                                tonalElevation = 0.dp,
                            ) {
                                NavigationBarItem(
                                    selected = selectedTab == 0,
                                    onClick = { selectedTab = 0 },
                                    icon = { Icon(Icons.Rounded.SportsEsports, "游戏库") },
                                    label = { Text("游戏库") },
                                )
                                NavigationBarItem(
                                    selected = selectedTab == 1,
                                    onClick = { selectedTab = 1 },
                                    icon = { Icon(Icons.Rounded.Settings, "设置") },
                                    label = { Text("设置") },
                                )
                            }
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        when (selectedTab) {
                            0 -> LibraryScreen(snapshot, viewModel, onChooseDirectory,
                                onManageGame = { managedGameId = it.id }, onRequestStorageAccess = onRequestStorageAccess)
                            else -> SettingsScreen(snapshot, translation, viewModel, onRequestStorageAccess,
                                onExportDiagnostics, onImportFont = onImportFont)
                        }
                    }
                }
            }
        }
    }

    managedGame?.let { game ->
        GameManagement(game, snapshot, viewModel, onChooseCover, onDismiss = { managedGameId = null })
    }
    if (coverSearch.gameId != null) {
        CoverSearchDialog(coverSearch, snapshot.settings.adultProtection, viewModel)
    }
    singleMissing?.let { uri ->
        AlertDialog(
            onDismissRequest = viewModel::consumeSingleGameMissing,
            title = { Text("当前文件夹未检测到游戏") },
            text = { Text("可以改为扫描此文件夹的子目录，无需重新选择。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.consumeSingleGameMissing()
                    viewModel.scanFolder(uri, ScanMode.RECURSIVE)
                }) { Text("扫描子文件夹") }
            },
            dismissButton = { TextButton(onClick = viewModel::consumeSingleGameMissing) { Text("取消") } },
        )
    }
}
