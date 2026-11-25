package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoZoomPolicyTest {

    @Test
    fun `untouched zoom is the identity`() {
        assertTrue(VideoZoom.IDENTITY.isIdentity)
        assertTrue(VideoZoom(scaleX = 1.001f).isIdentity)
    }

    @Test
    fun `adjusting both axes moves width and height together`() {
        val zoom = VideoZoomPolicy.adjust(VideoZoom.IDENTITY, VideoZoomAxis.Both, 2)
        assertEquals(1.10f, zoom.scaleX, 0.001f)
        assertEquals(1.10f, zoom.scaleY, 0.001f)
        assertFalse(zoom.isIdentity)
    }

    @Test
    fun `width and height move on their own`() {
        val wide = VideoZoomPolicy.adjust(VideoZoom.IDENTITY, VideoZoomAxis.Width, 4)
        assertEquals(1.20f, wide.scaleX, 0.001f)
        assertEquals(1.00f, wide.scaleY, 0.001f)
        val tall = VideoZoomPolicy.adjust(VideoZoom.IDENTITY, VideoZoomAxis.Height, -2)
        assertEquals(1.00f, tall.scaleX, 0.001f)
        assertEquals(0.90f, tall.scaleY, 0.001f)
    }

    @Test
    fun `scale and pan stay inside their limits`() {
        val big = VideoZoomPolicy.adjust(VideoZoom(scaleX = 2.98f, scaleY = 2.98f), VideoZoomAxis.Both, 10)
        assertEquals(VideoZoomPolicy.MAX_SCALE, big.scaleX, 0.001f)
        val small = VideoZoomPolicy.adjust(VideoZoom(scaleX = 0.52f), VideoZoomAxis.Width, -10)
        assertEquals(VideoZoomPolicy.MIN_SCALE, small.scaleX, 0.001f)
        val panned = VideoZoomPolicy.adjust(VideoZoom(panX = 0.49f), VideoZoomAxis.PanX, 10)
        assertEquals(VideoZoomPolicy.MAX_PAN, panned.panX, 0.001f)
    }

    @Test
    fun `garbage values fall back to no zoom`() {
        val z = VideoZoomPolicy.normalize(VideoZoom(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN, Float.NEGATIVE_INFINITY))
        assertEquals(1f, z.scaleX, 0f)
        assertEquals(1f, z.scaleY, 0f)
        assertEquals(0f, z.panX, 0f)
        assertEquals(0f, z.panY, 0f)
    }

    @Test
    fun `surface transform multiplies the aspect scale and pans by the picture size`() {
        val t = VideoZoomPolicy.surfaceTransform(
            zoom = VideoZoom(scaleX = 1.5f, scaleY = 1.5f, panX = 0.1f, panY = -0.1f),
            widthPx = 1000,
            heightPx = 500,
            baseScaleX = 2f,
            baseScaleY = 2f
        )
        assertEquals(3f, t.scaleX, 0.001f)
        assertEquals(3f, t.scaleY, 0.001f)
        assertEquals(300f, t.translationX, 0.01f)
        assertEquals(-150f, t.translationY, 0.01f)
    }

    @Test
    fun `scale label shows one number when both axes match`() {
        assertEquals("120%", VideoZoomPolicy.scaleLabel(VideoZoom(1.2f, 1.2f)))
        assertEquals("120% × 100%", VideoZoomPolicy.scaleLabel(VideoZoom(1.2f, 1f)))
    }
}
