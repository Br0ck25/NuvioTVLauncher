package com.nuvio.tv.core.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledAppsCatalogTest {
    @Test
    fun `new row is enabled and leads the home order by default`() {
        val order = mergeHomeCatalogOrder(listOf("movies", "shows"), listOf(INSTALLED_APPS_CATALOG_KEY), emptyList(), false)
        assertEquals(listOf(INSTALLED_APPS_CATALOG_KEY, "movies", "shows"), order)
    }

    @Test
    fun `unsaved apps row leads even when other rows already have positions`() {
        val order = mergeHomeCatalogOrder(
            addonKeys = listOf("movies", "shows"),
            independentKeys = listOf("collection_favorites", INSTALLED_APPS_CATALOG_KEY),
            savedOrderKeys = listOf("shows", "collection_favorites", "movies"),
            followAddonsOrder = false
        )
        assertEquals(listOf(INSTALLED_APPS_CATALOG_KEY, "shows", "collection_favorites", "movies"), order)
    }

    @Test
    fun `follow mode also leads with unsaved apps row`() {
        val addons = listOf("a_movies", "a_shows", "b_movies")
        val owner = { key: String -> key.substringBefore('_') }
        assertEquals(
            listOf(INSTALLED_APPS_CATALOG_KEY) + addons,
            mergeHomeCatalogOrder(addons, listOf(INSTALLED_APPS_CATALOG_KEY), emptyList(), true, owner)
        )
        assertEquals(
            listOf(INSTALLED_APPS_CATALOG_KEY) + addons,
            mergeHomeCatalogOrder(addons, listOf(INSTALLED_APPS_CATALOG_KEY), listOf("b_movies"), true, owner)
        )
    }

    @Test
    fun `a saved apps position wins over the default top position`() {
        val addons = listOf("a_movies", "a_shows", "b_movies")
        val owner = { key: String -> key.substringBefore('_') }
        assertEquals(
            listOf("a_movies", "a_shows", INSTALLED_APPS_CATALOG_KEY, "b_movies"),
            mergeHomeCatalogOrder(addons, listOf(INSTALLED_APPS_CATALOG_KEY), listOf("a_shows", INSTALLED_APPS_CATALOG_KEY), true, owner)
        )
    }

    @Test
    fun `saved position survives new catalogs and stale keys`() {
        val order = mergeHomeCatalogOrder(
            addonKeys = listOf("movies", "shows", "new"),
            independentKeys = listOf(INSTALLED_APPS_CATALOG_KEY),
            savedOrderKeys = listOf(INSTALLED_APPS_CATALOG_KEY, "removed", "movies", INSTALLED_APPS_CATALOG_KEY),
            followAddonsOrder = false
        )
        assertEquals(listOf(INSTALLED_APPS_CATALOG_KEY, "movies", "shows", "new"), order)
    }

    @Test
    fun `apps can be first between addon blocks or last in follow mode`() {
        val addons = listOf("a_movies", "a_shows", "b_movies", "b_shows")
        val owner = { key: String -> key.substringBefore('_') }
        for (saved in listOf(
            listOf(INSTALLED_APPS_CATALOG_KEY, "a_movies", "b_shows"),
            listOf("a_shows", INSTALLED_APPS_CATALOG_KEY, "b_movies"),
            listOf("b_shows", INSTALLED_APPS_CATALOG_KEY)
        )) {
            val order = mergeHomeCatalogOrder(addons, listOf(INSTALLED_APPS_CATALOG_KEY), saved, true, owner)
            assertEquals(addons, order.filterNot { it == INSTALLED_APPS_CATALOG_KEY })
            assertEquals(5, order.size)
            assertTrue(order.indexOf(INSTALLED_APPS_CATALOG_KEY) != 1)
            assertTrue(order.indexOf(INSTALLED_APPS_CATALOG_KEY) != 3)
        }
    }

    @Test
    fun `follow mode moves apps across a whole addon block`() {
        val keys = listOf("a_movies", "a_shows", INSTALLED_APPS_CATALOG_KEY, "b_movies")
        val owner = { key: String -> key.substringBefore('_') }
        assertEquals(listOf(INSTALLED_APPS_CATALOG_KEY, "a_movies", "a_shows", "b_movies"), moveIndependentHomeCatalog(keys, INSTALLED_APPS_CATALOG_KEY, -1, owner))
        assertEquals(listOf("a_movies", "a_shows", "b_movies", INSTALLED_APPS_CATALOG_KEY), moveIndependentHomeCatalog(keys, INSTALLED_APPS_CATALOG_KEY, 1, owner))
    }

    @Test
    fun `adjacent independent rows can swap in follow mode`() {
        val keys = listOf("collection_favorites", INSTALLED_APPS_CATALOG_KEY, "a_movies")
        assertEquals(listOf(INSTALLED_APPS_CATALOG_KEY, "collection_favorites", "a_movies"), moveIndependentHomeCatalog(keys, INSTALLED_APPS_CATALOG_KEY, -1) { it })
    }

    @Test
    fun `installed apps key is independent but catalog keys are not`() {
        assertTrue(isIndependentHomeCatalogKey(INSTALLED_APPS_CATALOG_KEY))
        assertTrue(isIndependentHomeCatalogKey("collection_1"))
        assertFalse(isIndependentHomeCatalogKey("addon_movie_installed_apps"))
    }
}
