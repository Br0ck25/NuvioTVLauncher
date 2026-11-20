package com.nuvio.tv.core.launcher

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeForegroundDeliveryTest {
    private val owner = Any()
    private val replacement = Any()

    @After
    fun tearDown() {
        HomeForegroundDelivery.detach(owner)
        HomeForegroundDelivery.detach(replacement)
    }

    @Test
    fun `resumed activity receives home without another startActivity`() {
        var received: Long? = null
        HomeForegroundDelivery.attach(owner) { received = it }
        assertTrue(HomeForegroundDelivery.isResumed)
        assertTrue(HomeForegroundDelivery.deliver(1_000))
        assertEquals(1_000L, received)
    }

    @Test
    fun `pause or destroy removes the receiver so another app can return via intent`() {
        HomeForegroundDelivery.attach(owner) { error("must not call a paused Activity") }
        HomeForegroundDelivery.detach(owner)
        assertFalse(HomeForegroundDelivery.isResumed)
        assertFalse(HomeForegroundDelivery.deliver(1_000))
    }

    @Test
    fun `old activity teardown cannot detach its replacement`() {
        var count = 0
        HomeForegroundDelivery.attach(owner) { error("old Activity") }
        HomeForegroundDelivery.attach(replacement) { count++ }
        HomeForegroundDelivery.detach(owner)
        assertTrue(HomeForegroundDelivery.deliver(1_000))
        assertEquals(1, count)
    }

    @Test
    fun `foreground presses invoke the existing double home profile request path`() {
        val gesture = HomeKeyGesture()
        val actions = mutableListOf<HomeKeyPress>()
        HomeForegroundDelivery.attach(owner) { actions += gesture.onPress(it) }
        HomeForegroundDelivery.deliver(1_000)
        HomeForegroundDelivery.deliver(1_000)
        HomeForegroundDelivery.deliver(1_250)
        assertEquals(listOf(HomeKeyPress.SINGLE, HomeKeyPress.IGNORED, HomeKeyPress.DOUBLE), actions)
    }
}
