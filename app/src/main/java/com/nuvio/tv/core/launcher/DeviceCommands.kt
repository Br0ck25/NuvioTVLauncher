package com.nuvio.tv.core.launcher

/** What a button on the phone setup page does on this TV. Each runs over the TV's own Wireless debugging. */
enum class DeviceAction(val id: String) {
    /** Make this app the Home app (`cmd package set-home-activity`). */
    MAKE_HOME("make-home"),

    /** Turn on this app's accessibility service so the Home button always opens it. */
    ENABLE_OVERRIDE("enable-override"),

    /** Report which launcher the Home button opens right now. */
    CHECK_HOME("check-home"),

    /** FLauncher's way: turn off the Google TV launcher (and its fallback) so only this launcher is left. */
    DISABLE_STOCK("disable-stock"),

    /** Let this launcher read notifications, for the bell at the top right of Home. */
    ENABLE_NOTIFICATIONS("enable-notifications"),

    /** Undo [DISABLE_STOCK]. */
    ENABLE_STOCK("enable-stock"),

    /** Point the Home button back at the launcher the TV came with. */
    RESTORE_ORIGINAL("restore-original");

    companion object {
        fun fromId(id: String?): DeviceAction? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The exact shell commands behind each [DeviceAction], with no Android dependencies so they can be
 * tested. They are the standard ones from FLauncher's and ArcLauncher's setup guides.
 */
object DeviceCommands {
    const val GOOGLE_TV_LAUNCHER = "com.google.android.apps.tv.launcherx"
    const val GOOGLE_TV_SETUP_FALLBACK = "com.google.android.tungsten.setupwraith"
    const val HOME_QUERY = "-c android.intent.category.HOME -a android.intent.action.MAIN"

    fun setHome(component: String) = "cmd package set-home-activity $component"
    /** Grants the notification-listener access that normally needs a trip through Settings. */
    fun allowNotificationListener(component: String) = "cmd notification allow_listener $component"
    val resolveHome = "cmd package resolve-activity $HOME_QUERY"
    val listHomes = "cmd package query-activities $HOME_QUERY"
    // Only the launcher itself. Google TV's setup service (GOOGLE_TV_SETUP_FALLBACK) is left alone:
    // turning it off took a TV's Wi-Fi connection down. Turning the launcher back on still re-enables both.
    val disableStock = listOf(
        "pm disable-user --user 0 $GOOGLE_TV_LAUNCHER"
    )
    val enableStock = listOf(
        "pm enable $GOOGLE_TV_LAUNCHER",
        "pm enable $GOOGLE_TV_SETUP_FALLBACK"
    )

    /**
     * `enabled_accessibility_services` with [service] added (kept if the list already has it), as one
     * colon-separated value the way Android stores it.
     */
    fun withAccessibilityService(current: String?, service: String): String {
        val existing = current?.trim().orEmpty()
        if (existing.isEmpty() || existing == "null") return service
        val parts = existing.split(':').filter { it.isNotBlank() }
        return if (service in parts) existing else (parts + service).joinToString(":")
    }

    /**
     * The first Home activity in `cmd package query-activities` output that does not belong to
     * [ownPackage], as `package/activity`; null when this app is the only launcher.
     */
    fun otherHomeComponent(queryOutput: String, ownPackage: String): String? {
        var name: String? = null
        for (line in queryOutput.lineSequence()) {
            val t = line.trim()
            when {
                t.startsWith("name=") -> name = t.removePrefix("name=").trim()
                t.startsWith("packageName=") -> {
                    val pkg = t.removePrefix("packageName=").trim()
                    val activity = name
                    name = null
                    if (pkg != ownPackage && pkg.isNotEmpty() && !activity.isNullOrEmpty()) {
                        return "$pkg/$activity"
                    }
                }
            }
        }
        return null
    }

    /** `packageName=` of the resolved Home activity in `resolve-activity` output, for the status line. */
    fun resolvedHomePackage(resolveOutput: String): String? =
        resolveOutput.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("packageName=") }?.removePrefix("packageName=")?.trim()?.takeIf { it.isNotEmpty() }
}
