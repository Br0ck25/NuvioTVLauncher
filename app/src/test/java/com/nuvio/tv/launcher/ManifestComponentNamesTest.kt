package com.nuvio.tv.launcher

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A flavor manifest that declares an `<activity>` with the same name as one of the main manifest's `<activity-alias>`
 * elements does not replace it: the manifest merger only matches elements of the same kind, so the app ends up with
 * two components of one name. Launchers then list the alias but cannot open it ("Activity class ... does not exist"),
 * which is how the app could not be opened from Google TV's launcher.
 */
class ManifestComponentNamesTest {
    private fun manifest(flavor: String) = File("src/$flavor/AndroidManifest.xml").readText()

    private fun names(xml: String, tag: String): Set<String> =
        Regex("(?s)<" + tag + """\s[^>]*?android:name="([^"]+)"""").findAll(xml)
            .map { it.groupValues[1] }
            // A leading dot means "in this module's namespace" (com.nuvio.tv), as the manifest merger resolves it.
            .map { if (it.startsWith(".")) "com.nuvio.tv$it" else it }
            .toSet()

    @Test
    fun `no flavor manifest declares an activity under the name of a main manifest alias`() {
        val aliases = names(manifest("main"), "activity-alias")
        assertTrue("no aliases found in ${File("src/main/AndroidManifest.xml").absolutePath} (${manifest("main").length} chars)", aliases.isNotEmpty())
        listOf("full", "playstore", "launcher").forEach { flavor ->
            val clashes = names(manifest(flavor), "activity").intersect(aliases)
            assertEquals("src/$flavor/AndroidManifest.xml re-declares aliases as activities", emptySet<String>(), clashes)
        }
    }

    @Test
    fun `the Nuvio TV Launcher icon is the one entry enabled by default, the Live TV badge entry is not`() {
        val main = manifest("main")
        val alias = Regex("<activity-alias[^>]*AppIconDefault[^>]*>", RegexOption.DOT_MATCHES_ALL).find(main)!!.value
        assertTrue(alias, "android:enabled=\"true\"" in alias)
        val iptv = Regex("<activity[^>]*IptvIconDefault[^>]*>", RegexOption.DOT_MATCHES_ALL).find(manifest("full"))!!.value
        assertTrue(iptv, "android:enabled=\"false\"" in iptv)
    }
}
