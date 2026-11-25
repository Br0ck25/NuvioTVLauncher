package com.nuvio.tv.livetv.player

/** Keeps IPTV download-ahead from exceeding the configured byte budget on small-RAM devices. */
internal object LiveTvBufferPolicy {
    data class Budget(val targetBytes: Int, val prioritizeTimeOverSize: Boolean)

    fun forDevice(effectiveHeapMb: Long, physicalRamBytes: Long): Budget {
        val heapMb = effectiveHeapMb.takeIf { it > 0L } ?: 512L
        val target = ((heapMb shl 20) * 3L / 10L).coerceIn(32L shl 20, 160L shl 20).toInt()
        // A small device can report a generous Java heap that its physical RAM cannot sustain.
        // Keep the time threshold from overriding the byte cap in that case.
        val constrainedDevice = physicalRamBytes in 1..(2L shl 30)
        return Budget(target, prioritizeTimeOverSize = heapMb >= 512L && !constrainedDevice)
    }
}
