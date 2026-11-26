package org.galplayer.launcher.artwork

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream

object ImageCodec {
    fun readLimited(input: InputStream, maximumBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maximumBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, maximumBytes - output.size()))
            if (count < 0) break
            if (count == 0) {
                if (output.size() == maximumBytes) break
                continue
            }
            output.write(buffer, 0, count)
            if (output.size() == maximumBytes) break
        }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray, maximumSide: Int = 900): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maximumSide * 2 || bounds.outHeight / sample > maximumSide * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let { source ->
            if (source.width <= maximumSide && source.height <= maximumSide) source
            else {
                val scale = maximumSide.toFloat() / maxOf(source.width, source.height)
                Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1),
                    (source.height * scale).toInt().coerceAtLeast(1), true).also { scaled ->
                    if (scaled !== source) source.recycle()
                }
            }
        }
    }.getOrNull()
}
