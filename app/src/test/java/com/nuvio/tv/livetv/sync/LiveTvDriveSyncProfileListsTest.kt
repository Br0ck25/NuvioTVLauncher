package com.nuvio.tv.livetv.sync

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveTvDriveSyncProfileListsTest {
    private val sync by lazy {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        LiveTvDriveSync(context, mockk(relaxed = true), mockk(relaxed = true))
    }

    @Test
    fun `profile-specific playlist and guide lists are unioned independently`() {
        val local = payload("playlists_p2" to listOf(source("two")))
        val remote = payload(
            "playlists_p2" to listOf(source("two"), source("remote-two")),
            "epg_sources_p3" to listOf(source("three-guide"))
        )

        val merged = sync.merge(null, local, remote, localWinsWithoutBase = true, removedHere = emptySet())

        assertEquals(setOf("two", "remote-two"), ids(merged, "playlists_p2"))
        assertEquals(setOf("three-guide"), ids(merged, "epg_sources_p3"))
    }

    @Test
    fun `removing a shared source on one profile does not remove it from another`() {
        val base = payload(
            "playlists" to listOf(source("shared-id")),
            "playlists_p2" to listOf(source("shared-id"))
        )
        val local = payload("playlists" to listOf(source("shared-id")))
        val remote = payload(
            "playlists" to listOf(source("shared-id")),
            "playlists_p2" to listOf(source("shared-id"))
        )

        val merged = sync.merge(base, local, remote, localWinsWithoutBase = false, removedHere = setOf("2|shared-id"))

        assertEquals(setOf("shared-id"), ids(merged, "playlists"))
        assertEquals(emptySet<String>(), ids(merged, "playlists_p2"))
    }

    @Test
    fun `profile deletion tombstones prune cloud sources without a merge base`() {
        val local = payload("playlists" to listOf(source("shared-id")))
        val remote = payload(
            "playlists" to listOf(source("shared-id")),
            "playlists_p2" to listOf(source("profile-two-source"))
        )

        val merged = sync.merge(
            null,
            local,
            remote,
            localWinsWithoutBase = true,
            removedHere = setOf("2|profile-two-source")
        )

        assertEquals(setOf("shared-id"), ids(merged, "playlists"))
        assertEquals(emptySet<String>(), ids(merged, "playlists_p2"))
    }

    private fun source(id: String) = JSONObject().put("id", id).put("name", id)

    private fun payload(vararg lists: Pair<String, List<JSONObject>>): JSONObject = JSONObject().apply {
        lists.forEach { (key, sources) ->
            put(key, JSONObject().put("t", "str").put("v", JSONArray(sources).toString()))
        }
    }

    private fun ids(data: JSONObject, key: String): Set<String> =
        data.optJSONObject(key)?.optString("v")?.let(::JSONArray)?.let { array ->
            (0 until array.length()).mapTo(LinkedHashSet()) { array.getJSONObject(it).getString("id") }
        }.orEmpty()
}
