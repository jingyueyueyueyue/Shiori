package org.catsystem2.player

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants

internal enum class GpuUsageStatus {
    AVAILABLE, STALE, NO_WINDOW, DENIED, UNAVAILABLE, INVALID, READ_ERROR,
}

internal data class GpuUsage(
    val status: GpuUsageStatus = GpuUsageStatus.UNAVAILABLE,
    val percent: Float? = null,
    val source: String? = null,
    val ageMs: Long? = null,
    val currentIssue: GpuUsageStatus? = null,
)

internal data class GpuCounter(val path: String, val busyAndTotal: Boolean = false)

internal sealed interface GpuCounterResult {
    data class Read(val text: String) : GpuCounterResult
    data class Failure(val status: GpuUsageStatus) : GpuCounterResult
}

internal class GpuUsageReader(
    counters: List<GpuCounter> = systemCounters,
    private val readCounter: (String) -> GpuCounterResult = ::readGpuCounter,
) {
    private data class Probe(val counter: GpuCounter, var nextAt: Long = 0, var issue: GpuUsageStatus? = null)
    private data class Reading(val at: Long, val value: GpuUsage)

    private val probes = counters.map(::Probe)
    private var previous: Reading? = null

    fun read(now: Long): GpuUsage {
        var issue = GpuUsage()
        for (probe in probes) {
            val path = probe.counter.path
            if (now >= probe.nextAt) {
                val result = readCounter(path)
                val status = when (result) {
                    is GpuCounterResult.Read -> {
                        val parsed = parseGpuCounter(result.text, probe.counter.busyAndTotal)
                        if (parsed.percent != null) {
                            val resultWithSource = parsed.copy(source = path, ageMs = 0)
                            probe.issue = null
                            previous = Reading(now, resultWithSource)
                            return resultWithSource
                        }
                        parsed.status
                    }
                    is GpuCounterResult.Failure -> result.status
                }
                probe.issue = status
                val retryNs = when (status) {
                    GpuUsageStatus.NO_WINDOW -> 0L
                    GpuUsageStatus.DENIED, GpuUsageStatus.UNAVAILABLE -> 30_000_000_000L
                    else -> 5_000_000_000L
                }
                probe.nextAt = now + retryNs
            }
            val status = probe.issue ?: continue
            if (issue.source == null || priority(status) > priority(issue.status)) {
                issue = GpuUsage(status = status, source = path)
            }
        }
        val last = previous ?: return issue
        val age = now - last.at
        if (age !in 0..2_500_000_000L) return issue
        return last.value.copy(status = GpuUsageStatus.STALE, ageMs = age / 1_000_000, currentIssue = issue.status)
    }

    private companion object {
        val systemCounters = listOf(
            GpuCounter("/sys/class/kgsl/kgsl-3d0/gpubusy", busyAndTotal = true),
            GpuCounter("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"),
            GpuCounter("/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load"),
            GpuCounter("/sys/class/misc/mali0/device/utilisation"),
            GpuCounter("/sys/class/misc/mali0/device/utilization"),
            GpuCounter("/sys/devices/platform/mali/utilization"),
            GpuCounter("/sys/devices/platform/gpusysfs/gpu_busy"),
        )

        fun priority(status: GpuUsageStatus): Int = when (status) {
            GpuUsageStatus.NO_WINDOW -> 4
            GpuUsageStatus.DENIED -> 3
            GpuUsageStatus.INVALID -> 2
            GpuUsageStatus.READ_ERROR -> 1
            else -> 0
        }
    }
}

private val counterWhitespace = Regex("\\s+")

internal fun parseGpuCounter(text: String, busyAndTotal: Boolean): GpuUsage {
    if (busyAndTotal) {
        val tokens = text.trim().split(counterWhitespace)
        val busy = tokens.getOrNull(0)?.toLongOrNull()
        val total = tokens.getOrNull(1)?.toLongOrNull()
        if (tokens.size == 2 && busy != null && total != null && busy in 0..total) {
            return if (total == 0L) GpuUsage(GpuUsageStatus.NO_WINDOW)
            else GpuUsage(GpuUsageStatus.AVAILABLE, (busy * 100.0 / total).toFloat())
        }
    } else {
        val value = text.trim().removeSuffix("%").trim().toFloatOrNull()
        if (value != null && value in 0f..100f) return GpuUsage(GpuUsageStatus.AVAILABLE, value)
    }
    return GpuUsage(GpuUsageStatus.INVALID)
}

private fun readGpuCounter(path: String): GpuCounterResult = try {
    val descriptor = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
    try {
        val bytes = ByteArray(128)
        val length = Os.read(descriptor, bytes, 0, bytes.size)
        if (length in 1 until bytes.size) GpuCounterResult.Read(bytes.decodeToString(0, length))
        else GpuCounterResult.Failure(GpuUsageStatus.INVALID)
    } finally {
        Os.close(descriptor)
    }
} catch (error: ErrnoException) {
    GpuCounterResult.Failure(when (error.errno) {
        OsConstants.EACCES, OsConstants.EPERM -> GpuUsageStatus.DENIED
        OsConstants.ENOENT, OsConstants.ENODEV, OsConstants.ENOTDIR -> GpuUsageStatus.UNAVAILABLE
        else -> GpuUsageStatus.READ_ERROR
    })
}
