package com.nuvio.tv.core.launcher

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Guard the task identity shared by native HOME, accessibility, and every selectable app icon. */
class LauncherManifestTest {
    private val manifest by lazy {
        val path = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
            .first { it.isFile }
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(path)
    }

    @Test
    fun `Home activity reuses its existing task`() {
        val main = elements("activity").single { it.androidAttribute("name") == ".MainActivity" }
        assertEquals("singleTask", main.androidAttribute("launchMode"))
        val categories = main.getElementsByTagName("category")
        assertTrue((0 until categories.length).any {
            (categories.item(it) as Element).androidAttribute("name") == "android.intent.category.HOME"
        })
    }

    @Test
    fun `every app icon is an alias of the stable Home activity`() {
        val aliases = elements("activity-alias").filter { it.androidAttribute("name").startsWith(".launcher.AppIcon") }
        assertEquals(6, aliases.size)
        aliases.forEach { alias ->
            assertEquals(".MainActivity", alias.androidAttribute("targetActivity"))
        }
        assertTrue(elements("activity").none { it.androidAttribute("name").startsWith(".launcher.AppIcon") })
    }

    @Test
    fun `existing icon component names are preserved for installed shortcuts`() {
        assertEquals(
            setOf("Default", "ArcticBlue", "Emerald", "RoseGold", "Copper", "Graphite")
                .map { ".launcher.AppIcon$it" }.toSet(),
            elements("activity-alias").filter { it.androidAttribute("name").startsWith(".launcher.AppIcon") }
                .map { it.androidAttribute("name") }.toSet()
        )
    }

    private fun elements(tag: String): List<Element> {
        val nodes = manifest.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS("http://schemas.android.com/apk/res/android", name)
}
