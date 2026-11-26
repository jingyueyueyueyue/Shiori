package org.catsystem2.player

internal enum class DisplaySource {
    NONE, SOFTWARE, EGL_UNSUPPORTED, EGL_PENDING, EGL_TIMESTAMP,
    VULKAN_UNSUPPORTED, VULKAN_PENDING, VULKAN_TIMESTAMP,
}

/** Layout shared with nativeReadHud in bridge.c. Times are monotonic nanoseconds. */
internal class EngineHudSample(private val fields: LongArray, val sampledAt: Long) {
    val session get() = fields[0]
    val displaySource get() = DisplaySource.entries.getOrElse(fields[1].toInt()) { DisplaySource.NONE }
    val displayGeneration get() = fields[2]
    val displayed get() = fields[3]
    val displayedAt get() = fields[4]
    val queueToDisplayNs get() = fields[5]
    val unresolved get() = fields[6]
    val pending get() = fields[7]
    val submittedAt get() = fields[8]
    val presentNs get() = fields[9]
    val copyNs get() = fields[10]
    val swapNs get() = fields[11]
    val logicNs get() = fields[12]
    val lastLogicAt get() = fields[13]
    val gpuSequence get() = fields[14]
    val gpuNs get() = fields[15]
}

internal data class GamePerformance(
    val active: Boolean = false,
    val logicTps: Float? = null,
    val submittedFps: Float? = null,
    val displayedFps: Float? = null,
    val displaySource: DisplaySource = DisplaySource.NONE,
    val staticFrame: Boolean = false,
    val rasterNs: Long? = null,
    val detail: EngineHudSample? = null,
)

internal class GameRateSampler {
    private val logic = FrameRateSampler()
    private val submitted = FrameRateSampler(windowNanos = 1_000_000_000L)
    private val displayed = FrameRateSampler(windowNanos = 1_000_000_000L)
    private var displayGeneration: Triple<Long, DisplaySource, Long>? = null
    private var displayObservedAt = 0L
    private var displayUnresolved = 0L
    private var displayedRate: Float? = null
    private var gpuSequence: Pair<Long, Long>? = null
    private var gpuObservedAt = 0L

    fun sample(
        now: Long, active: Boolean, ticks: Long, swaps: Long, lastSwapAt: Long, latest: EngineHudSample?,
    ): GamePerformance {
        if (!active) {
            reset()
            return GamePerformance()
        }
        val source = latest?.displaySource ?: DisplaySource.NONE
        val generation = latest?.let { Triple(it.session, source, it.displayGeneration) }
        if (displayGeneration != generation) {
            displayed.reset()
            displayGeneration = generation
            displayObservedAt = 0
            displayUnresolved = latest?.unresolved ?: 0
            displayedRate = null
        }
        val logicRate = logic.sample(now, ticks)
        val submittedRate = submitted.sample(now, swaps)
        if (source != DisplaySource.EGL_TIMESTAMP && source != DisplaySource.VULKAN_TIMESTAMP) {
            displayed.reset()
            displayedRate = null
            displayObservedAt = 0
        } else if (latest != null && latest.sampledAt != displayObservedAt) {
            displayObservedAt = latest.sampledAt
            if (latest.unresolved != displayUnresolved) displayed.reset()
            displayUnresolved = latest.unresolved
            displayedRate = displayed.sample(displayObservedAt, latest.displayed)
                ?.takeUnless { it == 0f && latest.pending > 0 }
        }
        val sequence = latest?.takeIf { it.gpuSequence > 0 }?.let { it.session to it.gpuSequence }
        if (gpuSequence != sequence) {
            gpuSequence = sequence
            gpuObservedAt = latest?.sampledAt ?: 0
        }
        return GamePerformance(
            active = true,
            logicTps = logicRate,
            submittedFps = submittedRate,
            displayedFps = displayedRate,
            displaySource = source,
            staticFrame = logicRate != null && logicRate > 0 && lastSwapAt > 0 && now - lastSwapAt > 1_200_000_000L,
            rasterNs = latest?.gpuNs?.takeIf { sequence != null && now - gpuObservedAt in 0..3_000_000_000L },
            detail = latest,
        )
    }

    fun reset() {
        logic.reset()
        submitted.reset()
        displayed.reset()
        displayGeneration = null
        displayObservedAt = 0
        displayUnresolved = 0
        displayedRate = null
        gpuSequence = null
        gpuObservedAt = 0
    }
}
