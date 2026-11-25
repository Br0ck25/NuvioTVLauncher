package com.nuvio.tv.core.player

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Temporarily lowers a VOD buffer's allocation target when Android reports memory pressure.
 * Existing buffered media is not discarded; only idle allocator segments are trimmed.
 */
@UnstableApi
internal class PlaybackBufferMemoryGuard(
    context: Context,
    private val control: BitrateAwareLoadControl,
    private val managedBudget: Boolean = true,
    private val outsideBudgetMb: Int = 0,
    private val fourKInAppMemory: Boolean = false,
) {
    private val appContext = context.applicationContext
    private val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    private val baseBytes = control.configuredBudgetBytes.coerceAtLeast(0L)
    private val floorBytes = minOf(baseBytes, MIN_BUFFER_FLOOR_BYTES)
    @Volatile private var capBytes = -1L
    @Volatile private var healthySinceMs = 0L
    @Volatile private var pressureSinceMs = 0L
    @Volatile private var roomCapBytes = -1L
    @Volatile private var detached = false
    private var recoveryTask: ScheduledFuture<*>? = null

    private val callbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) = handleTrim(level)
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = handleTrim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    fun attach() {
        appContext.registerComponentCallbacks(callbacks)
        if (managedBudget) {
            readMemoryInfo()?.let { info ->
                val heldMb = control.bufferAllocator.totalBytesAllocated / MB
                val sizedBytes = PlaybackBufferMemoryPolicy.sizedTargetBytes(
                    ceilingBytes = baseBytes,
                    availableBytes = info.availMem,
                    thresholdBytes = info.threshold,
                    heldBytes = control.bufferAllocator.totalBytesAllocated.toLong(),
                    outsideBudgetBytes = outsideBudgetMb.coerceAtLeast(0).toLong() * MB,
                    videoReserveBytes = (if (fourKInAppMemory) VIDEO_RESERVE_4K_MB else VIDEO_RESERVE_MB) * MB,
                    floorBytes = floorBytes,
                    lowMemory = info.lowMemory,
                )
                if (sizedBytes < baseBytes) {
                    applyCap(sizedBytes)
                    Log.i(TAG, "Buffer sized to current headroom targetMb=${sizedBytes / MB} heldMb=$heldMb")
                }
            }
        }
        recoveryTask = WORKER.scheduleWithFixedDelay(
            { runCatching { checkMemory() } },
            RECOVERY_CHECK_MS,
            RECOVERY_CHECK_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    fun detach() {
        detached = true
        recoveryTask?.cancel(false)
        recoveryTask = null
        runCatching { appContext.unregisterComponentCallbacks(callbacks) }
        capBytes = -1L
        roomCapBytes = -1L
        applyEffectiveCap(trimIdle = false)
    }

    private fun handleTrim(level: Int) {
        if (detached || level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
        if (!managedBudget && level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) return
        healthySinceMs = 0L
        if (pressureSinceMs == 0L) pressureSinceMs = android.os.SystemClock.elapsedRealtime()
        val critical = level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        lowerForPressure(critical)
        Log.w(TAG, "Buffer memory pressure level=$level capMb=${effectiveCapMb()} baseMb=${baseBytes / MB}")
    }

    private fun checkMemory() {
        if (detached || activityManager == null) return
        val info = readMemoryInfo() ?: return
        updateAddressSpaceCap()
        val room = info.availMem - info.threshold
        val pressured = info.lowMemory || room < PRESSURE_HEADROOM_BYTES
        val now = android.os.SystemClock.elapsedRealtime()
        if (pressured) {
            healthySinceMs = 0L
            if (pressureSinceMs == 0L) pressureSinceMs = now
            if ((managedBudget && (capBytes < 0L || now - pressureSinceMs >= ESCALATE_AFTER_MS)) ||
                (!managedBudget && info.lowMemory && capBytes < 0L)
            ) {
                lowerForPressure(critical = info.lowMemory || now - pressureSinceMs >= ESCALATE_AFTER_MS)
                Log.w(TAG, "Buffer pressure check lowMemory=${info.lowMemory} roomMb=${room / MB} capMb=${effectiveCapMb()}")
            }
            return
        }
        pressureSinceMs = 0L
        recoverWhenHealthy(room, now)
    }

    private fun recoverWhenHealthy(room: Long, now: Long) {
        if (capBytes < 0L) return
        if (room < RECOVERY_HEADROOM_BYTES) {
            healthySinceMs = 0L
            return
        }
        if (healthySinceMs == 0L) {
            healthySinceMs = now
            return
        }
        if (now - healthySinceMs < RECOVERY_QUIET_MS) return
        val current = capBytes
        val next = PlaybackBufferMemoryPolicy.stepUp(current, baseBytes, GROW_STEP_BYTES)
        applyCap(next)
        healthySinceMs = now
        Log.i(TAG, "Buffer memory pressure eased capMb=${if (next < 0L) baseBytes else next / MB}")
    }

    private fun lowerForPressure(critical: Boolean) {
        val current = capBytes.takeIf { it >= 0L } ?: baseBytes
        val next = PlaybackBufferMemoryPolicy.stepDown(current, floorBytes, critical)
        if (next < current) applyCap(next)
    }

    private fun readMemoryInfo(): ActivityManager.MemoryInfo? = runCatching {
        val manager = activityManager ?: return null
        ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
    }.getOrNull()

    private fun effectiveCapMb(): Long = effectiveCapBytes() / MB

    private fun updateAddressSpaceCap() {
        val next = runCatching {
            PlaybackBufferMemoryPolicy.addressSpaceCapBytes(
                is64Bit = android.os.Process.is64Bit(),
                usedAddressSpaceMb = readVmSizeMb(),
                addressSpaceLimitMb = readAddressSpaceLimitMb(),
                heldBytes = control.bufferAllocator.totalBytesAllocated.toLong(),
                ceilingBytes = baseBytes,
                floorBytes = floorBytes,
            )
        }.getOrNull()?.takeIf { it < baseBytes } ?: -1L
        if (next == roomCapBytes) return
        val previous = effectiveCapBytes()
        roomCapBytes = next
        applyEffectiveCap(trimIdle = effectiveCapBytes() < previous)
        Log.i(TAG, "Buffer address-space cap ${if (next < 0L) "off" else "${next / MB} MB"}")
    }

    private fun effectiveCapBytes(): Long {
        val memoryCap = capBytes.takeIf { it >= 0L } ?: baseBytes
        val addressCap = roomCapBytes.takeIf { it >= 0L } ?: baseBytes
        return minOf(memoryCap, addressCap)
    }

    private fun readVmSizeMb(): Long? = runCatching {
        java.io.File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmSize:") }
                ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()?.div(1024L)
        }
    }.getOrNull()

    private fun readAddressSpaceLimitMb(): Long? = runCatching {
        val gb = 1024L * MB
        java.io.File("/proc/self/maps").useLines { lines ->
            val stack = lines.firstOrNull { it.trimEnd().endsWith("[stack]") } ?: return null
            val endHex = stack.substringBefore(' ').substringAfter('-')
            val end = endHex.toLongOrNull(16) ?: return null
            (((end + gb - 1L) / gb) * 1024L).coerceIn(1024L, 4096L)
        }
    }.getOrNull()

    private fun applyCap(next: Long) {
        capBytes = next.takeIf { it < baseBytes } ?: -1L
        applyEffectiveCap(trimIdle = true)
    }

    private fun applyEffectiveCap(trimIdle: Boolean) {
        val effectiveCap = effectiveCapBytes().takeIf { it < baseBytes } ?: -1L
        control.setMemoryPressureCapBytes(effectiveCap)
        val allocator = control.bufferAllocator
        synchronized(allocator) {
            // Drop only free segments. Allocated media remains available to the active player.
            if (trimIdle) {
                allocator.setTargetBufferSize(allocator.totalBytesAllocated)
                allocator.trim()
            }
            allocator.setTargetBufferSize(control.allocatorTargetBytes())
        }
    }

    private companion object {
        const val TAG = "PlaybackMemory"
        const val MB = 1024L * 1024L
        const val MIN_BUFFER_FLOOR_BYTES = 50L * MB
        const val RECOVERY_HEADROOM_BYTES = 150L * MB
        const val PRESSURE_HEADROOM_BYTES = 100L * MB
        const val RECOVERY_CHECK_MS = 15_000L
        const val RECOVERY_QUIET_MS = 60_000L
        const val ESCALATE_AFTER_MS = 30_000L
        const val GROW_STEP_BYTES = 50L * MB
        const val VIDEO_RESERVE_MB = 140L
        const val VIDEO_RESERVE_4K_MB = 220L
        val WORKER = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "playback-buffer-memory").apply { isDaemon = true }
        }
    }
}

