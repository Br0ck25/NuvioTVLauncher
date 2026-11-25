package com.nuvio.tv.core.cache

import java.util.LinkedHashMap

/** Small synchronized access-ordered cache; eviction only causes a later lookup to be repeated. */
internal class BoundedMemoryCache<K, V>(private val maxEntries: Int) {
    init {
        require(maxEntries > 0)
    }

    private val values = LinkedHashMap<K, V>(16, 0.75f, true)

    operator fun get(key: K): V? = synchronized(values) { values[key] }

    operator fun set(key: K, value: V) = synchronized(values) {
        values[key] = value
        while (values.size > maxEntries) {
            val eldest = values.entries.iterator()
            if (!eldest.hasNext()) break
            eldest.next()
            eldest.remove()
        }
    }

    fun remove(key: K, expectedValue: V): Boolean = synchronized(values) {
        if (values[key] != expectedValue) return@synchronized false
        values.remove(key)
        true
    }

    fun clear() = synchronized(values) { values.clear() }

    fun snapshotEntries(): List<Pair<K, V>> = synchronized(values) {
        values.entries.map { it.key to it.value }
    }

    val size: Int get() = synchronized(values) { values.size }
}
