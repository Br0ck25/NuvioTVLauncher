package com.nuvio.tv.core.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoundedMemoryCacheTest {
    @Test
    fun `evicts least recently used entry when capacity is exceeded`() {
        val cache = BoundedMemoryCache<String, Int>(2)
        cache["old"] = 1
        cache["recent"] = 2
        assertEquals(1, cache["old"])
        cache["new"] = 3

        assertNull(cache["recent"])
        assertEquals(1, cache["old"])
        assertEquals(3, cache["new"])
        assertEquals(2, cache.size)
    }

    @Test
    fun `replacing a value does not use extra capacity`() {
        val cache = BoundedMemoryCache<String, Int>(1)
        cache["item"] = 1
        cache["item"] = 2

        assertEquals(2, cache["item"])
        assertEquals(1, cache.size)
    }
}