internal object PlaybackBufferMemoryPolicy {
    fun sizedTargetBytes(
        ceilingBytes: Long,
        availableBytes: Long,
        thresholdBytes: Long,
        heldBytes: Long,
        outsideBudgetBytes: Long,
        videoReserveBytes: Long,
        floorBytes: Long,
        lowMemory: Boolean,
    ): Long {
        val ceiling = ceilingBytes.coerceAtLeast(0L)
        val floor = floorBytes.coerceIn(0L, ceiling)
        if (lowMemory) return floor
        val spare = availableBytes - thresholdBytes.coerceAtLeast(0L) - 28L * 1024L * 1024L -
            videoReserveBytes.coerceAtLeast(0L) - outsideBudgetBytes.coerceAtLeast(0L) + heldBytes.coerceAtLeast(0L)
        return spare.coerceIn(floor, ceiling)
    }

    fun addressSpaceCapBytes(
        is64Bit: Boolean,
        usedAddressSpaceMb: Long?,
        addressSpaceLimitMb: Long?,
        heldBytes: Long,
        ceilingBytes: Long,
        floorBytes: Long,
        spareMb: Long = 796L,
    ): Long? {
        if (is64Bit) return null
        val used = usedAddressSpaceMb ?: return null
        val limit = addressSpaceLimitMb ?: return null
        val remainingMb = (limit - spareMb - used).coerceAtLeast(0L)
        val floor = floorBytes.coerceIn(0L, ceilingBytes.coerceAtLeast(0L))
        return (heldBytes.coerceAtLeast(0L) + remainingMb * 1024L * 1024L)
            .coerceIn(floor, ceilingBytes.coerceAtLeast(floor))
    }

    fun stepDown(currentBytes: Long, floorBytes: Long, critical: Boolean): Long {
        val current = currentBytes.coerceAtLeast(0L)
        val floor = floorBytes.coerceIn(0L, current)
        val share = if (critical) 2L else 4L
        return (current - (current - floor) / share).coerceAtLeast(floor)
    }

    /** Returns -1 when the configured ceiling has been fully restored. */
    fun stepUp(currentBytes: Long, ceilingBytes: Long, stepBytes: Long): Long =
        if (currentBytes >= ceilingBytes - stepBytes) -1L
        else (currentBytes + stepBytes).coerceAtMost(ceilingBytes)
}
