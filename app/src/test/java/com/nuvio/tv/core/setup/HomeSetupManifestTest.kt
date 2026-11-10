package com.nuvio.tv.core.setup

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Guards the two manifest promises the setup depends on: Nuvio is selectable as the Home app the way
 * FLauncher and ArcLauncher are, and it does so without an accessibility service listening for keys.
 */
class HomeSetupManifestTest {

    private val manifest by lazy { parse(manifestPath("main")) }
    private val fullManifest by lazy { parse(manifestPath("full")) }

    @Test
    fun `Home is declared on a single-task activity that is exported`() {
        val main = manifest.elements("activity").single { it.androidAttribute("name") == ".MainActivity" }
        assertEquals("true", main.androidAttribute("exported"))
        assertEquals("singleTask", main.androidAttribute("launchMode"))

        val home = main.intentFilters().single { filter ->
            filter.categories().contains("android.intent.category.HOME")
        }
        assertEquals("android.intent.action.MAIN", home.actions().single())
        assertTrue(home.categories().contains("android.intent.category.DEFAULT"))
    }

    @Test
    fun `every app icon is an alias of the Home activity so one task and one component name are used`() {
        val aliases = manifest.elements("activity-alias").filter { it.androidAttribute("name").startsWith(".launcher.AppIcon") }
        assertEquals(
            setOf("Default", "ArcticBlue", "Emerald", "RoseGold", "Copper", "Graphite")
                .map { ".launcher.AppIcon$it" }.toSet(),
            aliases.map { it.androidAttribute("name") }.toSet()
        )
        aliases.forEach { alias ->
            assertEquals(".MainActivity", alias.androidAttribute("targetActivity"))
            assertTrue(alias.intentFilters().any { filter ->
                filter.categories().contains("android.intent.category.LEANBACK_LAUNCHER")
            })
        }
        // Subclasses of MainActivity would each own a task, and switching icons would strand the
        // recorded Home preference on a component that is now disabled.
        assertTrue(manifest.elements("activity").none { it.androidAttribute("name").startsWith(".launcher.AppIcon") })
    }

    @Test
    fun `no accessibility service and no key interception ship with the app`() {
        assertTrue(manifest.elements("service").none {
            it.androidAttribute("permission") == "android.permission.BIND_ACCESSIBILITY_SERVICE"
        })
        assertFalse(File(manifestPath("main")).readText().contains("canRequestFilterKeyEvents"))
    }

    @Test
    fun `the Home resolver and Home settings stay visible on Android 11 plus`() {
        val queries = manifest.getElementsByTagName("queries").item(0) as Element
        val actions = (0 until queries.getElementsByTagName("action").length)
            .map { (queries.getElementsByTagName("action").item(it) as Element).androidAttribute("name") }
        assertTrue(actions.contains("android.settings.HOME_SETTINGS"))
        assertTrue(actions.contains("android.intent.action.MAIN"))
    }

    @Test
    fun `only the sideloadable flavor declares the permission the setup grants itself`() {
        assertTrue(fullManifest.usesPermissions().contains("android.permission.WRITE_SECURE_SETTINGS"))
        assertFalse(manifest.usesPermissions().contains("android.permission.WRITE_SECURE_SETTINGS"))
    }

    private fun manifestPath(sourceSet: String): File =
        listOf(File("src/$sourceSet/AndroidManifest.xml"), File("app/src/$sourceSet/AndroidManifest.xml"))
            .first { it.isFile }

    private fun parse(path: File) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(path)

    private fun org.w3c.dom.Document.elements(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.intentFilters(): List<Element> {
        val nodes = getElementsByTagName("intent-filter")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.actions(): List<String> = childNames("action")

    private fun Element.categories(): List<String> = childNames("category")

    private fun Element.childNames(tag: String): List<String> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { (nodes.item(it) as Element).androidAttribute("name") }
    }

    private fun org.w3c.dom.Document.usesPermissions(): List<String> =
        elements("uses-permission").map { it.androidAttribute("name") }

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS("http://schemas.android.com/apk/res/android", name)
}
