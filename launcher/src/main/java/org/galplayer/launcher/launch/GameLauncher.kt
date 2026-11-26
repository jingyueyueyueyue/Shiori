package org.galplayer.launcher.launch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import org.catsystem2.player.PlayerEngineActivity
import org.galplayer.launcher.data.LauncherStore
import org.galplayer.launcher.model.EngineType
import org.galplayer.launcher.model.GameEntry
import org.galplayer.launcher.model.GlobalSettings
import org.galplayer.launcher.model.LaunchOutcome
import org.galplayer.launcher.model.TextMode
import org.majiro.player.GameActivity
import java.io.File

class GameLauncher(private val context: Context, private val store: LauncherStore) {
    private val appContext = context.applicationContext

    fun hasStorageAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        appContext.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    fun storagePermissionIntent(): Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${appContext.packageName}"))
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${appContext.packageName}"))
    }

    fun launch(game: GameEntry, settings: GlobalSettings): LaunchOutcome {
        if (!game.available) return LaunchOutcome.Failed("游戏目录当前不可达，请刷新扫描或重新授权")
        return try {
            val textMode = if (game.settings.textMode == TextMode.FOLLOW_GLOBAL) settings.textMode else game.settings.textMode
            val isolated = game.settings.isolated && (settings.advanced || org.galplayer.launcher.BuildConfig.DEBUG)
            val intent = when (game.engine) {
                EngineType.MAJIRO -> majiroIntent(game, settings, textMode, isolated)
                EngineType.CATSYSTEM2 -> cs2Intent(game, settings, textMode, isolated) ?: return LaunchOutcome.StoragePermissionRequired
            }
            appContext.startActivity(intent)
            store.updateGame(game.id) { it.copy(lastPlayed = System.currentTimeMillis(), available = true) }
            LaunchOutcome.Dispatched
        } catch (error: Exception) {
            LaunchOutcome.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun majiroIntent(game: GameEntry, settings: GlobalSettings, textMode: TextMode, isolated: Boolean): Intent =
        Intent(appContext, GameActivity::class.java).apply {
            putExtra("game_tree", game.treeUri)
            putExtra("game_root", game.documentId)
            putExtra("game_title", game.displayTitle)
            putExtra("override_text_mode", if (textMode == TextMode.FOLLOW_GLOBAL) TextMode.AUTO.id else textMode.id)
            putExtra("override_touch_keys", game.settings.touchKeys ?: settings.touchKeys)
            putExtra("override_font_quality", if (game.settings.highQualityFont ?: settings.highQualityFont) 1 else 0)
            data = Uri.parse(game.treeUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (isolated) {
                putExtra("override_isolated", true)
                putExtra("diagnostic_save_namespace", "test-launcher-" + game.id.take(20))
            }
        }

    private fun cs2Intent(game: GameEntry, settings: GlobalSettings, textMode: TextMode, isolated: Boolean): Intent? {
        if (!hasStorageAccess()) return null
        val gameDirectory = resolveLocalDirectory(game.treeUri, game.documentId)
        val saves = File(appContext.filesDir, "saves/${game.id}")
        val language = when (textMode) {
            TextMode.AUTO, TextMode.FOLLOW_GLOBAL -> 0
            TextMode.LOCAL -> 2
            TextMode.ORIGINAL, TextMode.ONLINE -> 1
        }
        return Intent(appContext, org.catsystem2.player.PlayerActivity::class.java).apply {
            putExtra(PlayerEngineActivity.EXTRA_GAME_PATH, gameDirectory.absolutePath)
            putExtra(PlayerEngineActivity.EXTRA_SAVES_PATH, saves.absolutePath)
            putExtra(PlayerEngineActivity.EXTRA_GAME_TITLE, game.displayTitle)
            putExtra(PlayerEngineActivity.EXTRA_LANGUAGE, language)
            putExtra(PlayerEngineActivity.EXTRA_ISOLATED, isolated)
            putExtra(PlayerEngineActivity.EXTRA_HUD_ENABLED, game.settings.hudEnabled ?: settings.cs2Hud)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun resolveLocalDirectory(treeText: String, documentId: String): File {
        val tree = Uri.parse(treeText)
        require(tree.authority == EXTERNAL_STORAGE_AUTHORITY) { "CS2 需要本地存储目录，不支持当前文档提供器" }
        val separator = documentId.indexOf(':')
        require(separator > 0) { "无效的游戏目录标识" }
        val volumeId = documentId.substring(0, separator)
        val volume = if (volumeId.equals("primary", true)) {
            Environment.getExternalStorageDirectory()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = appContext.getSystemService(StorageManager::class.java)
            manager.storageVolumes.firstOrNull { volumeId.equals(it.uuid, true) }?.directory
                ?: File("/storage/$volumeId")
        } else {
            File("/storage/$volumeId")
        }
        val root = volume.canonicalFile
        val directory = File(root, documentId.substring(separator + 1)).canonicalFile
        require(directory.toPath().startsWith(root.toPath())) { "游戏目录超出已授权存储卷" }
        require(directory.isDirectory && directory.canRead()) { "游戏目录不可读：${directory.absolutePath}" }
        return directory
    }

    private companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    }
}
