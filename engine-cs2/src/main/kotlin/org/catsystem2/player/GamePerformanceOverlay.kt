package org.catsystem2.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt

private val MetricStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

@Composable
internal fun GamePerformanceOverlay(
    snapshot: PerformanceSnapshot,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(4.dp)
    val power = when {
        snapshot.externalPower -> stringResource(R.string.performance_external_power)
        snapshot.powerWatts != null -> String.format(Locale.ROOT, "%4.1fW", snapshot.powerWatts)
        else -> "  — W"
    }
    val fps = snapshot.game.displayedFps
    val gpu = when (snapshot.gpu.status) {
        GpuUsageStatus.AVAILABLE -> percent(snapshot.gpu.percent)
        GpuUsageStatus.STALE -> "~" + percent(snapshot.gpu.percent).trimStart()
        else -> percent(null)
    }

    FlowRow(
        modifier = Modifier
            .background(Color.Black.copy(alpha = if (dragging) 0.86f else 0.72f), shape)
            .border(1.dp, if (dragging) Color(0xFF5096F3) else Color.Transparent, shape)
            .pointerInput(onDrag, onDrop) {
                detectDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false; onDrop() },
                    onDragCancel = { dragging = false; onDrop() },
                ) { change, delta ->
                    change.consume()
                    onDrag(delta)
                }
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Metric("FPS", fps?.roundToInt()?.toString()?.padStart(3) ?: "  —",
            Color(0xFFFFFF50), stringResource(R.string.performance_frames))
        Metric("GPU", gpu, Color(0xFF64FF64))
        Metric("CPU", percent(snapshot.cpuPercent), Color(0xFFFFB400),
            stringResource(R.string.performance_cpu))
        Metric("RAM", percent(snapshot.ramPercent), Color(0xFF50C8FF),
            stringResource(R.string.performance_ram))
        Metric("PWR", power, Color(0xFF7DA2FF), stringResource(R.string.performance_power))
    }
}

private fun percent(value: Float?): String =
    value?.let { "${it.roundToInt().toString().padStart(3)}%" } ?: "   —"

@Composable
private fun Metric(label: String, value: String, color: Color, description: String = label) {
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = color)) { append(label) }
            withStyle(SpanStyle(color = Color.White)) { append(" "); append(value) }
        },
        modifier = Modifier.semantics { contentDescription = "$description $value" },
        style = MetricStyle,
        maxLines = 1,
        softWrap = false,
    )
}
