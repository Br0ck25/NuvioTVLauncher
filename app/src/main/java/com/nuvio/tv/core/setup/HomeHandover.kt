package com.nuvio.tv.core.setup

/**
 * The system-level part of becoming the Home app, with no accessibility service involved.
 *
 * This is the same mechanism the other Android TV launchers use: the app declares
 * `MAIN`/`HOME`/`DEFAULT` on a `singleTask` activity, and the Home preference is then pointed at
 * that activity. FLauncher and ArcLauncher leave that choice to the system Home-app picker; the
 * ADB route below is what ArcLauncher's install tooling does, minus the parts that need a computer:
 *
 * 1. ask the TV which component currently owns Home, instead of guessing a manufacturer package;
 * 2. `cmd package set-home-activity --user 0 <pkg>/<activity>`;
 * 3. re-resolve Home and only accept the switch when it really points at us;
 * 4. put the previous Home back when it does not, because a TV whose new Home does not start can
 *    only be rescued over ADB;
 * 5. optionally `pm disable-user` the stock launcher, which is the blunt fallback both launcher
 *    READMEs document, and re-enable it on the way back out.
 *
 * Everything here is pure so the ordering and the recovery commands can be unit tested.
 */
internal object HomeHandover {

    /** Which package Home resolves to, as reported by the TV. */
    internal enum class HomeOwner { OURS, KNOWN_STOCK, OTHER_LAUNCHER, SYSTEM_CHOOSER, SETUP_WIZARD, NONE }

    /**
     * Launchers this setup is willing to disable. Anything else — another third-party launcher, the
     * system chooser, a setup wizard — is left alone, because disabling the wrong package can leave
     * the TV without a usable Home.
     */
    val knownStockLaunchers: Set<String> = setOf(
        "com.google.android.tvlauncher",              // Android TV (Sony, Philips, older Chromecast)
        "com.google.android.apps.tv.launcherx",       // Google TV / Chromecast with Google TV
        "com.google.android.leanbacklauncher",        // older Android TV
        "com.amazon.tv.launcher",                     // Fire TV
        "com.mitv.tvhome",                            // Xiaomi / MIUI TV
        "com.sony.dtv.osd.launcher"                   // some Sony builds
    )

    /**
     * Packages that re-assert the stock launcher after it is disabled. Google TV's setup wizard
     * re-enables `launcherx`, so disabling only the launcher silently undoes itself.
     */
    val knownLauncherRestorers: Set<String> = setOf(
        "com.google.android.tungsten.setupwraith"
    )

    /** Android's own chooser: two or more Home apps tie and no default has been picked. */
    const val SYSTEM_CHOOSER_PACKAGE = "android"

    internal data class Plan(
        val setHome: String?,
        val verifyHome: String,
        val disable: List<String> = emptyList(),
        val rollback: List<String> = emptyList(),
        val recovery: List<String> = emptyList()
    )

    internal fun classify(homePackage: String?, ourPackage: String): HomeOwner = when {
        homePackage.isNullOrBlank() -> HomeOwner.NONE
        homePackage == ourPackage -> HomeOwner.OURS
        homePackage == SYSTEM_CHOOSER_PACKAGE -> HomeOwner.SYSTEM_CHOOSER
        homePackage in knownLauncherRestorers -> HomeOwner.SETUP_WIZARD
        homePackage in knownStockLaunchers -> HomeOwner.KNOWN_STOCK
        else -> HomeOwner.OTHER_LAUNCHER
    }

    /**
     * Builds the command sequence for one setup run.
     *
     * @param homeComponent what Home resolves to right now, `pkg/activity`, or null when the TV
     * could not answer. It becomes the rollback target, so it is never allowed to be us.
     * @param disableStockLauncher opt-in step; only ever runs after Home already resolves to us.
     */
    internal fun plan(
        ourComponent: String,
        ourPackage: String,
        homeComponent: String?,
        disableStockLauncher: Boolean
    ): Plan {
        val homePackage = homeComponent?.substringBefore('/', "")?.takeIf { it.isNotBlank() }
        val previousHome = homeComponent?.takeIf { homePackage != null && homePackage != ourPackage }

        // Already Home: nothing to switch, nothing to roll back to.
        if (homePackage == ourPackage) {
            return Plan(
                setHome = null,
                verifyHome = resolveHomeCommand(),
                disable = emptyList(),
                rollback = emptyList(),
                recovery = emptyList()
            )
        }

        val rollback = mutableListOf<String>()
        if (previousHome != null) {
            // Re-enable first: a disabled package cannot be the Home activity.
            rollback += enableCommand(previousHome.substringBefore('/'))
            rollback += setHomeActivityCommand(previousHome)
        }

        return Plan(
            setHome = setHomeActivityCommand(ourComponent),
            verifyHome = resolveHomeCommand(),
            // Only a stock launcher we recognise, and only once Home is ours: if the switch did not
            // take, the stock launcher is still there for the TV to fall back to.
            disable = if (disableStockLauncher) disableTargets(previousHome, ourPackage) else emptyList(),
            rollback = rollback,
            recovery = previousHomeRecovery(previousHome, ourPackage)
        )
    }

