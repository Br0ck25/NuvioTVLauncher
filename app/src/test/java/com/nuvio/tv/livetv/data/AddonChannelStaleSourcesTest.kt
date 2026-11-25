package com.nuvio.tv.livetv.data

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.livetv.model.AddonChannelSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AddonChannelStaleSourcesTest {

    private val catalogRepository = mockk<CatalogRepository>(relaxed = true)

    @Test
    fun `sources for an installed addon are kept`() = runTest {
        val repository = repositoryWith(addon("https://example.com/addon/"))
        val sources = listOf(source("a", "https://example.com/addon"))
        assertEquals(emptySet<String>(), repository.staleSources(sources))
    }

    @Test
    fun `sources for a removed addon are returned`() = runTest {
        val repository = repositoryWith(addon("https://other.example/addon"))
        val sources = listOf(
            source("kept", "https://other.example/addon"),
            source("removed", "https://gone.example/addon")
        )
        assertEquals(setOf("removed"), repository.staleSources(sources))
    }

    @Test
    fun `an empty installed list does not delete every selection`() = runTest {
        val repository = repositoryWith()
        val sources = listOf(source("a", "https://example.com/addon"))
        assertEquals(emptySet<String>(), repository.staleSources(sources))
    }

    @Test
    fun `a source without an addon address is left for legacy migration`() = runTest {
        val repository = repositoryWith(addon("https://example.com/addon"))
        val sources = listOf(source("legacy", ""))
        assertEquals(emptySet<String>(), repository.staleSources(sources))
    }

    private fun repositoryWith(vararg addons: Addon): AddonChannelRepository {
        val addonRepository = mockk<AddonRepository>()
        every { addonRepository.getInstalledAddons() } returns flowOf(addons.toList())
        return AddonChannelRepository(addonRepository, catalogRepository)
    }

    private fun addon(baseUrl: String) = Addon(
        id = "test",
        name = "Test",
        version = "1.0",
        description = null,
        logo = null,
        baseUrl = baseUrl,
        catalogs = emptyList(),
        types = emptyList(),
        resources = emptyList(),
    )

    private fun source(id: String, baseUrl: String) = AddonChannelSource(
        id = id,
        addonBaseUrl = baseUrl,
        addonName = "Test",
        catalogId = "tv",
        catalogName = "TV",
    )
}
