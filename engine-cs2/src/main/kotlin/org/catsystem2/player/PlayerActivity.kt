package org.catsystem2.player

import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.galplayer.performance.SystemPerformanceSampler

class PlayerActivity : PlayerEngineActivity() {
    private val preferences by lazy { getSharedPreferences("performance_overlay", MODE_PRIVATE) }
    private val margin by lazy { (8 * resources.displayMetrics.density).roundToInt() }
    private val completedTicks = AtomicLong()
    private val submittedFrames = AtomicLong()
    private val lastSubmitAt = AtomicLong()
    private val hudEpoch = AtomicLong()
    private val latestEngineSample = AtomicReference<Pair<Long, EngineHudSample>?>()
    private val gameRate = GameRateSampler()
    private var monitorEnabled by mutableStateOf(false)
    private var snapshot by mutableStateOf(PerformanceSnapshot())
    private var gameVisible = false
    private var overlay: ComposeView? = null
    private var position: Offset? = null
    private var samplingJob: Job? = null

    override fun onCreate(state: Bundle?) {
        val x = preferences.getFloat("position_x", Float.NaN)
        val y = preferences.getFloat("position_y", Float.NaN)
        if (x in 0f..1f && y in 0f..1f) position = Offset(x, y)
        monitorEnabled = if (intent.hasExtra(EXTRA_HUD_ENABLED)) {
            intent.getBooleanExtra(EXTRA_HUD_ENABLED, false)
        } else {
            preferences.getBoolean("enabled", false)
        }
        super.onCreate(state)
        playerRoot.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeOverlay() }
        if (monitorEnabled) showOverlay()
    }

    override fun createPerformanceAction(): View = ComposeView(this).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                TextButton(
                    onClick = ::toggleMonitor,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF9CDDBD)),
                ) {
                    Text(stringResource(if (monitorEnabled) R.string.performance_hide else R.string.performance_show))
                }
            }
        }
    }

    override fun onGameViewVisibilityChanged(visible: Boolean) {
        if (gameVisible == visible) return
        gameVisible = visible
        resetGameSampling()
        if (monitorEnabled) {
            snapshot = snapshot.copy(game = GamePerformance())
            placeOverlay()
        }
    }

    override fun onGameTickCompleted() {
        completedTicks.incrementAndGet()
    }

    override fun onGameFrameSubmitted() {
        lastSubmitAt.set(System.nanoTime())
        submittedFrames.incrementAndGet()
    }

    private fun toggleMonitor() {
        monitorEnabled = !monitorEnabled
        preferences.edit().putBoolean("enabled", monitorEnabled).apply()
        if (monitorEnabled) {
            showOverlay()
        } else {
            val view = overlay
            overlay = null
            stopGameSampling()
            samplingJob?.cancel()
            samplingJob = null
            view?.let(playerRoot::removeView)
            snapshot = PerformanceSnapshot()
        }
    }

    private fun showOverlay() {
        val view = ComposeView(this).apply {
            setContent {
                GamePerformanceOverlay(snapshot, onDrag = ::moveOverlay, onDrop = ::savePosition)
            }
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeOverlay() }
        }
        overlay = view
        @Suppress("RtlHardcoded")
        val params = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.LEFT)
        params.setMargins(margin, margin, margin, margin)
        playerRoot.addView(view, params)
        samplingJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                snapshot = PerformanceSnapshot()
                resetGameSampling()
                setGameHudCollection(true)
                try {
                    sampleGameRates()
                    pollEngineSample()
                    val sampler = withContext(Dispatchers.IO) { SystemPerformanceSampler(applicationContext) }
                    while (isActive) {
                        delay(1_000)
                        val metrics = withContext(Dispatchers.IO) { sampler.sample() }
                        pollEngineSample()
                        snapshot = PerformanceSnapshot(system = metrics, game = sampleGameRates())
                        logSample(snapshot)
                    }
                } finally {
                    // A cancelled sampler may finish after a new overlay starts.
                    if (overlay === view) stopGameSampling()
                }
            }
        }
    }

    private fun stopGameSampling() {
        resetGameSampling()
        setGameHudCollection(false)
    }

    private fun resetGameSampling() {
        hudEpoch.incrementAndGet()
        latestEngineSample.set(null)
        gameRate.reset()
    }

    private suspend fun pollEngineSample() {
        if (!gameVisible || !hasGameMonitorSurface()) return
        val epoch = hudEpoch.get()
        val ready = CompletableDeferred<Unit>()
        requestGameHudSample { fields ->
            latestEngineSample.set(epoch to EngineHudSample(fields, System.nanoTime()))
            ready.complete(Unit)
        }
        withTimeoutOrNull(40) { ready.await() }
    }

    private fun sampleGameRates(): GamePerformance {
        val now = System.nanoTime()
        val engine = latestEngineSample.get()?.takeIf { (epoch, sample) ->
            epoch == hudEpoch.get() && now - sample.sampledAt in 0..2_500_000_000L
        }?.second
        return gameRate.sample(now, gameVisible && hasGameMonitorSurface(), completedTicks.get(),
            submittedFrames.get(), lastSubmitAt.get(), engine)
    }

    private fun logSample(sample: PerformanceSnapshot) {
        val game = sample.game
        val detail = game.detail
        fun rate(value: Float?): String = value?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "na"
        fun us(value: Long?): String = value?.let { (it / 1_000L).toString() } ?: "na"
        val now = System.nanoTime()
        fun age(stamp: Long?): String = stamp?.takeIf { it > 0 && it <= now }
            ?.let { ((now - it) / 1_000_000).toString() } ?: "na"
        Log.i("CS2Hud", "session=${detail?.session ?: 0} active=${game.active} static=${game.staticFrame}" +
            " logic_tps=${rate(game.logicTps)} submitted_fps=${rate(game.submittedFps)}" +
            " displayed_fps=${rate(game.displayedFps)} display_source=${game.displaySource.name.lowercase(Locale.ROOT)}" +
            " sample_age_ms=${age(detail?.sampledAt)} display_mono_ns=${detail?.displayedAt ?: 0}" +
            " displayed=${detail?.displayed ?: 0} pending=${detail?.pending ?: 0} unresolved=${detail?.unresolved ?: 0}" +
            " queue_to_display_us=${us(detail?.queueToDisplayNs?.takeIf { it > 0 })}" +
            " logic_us=${us(detail?.logicNs?.takeIf { it > 0 })} copy_draw_us=${us(detail?.copyNs?.takeIf { it > 0 })}" +
            " submit_us=${us(detail?.swapNs?.takeIf { it > 0 })} present_cpu_us=${us(detail?.presentNs?.takeIf { it > 0 })}" +
            " logic_age_ms=${age(detail?.lastLogicAt)} submit_age_ms=${age(detail?.submittedAt)}" +
            " vulkan_raster_us=${us(game.rasterNs)} vulkan_batch=${detail?.gpuSequence ?: 0}" +
            " gpu_busy_pct=${rate(sample.gpu.percent)} gpu_busy_status=${sample.gpu.status.name.lowercase(Locale.ROOT)}" +
            " gpu_busy_source=${sample.gpu.source ?: "na"} gpu_busy_age_ms=${sample.gpu.ageMs ?: "na"}" +
            " gpu_busy_issue=${sample.gpu.currentIssue?.name?.lowercase(Locale.ROOT) ?: "na"}")
    }

    private fun travel(view: View): Offset {
        val root = playerRoot
        val width = root.width - root.paddingLeft - root.paddingRight - view.width - 2 * margin
        val height = root.height - root.paddingTop - root.paddingBottom - view.height - 2 * margin
        return Offset(width.coerceAtLeast(0).toFloat(), height.coerceAtLeast(0).toFloat())
    }

    private fun placeOverlay() {
        val view = overlay ?: return
        val available = travel(view)
        val point = position ?: Offset(0.5f, if (gameVisible) 0f else 1f)
        view.translationX = available.x * point.x
        view.translationY = available.y * point.y
    }

    private fun moveOverlay(delta: Offset) {
        val view = overlay ?: return
        val available = travel(view)
        val previous = position ?: Offset(0.5f, if (gameVisible) 0f else 1f)
        position = Offset(
            if (available.x > 0f) ((view.translationX + delta.x) / available.x).coerceIn(0f, 1f) else previous.x,
            if (available.y > 0f) ((view.translationY + delta.y) / available.y).coerceIn(0f, 1f) else previous.y,
        )
        placeOverlay()
    }

    private fun savePosition() {
        val point = position ?: return
        preferences.edit().putFloat("position_x", point.x).putFloat("position_y", point.y).apply()
    }

    // @JvmStatic keeps these methods on the launcher class named by bridge.c.
    companion object {
        init { System.loadLibrary("cs2_android_bridge") }

        @JvmStatic external fun nativeOpen(game: ByteArray, saves: ByteArray, language: Int, availableMemory: Long): Long
        @JvmStatic external fun nativeClose(handle: Long): Int
        @JvmStatic external fun nativeStep(handle: Long, budget: Int): Int
        @JvmStatic external fun nativeTickDelay(handle: Long, reset: Boolean): Int
        @JvmStatic external fun nativeRetryDelay(handle: Long): Int
        @JvmStatic external fun nativeSize(handle: Long, axis: Int): Int
        @JvmStatic external fun nativePointer(handle: Long, output: IntArray): Int
        @JvmStatic external fun nativeCursorVisibility(handle: Long): Int
        @JvmStatic external fun nativeCursorImage(handle: Long): ByteArray?
        @JvmStatic external fun nativeTextEntry(handle: Long): ByteArray?
        @JvmStatic external fun nativeTextEntryResult(handle: Long, request: Long, value: ByteArray?): Int
        @JvmStatic external fun nativeWindowRequest(handle: Long): Int
        @JvmStatic external fun nativeGeneralWindowPolicy(handle: Long): Int
        @JvmStatic external fun nativeGeneralWindowCaption(handle: Long): ByteArray?
        @JvmStatic external fun nativeWindowMode(handle: Long, windowed: Int): Int
        @JvmStatic external fun nativeRequestFullscreen(handle: Long, fullscreen: Boolean): Int
        @JvmStatic external fun nativeWindowUi(handle: Long): ByteArray?
        @JvmStatic external fun nativeWindowUiAction(handle: Long, request: Long, action: Int, a: Int, b: Int, c: Int): Int
        @JvmStatic external fun nativeSettingsApply(handle: Long, request: Long, record: ByteArray): Int
        @JvmStatic external fun nativeSettingsFontPreview(handle: Long, request: Long, face: Int, bold: Int): ByteArray?
        @JvmStatic external fun nativeClipboard(handle: Long): CharArray?
        @JvmStatic external fun nativeMessageLists(handle: Long): ByteArray?
        @JvmStatic external fun nativeMessageListAction(handle: Long, task: Int, operation: Int, value: Int): Int
        @JvmStatic external fun nativeSceneTools(handle: Long): ByteArray?
        @JvmStatic external fun nativeSceneToolAction(handle: Long, task: Int, operation: Int, parameter: Int, value: Int): Int
        @JvmStatic external fun nativeSystemOpen(handle: Long): ByteArray?
        @JvmStatic external fun nativeSystemOpenResult(handle: Long, request: Long, failure: ByteArray?): Int
        @JvmStatic external fun nativeDialog(handle: Long): ByteArray?
        @JvmStatic external fun nativeDialogResult(handle: Long, request: Long, shown: Int): Int
        @JvmStatic external fun nativeInput(handle: Long, kind: Int, a: Int, b: Int): Int
        @JvmStatic external fun nativeEnqueueInput(handle: Long, kind: Int, a: Int, b: Int, eventToUiMs: Long, inputUiMonoUs: Long): Int
        @JvmStatic external fun nativeTimedPrimaryDown(handle: Long, eventToUiMs: Long, uiToWorkerUs: Long): Int
        @JvmStatic external fun nativeText(handle: Long, kind: Int, text: ByteArray, start: Int, end: Int): Int
        @JvmStatic external fun nativePublishFrame(handle: Long, revision: Long): Int
        @JvmStatic external fun nativePresent(handle: Long, surface: Surface, output: LongArray): Int
        @JvmStatic external fun nativeRecordPresent(handle: Long, output: LongArray)
        @JvmStatic external fun nativeDetachSurface(): Int
        @JvmStatic external fun nativeSetHudEnabled(enabled: Boolean)
        @JvmStatic external fun nativeSetDisplayStatsEnabled(enabled: Boolean)
        @JvmStatic external fun nativeReadDisplayStats(handle: Long, output: LongArray): Int
        @JvmStatic external fun nativeReadHud(output: LongArray): Int
        @JvmStatic external fun nativeError(): ByteArray?
        @JvmStatic external fun nativeDiagnostic(handle: Long): ByteArray?
    }
}
