package com.nuvio.tv.core.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HiddenAppsTest {
    @Test
    fun `hidden apps are saved and read back, and showing one again removes it`() {
        val hidden = AppHidden.set(AppHidden.set(emptySet(), "b.app", true), "a.app", true)
        assertEquals(setOf("a.app", "b.app"), AppHidden.decode(AppHidden.encode(hidden)))
        assertEquals(setOf("b.app"), AppHidden.set(hidden, "a.app", false))
        assertEquals(emptySet<String>(), AppHidden.decode(null))
        assertEquals(setOf("x"), AppHidden.decode("\n x \n\n"))
    }

    @Test
    fun `one place on the Home row steps over hidden apps`() {
        val all = listOf("a", "h1", "h2", "b", "c")
        val hidden = setOf("h1", "h2")
        assertEquals(listOf("a", "h1", "h2", "c", "b"), AppOrder.move(all, "b", 1, hidden))
        assertEquals(listOf("b", "a", "h1", "h2", "c"), AppOrder.move(all, "b", -1, hidden))
        // a swaps with b, jumping over the hidden pair
        assertEquals(listOf("h1", "h2", "b", "a", "c"), AppOrder.move(all, "a", 1, hidden))
        assertNull(AppOrder.move(all, "c", 1, hidden))
        assertNull(AppOrder.move(listOf("a", "h1"), "a", 1, setOf("h1")))
    }

    @Test
    fun `on the Apps page every app counts`() {
        assertEquals(listOf("h1", "a", "b"), AppOrder.move(listOf("a", "h1", "b"), "a", 1))
        assertNull(AppOrder.move(listOf("a", "b"), "a", -1))
    }
}
