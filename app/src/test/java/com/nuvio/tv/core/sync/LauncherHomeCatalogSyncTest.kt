package com.nuvio.tv.core.sync

import com.nuvio.tv.core.apps.INSTALLED_APPS_CATALOG_KEY
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Launcher: how the Apps row (a local-only Home row) behaves in the Home catalog sync. */
class LauncherHomeCatalogSyncTest {

    @Test
    fun `apps row is a local only key but ordinary catalogs and collections are not`() {
        assertTrue(isLocalOnlyHomeKey(INSTALLED_APPS_CATALOG_KEY))
        assertTrue(isLocalOnlyHomeKey("server.jellyfin-1"))
        assertFalse(isLocalOnlyHomeKey("com.test.addon_movie_top"))
        assertFalse(isLocalOnlyHomeKey("collection_col-1"))
    }

    @Test
    fun `a pull keeps the apps row in its local position`() {
        val remote = listOf("addon_movie_a", "addon_movie_b", "collection_col-1")
        val local = listOf("addon_movie_a", INSTALLED_APPS_CATALOG_KEY, "addon_movie_b", "collection_col-1")

        assertEquals(
            listOf("addon_movie_a", INSTALLED_APPS_CATALOG_KEY, "addon_movie_b", "collection_col-1"),
            remote.withLocalServerKeys(local)
        )
    }

    @Test
    fun `apps row never appears in the synced payload so a pull must carry it over locally`() {
        val payload = buildHomeCatalogSyncPayload(
            addons = emptyList(),
            collections = listOf(Collection(id = "col-1", title = "Favorites")),
            localState = LocalHomeCatalogSettingsState(
                orderKeys = listOf(INSTALLED_APPS_CATALOG_KEY, "collection_col-1"),
                disabledKeys = setOf(INSTALLED_APPS_CATALOG_KEY)
            )
        )

        assertEquals(listOf("col-1"), payload.items.map { it.collectionId })
    }
}
