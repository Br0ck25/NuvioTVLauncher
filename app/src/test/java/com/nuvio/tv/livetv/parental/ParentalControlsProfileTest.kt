package com.nuvio.tv.livetv.parental

import com.nuvio.tv.livetv.model.LiveTvSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ParentalControlsProfileTest {
    private val settings = LiveTvSettings(parentalPin = "1234", lockAdultContent = true)

    @Before
    fun setUp() {
        ParentalControls.removeProfile(1)
        ParentalControls.removeProfile(2)
        ParentalControls.setProfile(1)
    }

    @After
    fun tearDown() {
        ParentalControls.removeProfile(2)
        ParentalControls.removeProfile(1)
        ParentalControls.setProfile(1)
    }

    @Test
    fun `an unlocked group does not carry into another profile`() {
        assertTrue(ParentalControls.isLocked("adult", "Adult", settings, emptySet()))
        assertTrue(ParentalControls.unlock("adult", "1234", settings))
        assertFalse(ParentalControls.isLocked("adult", "Adult", settings, emptySet()))

        ParentalControls.setProfile(2)

        assertTrue(ParentalControls.isLocked("adult", "Adult", settings, emptySet()))
    }

    @Test
    fun `switching back restores only that profiles session unlocks`() {
        val locked = setOf("profile-one", "profile-two")
        ParentalControls.unlock("profile-one", "1234", settings)
        ParentalControls.setProfile(2)
        ParentalControls.unlock("profile-two", "1234", settings)

        ParentalControls.setProfile(1)
        assertEquals(setOf("profile-one"), ParentalControls.unlocked.value)
        assertFalse(ParentalControls.isLocked("profile-one", "Group", settings, locked))
        assertTrue(ParentalControls.isLocked("profile-two", "Group", settings, locked))

        ParentalControls.setProfile(2)
        assertTrue(ParentalControls.isLocked("profile-one", "Group", settings, locked))
        assertFalse(ParentalControls.isLocked("profile-two", "Group", settings, locked))
    }

    @Test
    fun `deleting a profile forgets its session unlocks`() {
        ParentalControls.setProfile(2)
        ParentalControls.unlock("adult", "1234", settings)
        ParentalControls.removeProfile(2)
        ParentalControls.setProfile(1)
        ParentalControls.setProfile(2)

        assertTrue(ParentalControls.isLocked("adult", "Adult", settings, emptySet()))
    }
}
