package com.nuvio.tv.core.launcher

/**
 * One-time, on-device setup that replaces the manual computer-side ADB steps. The implementation
 * pairs with the TV's own Wireless debugging service over loopback and runs the grants itself.
 * Only the `full` flavor ships an implementation; other flavors get `null` from the factory.
 */
internal interface LocalAdbSetup {
    /**
     * @param pairingPort port shown in "Pair device with pairing code", or null if this TV was
     * already paired with Nuvio (then only connecting is attempted)
     * @param pairingCode 6-digit code shown in the same dialog, or null together with [pairingPort]
     * @param connectPort optional port from the main Wireless debugging screen; when null the
     * connection is discovered automatically.
     */
    suspend fun setUp(pairingPort: Int?, pairingCode: String?, connectPort: Int?): Result<Unit>
}

