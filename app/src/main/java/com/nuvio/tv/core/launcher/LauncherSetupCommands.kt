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
                title = "If the Home screen looks broken",
                intro = "This app's Home takeover needs the TV's own launcher to stay enabled. Do not turn the stock launcher off: on a Chromecast with Google TV that made Android loop on a recovery screen.",
                commands = listOf(
                    SetupCommand("Make sure the stock launcher is on", "Harmless if it already is.", adbAll(DeviceCommands.enableStock)),
                    SetupCommand("Check who holds the Home role", "", adb(DeviceCommands.roleHolders))
                )
            )
        )
    }
}
