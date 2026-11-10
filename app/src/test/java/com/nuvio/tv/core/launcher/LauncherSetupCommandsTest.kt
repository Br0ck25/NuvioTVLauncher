package com.nuvio.tv.core.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherSetupCommandsTest {
    private val component = "com.nuvio.livetv/com.nuvio.tv.MainActivity"
    private val all get() = LauncherSetupCommands.sections(component, "192.168.1.50").flatMap { it.commands }.map { it.command }

    @Test
    fun setHomeCommandUsesThisAppsComponent() {
        assertTrue("adb shell cmd package set-home-activity $component" in all)
    }

    @Test
    fun includesArcStyleCheckAndRestoreCommands() {
        assertTrue(all.any { it.startsWith("adb shell cmd package resolve-activity -c android.intent.category.HOME") })
        assertTrue(all.any { it.startsWith("adb shell cmd package query-activities -c android.intent.category.HOME") })
        assertTrue("adb shell cmd package set-home-activity <package>/<activity>" in all)
    }

    @Test
    fun includesFLauncherDisableAndReenableCommands() {
        val disable = listOf("com.google.android.apps.tv.launcherx", "com.google.android.tungsten.setupwraith")
            .map { "adb shell pm disable-user --user 0 $it" }
        disable.forEach { assertTrue(it in all) }
        val reenable = all.first { it.startsWith("adb shell pm enable ") }
        assertTrue("pm enable com.google.android.apps.tv.launcherx" in reenable)
        assertTrue("pm enable com.google.android.tungsten.setupwraith" in reenable)
    }

    @Test
    fun usesTheTvAddressInTheConnectCommands() {
        assertTrue(all.any { it == "adb connect 192.168.1.50:5555" })
        assertTrue(LauncherSetupCommands.sections(component, null).flatMap { it.commands }.any { "TV_IP" in it.command })
    }

    @Test
    fun stockLauncherSectionWarnsAboutTheYouTubeButton() {
        val warning = LauncherSetupCommands.sections(component, null).mapNotNull { it.warning }.single()
        assertTrue("YouTube" in warning)
    }

    @Test
    fun pageEscapesHtmlAndHasACopyButtonPerCommand() {
        val sections = listOf(SetupSection("T <x>", "i", listOf(SetupCommand("a", "n", "echo \"<b>\" & ok"))))
        val html = LauncherSetupServer.page("App", sections)
        assertTrue("T &lt;x&gt;" in html)
        assertTrue("echo &quot;&lt;b&gt;&quot; &amp; ok" in html)
        assertFalse("<b>\"" in html)
        assertEquals(1, Regex("onclick=\"copyCmd").findAll(html).count())
    }
}
