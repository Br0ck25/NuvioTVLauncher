package com.nuvio.tv.core.launcher

import android.view.KeyEvent
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ButtonMappingRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createDataStore(fileName: String = "test_button_mapper.preferences_pb"): ButtonMappingDataStore {
        val file = File(tempFolder.root, fileName)
        val ds = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = { file }
        )
        return ButtonMappingDataStore(ds)
    }

    @Test
    fun `first install seeds factory default Home mapping`() = runBlocking {
        val dataStore = createDataStore("first_install.preferences_pb")
        val repository = ButtonMappingRepository(dataStore, Dispatchers.Unconfined)

        val state = dataStore.ensureInitialized()
        assertTrue(state.masterEnabled)
        assertTrue(state.homeCustomizationEnabled)
        assertEquals(1, state.mappings.size)

        val home = state.mappingFor(KeyEvent.KEYCODE_HOME)
        assertNotNull(home)
        assertEquals(ButtonAction.GoToTopOfHome, home?.singleAction)
        assertEquals(ButtonAction.OpenProfileSwitcher, home?.doubleAction)
        assertEquals(ButtonAction.None, home?.longAction)
        assertTrue(home?.matchesFactoryHomeDefaults() == true)
        assertNotNull(repository.activeMappingFor(KeyEvent.KEYCODE_HOME, isGlobal = false))
    }

    @Test
    fun `migration preserves existing user mappings across restarts and updates`() = runBlocking {
        val fileName = "migration_persist.preferences_pb"
        val dataStore1 = createDataStore(fileName)
        val repo1 = ButtonMappingRepository(dataStore1, Dispatchers.Unconfined)

        // Customize Home and add a Netflix shortcut button
        repo1.updatePressAction(
            keyCode = KeyEvent.KEYCODE_HOME,
            pressType = ButtonPressType.LONG_PRESS,
            action = ButtonAction.OpenSettings
        )
        repo1.upsertMapping(
            ButtonMapping(
                keyCode = RemoteButtonCatalog.KEYCODE_VIDEO_APP_1,
                displayName = "Netflix",
                singleAction = ButtonAction.LaunchApp("com.netflix.ninja", "Netflix"),
                doubleAction = ButtonAction.OpenSearch,
                longAction = ButtonAction.None
            )
        )

        // Simulate app restart / update on the same persisted state
        val persisted = dataStore1.ensureInitialized()
        assertEquals(2, persisted.mappings.size)
        val home = persisted.mappingFor(KeyEvent.KEYCODE_HOME)
        assertEquals(ButtonAction.GoToTopOfHome, home?.singleAction)
        assertEquals(ButtonAction.OpenProfileSwitcher, home?.doubleAction)
        assertEquals(ButtonAction.OpenSettings, home?.longAction)

        val netflix = persisted.mappingFor(RemoteButtonCatalog.KEYCODE_VIDEO_APP_1)
        assertEquals(
            ButtonAction.LaunchApp("com.netflix.ninja", "Netflix"),
            netflix?.singleAction
        )
        assertEquals(ButtonAction.OpenSearch, netflix?.doubleAction)
    }

    @Test
    fun `disabling Home customization falls back to factory Home defaults and restoreHomeDefaults resets Home`() = runBlocking {
        val dataStore = createDataStore("home_customization.preferences_pb")
        val repo = ButtonMappingRepository(dataStore, Dispatchers.Unconfined)

        repo.updatePressAction(
            keyCode = KeyEvent.KEYCODE_HOME,
            pressType = ButtonPressType.SINGLE_PRESS,
            action = ButtonAction.OpenSearch
        )
        assertEquals(
            ButtonAction.OpenSearch,
            repo.activeMappingFor(KeyEvent.KEYCODE_HOME, isGlobal = false)?.singleAction
        )

        // Disable Home customization -> activeMappingFor returns factory default Home mapping
        repo.setHomeCustomizationEnabled(false)
        val fallbackHome = repo.activeMappingFor(KeyEvent.KEYCODE_HOME, isGlobal = false)
        assertNotNull(fallbackHome)
        assertTrue(fallbackHome!!.matchesFactoryHomeDefaults())

        // Restore Home defaults -> re-enables customization and resets Home to factory defaults
        val restoredState = repo.restoreHomeDefaults()
        assertTrue(restoredState.homeCustomizationEnabled)
        assertTrue(restoredState.homeMapping().matchesFactoryHomeDefaults())
    }

    @Test
    fun `clearPressAction deleteMapping and resetAllToDefaults work as expected`() = runBlocking {
        val dataStore = createDataStore("crud_reset.preferences_pb")
        val repo = ButtonMappingRepository(dataStore, Dispatchers.Unconfined)

        // Add Red button mapping
        repo.upsertMapping(
            ButtonMapping(
                keyCode = KeyEvent.KEYCODE_PROG_RED,
                displayName = "Red",
                singleAction = ButtonAction.OpenLiveTv,
                doubleAction = ButtonAction.OpenTvGuide,
                longAction = ButtonAction.OpenApps
            )
        )
        assertEquals(2, repo.currentSnapshot().mappings.size)

        // Clear double action
        repo.clearPressAction(KeyEvent.KEYCODE_PROG_RED, ButtonPressType.DOUBLE_PRESS)
        assertEquals(
            ButtonAction.None,
            repo.currentSnapshot().mappingFor(KeyEvent.KEYCODE_PROG_RED)?.doubleAction
        )

        // RestoreDefault sentinel on Home single press restores GoToTopOfHome
        repo.updatePressAction(KeyEvent.KEYCODE_HOME, ButtonPressType.SINGLE_PRESS, ButtonAction.OpenLibrary)
        repo.updatePressAction(KeyEvent.KEYCODE_HOME, ButtonPressType.SINGLE_PRESS, ButtonAction.RestoreDefault)
        assertEquals(
            ButtonAction.GoToTopOfHome,
            repo.currentSnapshot().mappingFor(KeyEvent.KEYCODE_HOME)?.singleAction
        )

        // Delete Red mapping
        repo.deleteMapping(KeyEvent.KEYCODE_PROG_RED)
        assertNull(repo.currentSnapshot().mappingFor(KeyEvent.KEYCODE_PROG_RED))

        // Modify Home and then reset all to defaults
        repo.updatePressAction(KeyEvent.KEYCODE_HOME, ButtonPressType.DOUBLE_PRESS, ButtonAction.OpenApps)
        val afterReset = repo.resetAllToDefaults()
        assertEquals(1, afterReset.mappings.size)
        assertTrue(afterReset.homeMapping().matchesFactoryHomeDefaults())
    }

    @Test
    fun `capability status reflects system reservation and observed foreground vs global events`() = runBlocking {
        val dataStore = createDataStore("capabilities.preferences_pb")
        val repo = ButtonMappingRepository(dataStore, Dispatchers.Unconfined)

        // Volume and Power are always SYSTEM_RESERVED
        assertEquals(
            ButtonCapabilityStatus.SYSTEM_RESERVED,
            repo.capabilityStatusFor(KeyEvent.KEYCODE_VOLUME_UP, accessibilityEnabled = true)
        )
        assertEquals(
            ButtonCapabilityStatus.SYSTEM_RESERVED,
            repo.capabilityStatusFor(KeyEvent.KEYCODE_POWER, accessibilityEnabled = true)
        )

        // Unobserved custom button starts as UNKNOWN
        assertEquals(
            ButtonCapabilityStatus.UNKNOWN,
            repo.capabilityStatusFor(KeyEvent.KEYCODE_PROG_GREEN, accessibilityEnabled = true)
        )

        // Once observed in foreground -> AVAILABLE_IN_NUVIO
        repo.recordKeyObserved(KeyEvent.KEYCODE_PROG_GREEN, isGlobal = false)
        assertEquals(
            ButtonCapabilityStatus.AVAILABLE_IN_NUVIO,
            repo.capabilityStatusFor(KeyEvent.KEYCODE_PROG_GREEN, accessibilityEnabled = true)
        )

        // Once observed globally with Accessibility enabled -> AVAILABLE_GLOBALLY
        repo.recordKeyObserved(KeyEvent.KEYCODE_PROG_GREEN, isGlobal = true)
        assertEquals(
            ButtonCapabilityStatus.AVAILABLE_GLOBALLY,
            repo.capabilityStatusFor(KeyEvent.KEYCODE_PROG_GREEN, accessibilityEnabled = true)
        )
    }
}
