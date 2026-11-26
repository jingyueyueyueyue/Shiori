package org.catsystem2.player

internal class FrameRateSampler(private val windowNanos: Long = 2_000_000_000L) {
    private data class Reading(val time: Long, val count: Long)
    private val readings = ArrayDeque<Reading>()

    fun sample(nowNanos: Long, frameCount: Long?): Float? {
        if (frameCount == null) {
            reset()
            return null
        }
        val previous = readings.lastOrNull()
        if (previous != null) {
            if (nowNanos <= previous.time) {
                reset()
                return null
            }
            if (frameCount < previous.count || nowNanos - previous.time > 2 * windowNanos) reset()
        }
        readings.addLast(Reading(nowNanos, frameCount))
        val cutoff = nowNanos - windowNanos
        while (readings.size > 2 && (readings[1].time <= cutoff || readings.size > 8)) {
            readings.removeFirst()
        }
        val first = readings.first()
        if (first.time == nowNanos) return null
        // Use the nearest observation before the cutoff to interpolate the
        // start count. Poll jitter otherwise shortens the window by a full sample.
        val start = maxOf(cutoff, first.time)
        val next = readings[1]
        val fraction = (start - first.time).toDouble() / (next.time - first.time)
        val frames = (frameCount - first.count) - (next.count - first.count) * fraction
        return (frames * 1_000_000_000.0 / (nowNanos - start)).toFloat()
    }

    fun reset() {
        readings.clear()
    }
}
