package org.catsystem2.player

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Process
import android.os.SystemClock
import kotlin.math.abs

internal data class PerformanceSnapshot(
    val game: GamePerformance = GamePerformance(),
    val gpu: GpuUsage = GpuUsage(),
    val cpuPercent: Float? = null,
    val ramPercent: Float? = null,
    val powerWatts: Float? = null,
    val externalPower: Boolean = false,
)

/** Construct and sample on a background dispatcher. */
internal class GamePerformanceSampler(private val context: Context) {
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private val memory = ActivityManager.MemoryInfo()
    private val processors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private val gpu = GpuUsageReader()
    private var previousTime = SystemClock.elapsedRealtimeNanos()
    private var previousCpu = Process.getElapsedCpuTime()

    fun sample(): PerformanceSnapshot {
        val now = SystemClock.elapsedRealtimeNanos()
        val cpu = Process.getElapsedCpuTime()
        val elapsed = now - previousTime
        val cpuPercent = if (elapsed > 0 && cpu >= previousCpu) {
            ((cpu - previousCpu) * 100_000_000.0 / elapsed / processors).toFloat().coerceIn(0f, 100f)
        } else null
        previousTime = now
        previousCpu = cpu

        val ramPercent = runCatching {
            activityManager?.getMemoryInfo(memory)
            if (memory.totalMem > 0) {
                ((memory.totalMem - memory.availMem) * 100.0 / memory.totalMem)
                    .toFloat().coerceIn(0f, 100f)
            } else null
        }.getOrNull()
        val power = readBatteryPower()
        return PerformanceSnapshot(
            gpu = gpu.read(now),
            cpuPercent = cpuPercent,
            ramPercent = ramPercent,
            powerWatts = power.watts,
            externalPower = power.externalPower,
        )
    }

    private data class BatteryPower(val watts: Float? = null, val externalPower: Boolean = false)

    private fun readBatteryPower(): BatteryPower = runCatching {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return@runCatching BatteryPower()
        val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 ||
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        if (plugged) return@runCatching BatteryPower(externalPower = true)
        if (status != BatteryManager.BATTERY_STATUS_DISCHARGING &&
            status != BatteryManager.BATTERY_STATUS_NOT_CHARGING
        ) return@runCatching BatteryPower()
        val voltageMv = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val currentUa = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?: return@runCatching BatteryPower()
        if (currentUa == Int.MIN_VALUE || currentUa == 0 || voltageMv <= 0) return@runCatching BatteryPower()
        val watts = abs(currentUa.toLong()) * voltageMv / 1_000_000_000.0
        BatteryPower(watts = watts.toFloat())
    }.getOrDefault(BatteryPower())
}
