package com.nuvio.tv.core.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class InstalledAppsOrderTest {
    private val available = listOf("app.a", "app.b", "app.c", "app.new")

    @Test
    fun `saved order wins and new apps append in discovery order`() {
        assertEquals(listOf("app.c", "app.a", "app.b", "app.new"), orderInstalledApps(available, listOf("app.c", "app.a"), emptySet()))
    }

    @Test
    fun `removed and uninstalled packages do not appear and duplicates are collapsed`() {
        assertEquals(listOf("app.c", "app.b", "app.new"), orderInstalledApps(available, listOf("app.c", "app.c", "app.a", "app.uninstalled"), setOf("app.a")))
    }

    @Test
    fun `restore appends without changing existing order`() {
        val order = orderInstalledApps(available, listOf("app.c", "app.b"), setOf("app.a"))
        assertEquals(listOf("app.c", "app.b", "app.new", "app.a"), orderInstalledApps(available, order, emptySet()))
    }

    @Test
    fun `move swaps neighbors without mutating original`() {
        val original = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), moveInstalledApp(original, "a", 1))
        assertEquals(listOf("a", "c", "b"), moveInstalledApp(original, "c", -1))
        assertEquals(listOf("a", "b", "c"), original)
    }

    @Test
    fun `moving beyond bounds or missing package is a no-op`() {
        val original = listOf("a", "b")
        assertEquals(original, moveInstalledApp(original, "a", -1))
        assertEquals(original, moveInstalledApp(original, "b", 1))
        assertEquals(original, moveInstalledApp(original, "missing", 1))
        assertEquals(original, moveInstalledApp(original, "a", 0))
    }

    @Test
    fun `removing every app yields an empty row`() {
        assertEquals(emptyList<String>(), orderInstalledApps(available, emptyList(), available.toSet()))
    }
}
