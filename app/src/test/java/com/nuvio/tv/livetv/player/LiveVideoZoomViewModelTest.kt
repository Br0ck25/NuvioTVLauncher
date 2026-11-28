package com.nuvio.tv.livetv.player

import com.nuvio.tv.data.local.TrackPreferenceDataStore
import com.nuvio.tv.ui.screens.player.VideoZoom
import com.nuvio.tv.ui.screens.player.VideoZoomAxis
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveVideoZoomViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = mockk<TrackPreferenceDataStore>(relaxed = true)

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `opening a channel shows the zoom saved for it`() = TestScope(dispatcher).run {
        coEvery { store.loadVideoZoom("livetv:a") } returns VideoZoom(scaleX = 1.2f, scaleY = 1.2f)
        val vm = LiveVideoZoomViewModel(store)
        vm.open("a")
        advanceUntilIdle()
        assertEquals(1.2f, vm.zoom.value.scaleX, 0.001f)
    }

    @Test
    fun `a channel with nothing saved is not zoomed, and switching channel drops the previous zoom`() = TestScope(dispatcher).run {
        coEvery { store.loadVideoZoom("livetv:a") } returns VideoZoom(scaleX = 1.2f, scaleY = 1.2f)
        coEvery { store.loadVideoZoom("livetv:b") } returns null
        val vm = LiveVideoZoomViewModel(store)
        vm.open("a"); advanceUntilIdle()
        vm.open("b"); advanceUntilIdle()
        assertTrue(vm.zoom.value.isIdentity)
    }

    @Test
    fun `adjusting changes the picture at once and is saved for the channel once the keys settle`() = TestScope(dispatcher).run {
        coEvery { store.loadVideoZoom(any()) } returns null
        val vm = LiveVideoZoomViewModel(store)
        vm.open("b"); advanceUntilIdle()
        vm.adjust(VideoZoomAxis.Width, 2)
        vm.adjust(VideoZoomAxis.Width, 2)
        runCurrent()
        assertEquals(1.2f, vm.zoom.value.scaleX, 0.001f)
        coVerify(exactly = 0) { store.saveVideoZoom(any(), any()) }
        advanceTimeBy(500); runCurrent()
        coVerify(exactly = 1) { store.saveVideoZoom("livetv:b", match { it.scaleX == 1.2f && it.scaleY == 1f }) }
    }

    @Test
    fun `reset puts the plain picture back and saves that`() = TestScope(dispatcher).run {
        coEvery { store.loadVideoZoom("livetv:a") } returns VideoZoom(scaleX = 1.5f, scaleY = 1.5f)
        val vm = LiveVideoZoomViewModel(store)
        vm.open("a"); advanceUntilIdle()
        vm.reset()
        advanceUntilIdle()
        assertTrue(vm.zoom.value.isIdentity)
        coVerify { store.saveVideoZoom("livetv:a", match { it.isIdentity }) }
    }
}
