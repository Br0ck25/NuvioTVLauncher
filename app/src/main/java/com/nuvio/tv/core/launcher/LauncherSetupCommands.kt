package com.nuvio.tv.core.launcher

/** One command in the "from a computer" reference on the setup page. */
data class SetupCommand(val title: String, val note: String, val command: String)

/** A group of commands in that reference. */
data class SetupSection(val title: String, val intro: String, val commands: List<SetupCommand>, val warning: String? = null)

/**
 * The same commands the phone page's buttons run, written out for someone running ADB from a
 * computer. Every command names this TV (`-s IP:PORT`), so with several devices attached it still
 * goes to the right one. [tvIp] is this TV's address on the home network.
 */
object LauncherSetupCommands {
    fun sections(component: String, tvIp: String?, listenerComponent: String? = null): List<SetupSection> {
        val ip = tvIp ?: "TV_IP"
        val target = "$ip:PORT"
        fun adb(shell: String) = "adb -s $target shell $shell"
        fun adbAll(shell: List<String>) = shell.joinToString("\n") { adb(it) }
        return listOf(
            SetupSection(
                title = "Connect ADB to this TV",
                intro = "Use the pairing address and code from Developer options → Wireless debugging → Pair device with pairing code, then connect with the address on the main Wireless debugging screen (its port is different). Replace PORT below with that connection port.",
                commands = listOf(
                    SetupCommand("Wireless debugging (Android 11 and newer)", "", "adb pair $ip:PAIRING_PORT\nadb connect $target"),
                    SetupCommand("Network debugging (older TVs, many Fire TVs)", "The port is usually 5555.", "adb connect $ip:5555")
                )
            ),
            SetupSection(
                title = "Make this app the Home app",
                intro = "One command; the stock launcher is untouched, so it is easy to undo.",
                commands = listOf(
                    SetupCommand("Set this app as the Home app", "", adb(DeviceCommands.setHome(component))),
                    SetupCommand("Check which launcher is Home right now", "", adb(DeviceCommands.resolveHome)),
                    SetupCommand("List the launchers on the TV", "Copy the original launcher's package/activity from this list to go back.", adb(DeviceCommands.listHomes)),
                    *(listenerComponent?.let {
                        arrayOf(SetupCommand("Turn on notifications", "For the bell at the top right of Home.", adb(DeviceCommands.allowNotificationListener(it))))
                    } ?: emptyArray()),
                    SetupCommand("Go back to the original launcher", "Replace the placeholder with the entry you copied.", adb("cmd package set-home-activity <package>/<activity>"))
                )
            ),
            SetupSection(
                title = "Keep the app running in the background",
                intro = "Stops Android limiting this app while the TV is idle, so scheduled recordings, guide and playlist updates and Drive sync run on time. Uses a little more power. Android may reset the standby setting after a restart; run it again if recordings stop firing.",
                commands = listOf(
                    SetupCommand("Keep this app running", "Four commands: standby bucket, the two background app-ops, and the Doze exemption.", adbAll(DeviceCommands.keepRunning(component.substringBefore('/')))),
                    SetupCommand("Check it took", "The app's package should be in this list.", adb(DeviceCommands.listDozeWhitelist)),
                    SetupCommand("Make the app start faster", "Compiles the app ahead of time (about half the start-up time on a TiVo Stream 4K). Takes a minute or two. An app update undoes it, so run it again after updating.", adb(DeviceCommands.optimizeSpeed(component.substringBefore('/'))))
                )
            ),
            SetupSection(
                title = "Optional: turn off the stock launcher (Chromecast with Google TV)",
                intro = "Only if the TV keeps going back to its own launcher after the Home button override is on. This is FLauncher's method, and it is reversible.",
                warning = "Most people should leave the TV's own launcher on: if it is on and the Home button override ever switches off, Home just opens Google TV. With it turned off or uninstalled, nothing else shows Home, so a lost override means a black screen or a loop on a recovery screen. Run the permission command below first: it lets this app switch its own Home button override back on after every restart. The remote's YouTube button also stops working (a button-remapping app such as Button Mapper, flar2.homebutton, can map it again). Only use this if the Home button override does not work. The re-enable command below undoes it.",
                commands = listOf(
                    SetupCommand("Let the app keep its Home button override on (run this first)", "Gives this app the permission to switch its own accessibility setting back on after a restart.", adb("pm grant ${component.substringBefore('/')} android.permission.WRITE_SECURE_SETTINGS")),
                    SetupCommand("Disable the stock launcher", "${DeviceCommands.GOOGLE_TV_LAUNCHER} is the default launcher.", adbAll(DeviceCommands.disableStock)),
                    SetupCommand("Uninstall it for this user (stays off after restart)", "On a Chromecast with Google TV the disable step above is undone at the next restart; this one stays. Riskier: with it removed and the override lost, Home is a black screen.", adbAll(DeviceCommands.uninstallStock)),
                    SetupCommand("Re-enable it", "Undoes both steps above (also brings back an uninstalled launcher). Harmless if it is already on.", adbAll(DeviceCommands.enableStock)),
                    SetupCommand("Check who holds the Home role", "", adb(DeviceCommands.roleHolders))
                )
            )
        )
    }
}
