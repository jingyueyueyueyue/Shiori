package org.galplayer.launcher.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.galplayer.launcher.artwork.ImageCodec
import org.galplayer.launcher.model.EngineType
import org.galplayer.launcher.model.GameEntry
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun rememberArtwork(source: String?, maximumSide: Int = 900): ImageBitmap? {
    val localFile = if (source.isNullOrBlank() || source.startsWith("http://") || source.startsWith("https://")) null else File(source)
    val modifiedAt = localFile?.lastModified() ?: 0L
    val fileLength = localFile?.length() ?: 0L
    val bitmap by produceState<ImageBitmap?>(initialValue = null, source, modifiedAt, fileLength) {
        value = withContext(Dispatchers.IO) {
            if (source.isNullOrBlank()) return@withContext null
            runCatching {
                val bytes = if (source.startsWith("http://") || source.startsWith("https://")) {
                    val connection = URL(source).openConnection() as HttpURLConnection
                    try {
                        connection.connectTimeout = 12_000
                        connection.readTimeout = 18_000
                        connection.setRequestProperty("User-Agent", "Shiori/0.1.2 (Android)")
                        connection.inputStream.use { ImageCodec.readLimited(it, 24 * 1024 * 1024) }
                    } finally {
                        connection.disconnect()
                    }
                } else {
                    File(source).inputStream().use { ImageCodec.readLimited(it, 32 * 1024 * 1024) }
                }
                ImageCodec.decode(bytes, maximumSide)?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bitmap
}

@Composable
fun GameArtwork(
    game: GameEntry,
    adultProtection: Boolean,
    modifier: Modifier = Modifier,
    posterMode: Boolean = true,
) {
    val cover = rememberArtwork(game.coverPath)
    val icon = rememberArtwork(game.iconPath, 512)
    val protected = adultProtection && game.coverSensitive
    val colors = MaterialTheme.colorScheme
    val placeholderBrush = Brush.linearGradient(
        listOf(colors.primary.copy(alpha = 0.78f), colors.secondary, colors.tertiary),
    )
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(placeholderBrush)) {
        when {
            cover != null -> {
                Image(
                    bitmap = cover,
                    contentDescription = game.displayTitle,
                    modifier = Modifier.fillMaxSize().then(if (protected) Modifier.blur(24.dp) else Modifier),
                    contentScale = ContentScale.Crop,
                )
            }
            icon != null -> {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.radialGradient(listOf(colors.primary.copy(alpha = 0.72f), colors.secondary)),
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    SurfaceIcon(icon)
                }
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    game.engine.displayName,
                    color = Color.White.copy(alpha = 0.88f),
                    style = if (posterMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (protected) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                Text("敏感封面", color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun SurfaceIcon(icon: ImageBitmap) {
    Box(
        Modifier.padding(22.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.82f)).padding(18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(icon, null, Modifier.size(82.dp), contentScale = ContentScale.Fit)
    }
}