    /** The stock launcher to disable, plus whatever would silently re-enable it. */
    private fun disableTargets(homeComponent: String?, ourPackage: String): List<String> {
        val homePackage = homeComponent?.substringBefore('/', "") ?: return emptyList()
        val stock = when (classify(homePackage, ourPackage)) {
            HomeOwner.KNOWN_STOCK -> homePackage
            // Home is the chooser, another launcher or unknown: disable nothing rather than guess.
            else -> return emptyList()
        }
        return buildList {
            add(disableCommand(stock))
            knownLauncherRestorers.forEach { add(disableCommand(it)) }
        }
    }

    /** What the user can run from a computer if this TV stops answering after a handover. */
    private fun previousHomeRecovery(homeComponent: String?, ourPackage: String): List<String> {
        val homePackage = homeComponent?.substringBefore('/', "") ?: return emptyList()
        if (classify(homePackage, ourPackage) != HomeOwner.KNOWN_STOCK) return emptyList()
        return buildList {
            add("adb shell pm enable --user 0 $homePackage")
            knownLauncherRestorers.forEach { add("adb shell pm enable --user 0 $it") }
            add(
                "adb shell cmd package set-home-activity --user 0 \$(adb shell cmd package " +
                    "resolve-activity --brief -a android.intent.action.MAIN " +
                    "-c android.intent.category.HOME $homePackage | tail -n 1)"
            )
        }
    }

    fun resolveHomeCommand(): String =
        "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"

    /** `--user 0` because the Home preference is per-user and TV profiles are not always user 0. */
    fun setHomeActivityCommand(component: String): String =
        "cmd package set-home-activity --user 0 $component"

    fun disableCommand(packageName: String): String = "pm disable-user --user 0 $packageName"

    fun enableCommand(packageName: String): String = "pm enable --user 0 $packageName"

    /** Grants the setup needs to keep working after a reboot or an app update. */
    fun grantWriteSecureSettingsCommand(packageName: String): String =
        "pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"

    /**
     * Android 13+ hides accessibility and other privileged toggles from sideloaded apps until this
     * op is allowed. Unknown on older firmware, so it is best effort.
     */
    fun allowRestrictedSettingsCommand(packageName: String): String =
        "appops set $packageName ACCESS_RESTRICTED_SETTINGS allow"

    /** Compiles the app ahead of first Home launch, so the first HOME press is not slow. */
    fun compileCommand(packageName: String): String = "cmd package compile -m speed -f $packageName"

    /** `cmd package resolve-activity --brief` prints a header line, then `pkg/activity`. */
    fun parseResolvedComponent(output: String?): String? =
        output?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.contains('/') && !it.startsWith("priority=") && " " !in it }

    /**
     * Expands the short forms `cmd package resolve-activity` can print, so a resolved component can
     * be compared with the one this app set: `pkg/.MainActivity` and `pkg/MainActivity` both mean
     * `pkg/<full.activity.Name>`.
     */
    fun normalizeComponent(component: String, packageName: String): String {
        val separator = component.indexOf('/')
        if (separator < 0) return component
        val pkg = component.substring(0, separator).ifBlank { packageName }
        val activity = component.substring(separator + 1).removeSuffix("/")
        val expanded = when {
            activity.startsWith(".") -> pkg + activity
            '.' !in activity -> "$pkg.$activity"
            else -> activity
        }
        return "$pkg/$expanded"
    }

    /** True when Home now resolves to [ourComponent], in whichever form the TV printed it. */
    fun homeIsOurs(resolvedComponent: String?, ourComponent: String): Boolean {
        if (resolvedComponent.isNullOrBlank()) return false
        val packageName = ourComponent.substringBefore('/')
        return normalizeComponent(resolvedComponent, packageName) == normalizeComponent(ourComponent, packageName)
    }
}
