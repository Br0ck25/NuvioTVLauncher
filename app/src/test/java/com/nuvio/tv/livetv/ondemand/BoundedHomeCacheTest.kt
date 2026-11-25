package com.nuvio.tv.livetv.ondemand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedHomeCacheTest {
    @Test fun mapEvictsLeastRecentlyUsedEntryAtCountLimit() {
        val cache = BoundedHomeMap<String>(2, 100) { it.length.toLong() }
        cache["a"] = "one"
        cache["b"] = "two"
        assertEquals("one", cache["a"]) // make a most recently used
        cache["c"] = "three"

        assertNull(cache["b"])
        assertEquals("one", cache["a"])
        assertEquals("three", cache["c"])
    }

    @Test fun mapEvictsToTheEstimatedWeightLimit() {
        val cache = BoundedHomeMap<String>(10, 5) { it.length.toLong() }
        cache["small"] = "123"
        cache["large"] = "abcdef"

        assertNull(cache["small"])
        assertEquals("abcdef", cache["large"])
    }

    @Test fun setIsBoundedAndTouchesExistingEntries() {
        val cache = BoundedHomeSet(2)
        assertTrue(cache.add("a"))
        cache.add("b")
        assertFalse(cache.add("a"))
        cache.add("c")

        assertTrue("a" in cache)
        assertFalse("b" in cache)
        assertTrue("c" in cache)
    }
}
