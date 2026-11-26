package org.galplayer.launcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import org.galplayer.launcher.model.GameEntry
import org.galplayer.launcher.model.ScanMode
import org.galplayer.launcher.ui.LauncherApp
import org.galplayer.launcher.ui.LauncherTheme

class LauncherActivity : ComponentActivity() {
    private val viewModel: LauncherViewModel by viewModels()
    private var scanMode = ScanMode.RECURSIVE
    private var coverGame: GameEntry? = null

    private val openTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
                .onFailure { viewModel.showMessage("目录授权无法持久化，请重新选择并允许访问") }
            viewModel.scanFolder(uri.toString(), scanMode)
        }
    }
    private val openCover = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val game = coverGame
        if (uri != null && game != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            viewModel.importCover(game, uri)
        }
        coverGame = null
    }
    private val createDiagnostics = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.exportDiagnostics(uri)
    }
    private val openFont = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importFont(uri)
    }
    private val legacyStoragePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.showMessage(if (granted) "文件访问权限已授予，请重新启动游戏" else "未授予文件访问权限，CS2 无法读取本地游戏目录")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LauncherApp(
                viewModel = viewModel,
                onChooseDirectory = { mode ->
                    scanMode = mode
                    openTree.launch(null)
                },
                onChooseCover = { game ->
                    coverGame = game
                    openCover.launch(arrayOf("image/*"))
                },
                onExportDiagnostics = { createDiagnostics.launch("Shiori-diagnostics.zip") },
                onImportFont = { openFont.launch(arrayOf("application/octet-stream", "font/*")) },
                onRequestStorageAccess = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        runCatching { startActivity(viewModel.storagePermissionIntent()) }
                            .onFailure { viewModel.showMessage("无法打开文件访问设置") }
                    } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        legacyStoragePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.reloadTranslation()
    }
}
