package com.nuvio.tv.core.setup

/** What one setup run should change on the TV. Chosen on the TV or on the phone page. */
internal enum class SetupMode {
    /** Point the system Home preference at Nuvio, verify it and roll back when it did not take. */
    HOME_APP,

    /** Only apply the ADB grants. Leaves the Home app untouched. */
    GRANTS_ONLY
}

/** What a finished run actually left behind, so the UI can report facts instead of exit codes. */
internal data class SetupOutcome(
    val grantsApplied: Boolean,
    val writeSecureSettingsGranted: Boolean,
    val homeComponent: String?,
    val homeIsNuvio: Boolean,
    val rolledBack: Boolean,
    val disabledPackages: List<String> = emptyList(),
    val recoveryCommands: List<String> = emptyList()
) {
    companion object {
        val Idle = SetupOutcome(
            grantsApplied = false,
            writeSecureSettingsGranted = false,
            homeComponent = null,
            homeIsNuvio = false,
            rolledBack = false
        )
    }
}

/**
 * One-time, on-device setup that replaces the manual computer-side ADB steps. The implementation
 * pairs with the TV's own Wireless debugging service over loopback and runs the commands itself.
 * Only the `full` flavor ships an implementation; other flavors get `null` from the factory.
 */
internal interface LocalAdbSetup {
    /**
     * @param pairingPort port shown in "Pair device with pairing code", or null if this TV was
     * already paired with Nuvio (then only connecting is attempted)
     * @param pairingCode 6-digit code shown in the same dialog, or null together with [pairingPort]
     * @param connectPort optional port from the main Wireless debugging screen; when null the
     * connection is discovered automatically
     * @param mode whether to hand Home over to Nuvio or only apply the grants
     * @param disableStockLauncher opt-in extra for firmware that ignores the Home preference; it only
     * runs after Home already resolves to Nuvio, and is undone by any rollback
     */
    suspend fun setUp(
        pairingPort: Int?,
        pairingCode: String?,
        connectPort: Int?,
        mode: SetupMode = SetupMode.HOME_APP,
        disableStockLauncher: Boolean = false
    ): Result<SetupOutcome>
}
