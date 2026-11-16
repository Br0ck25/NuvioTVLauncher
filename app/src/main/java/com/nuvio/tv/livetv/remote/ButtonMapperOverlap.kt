package com.nuvio.tv.livetv.remote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.core.launcher.ButtonMapperEntryPoint
import com.nuvio.tv.core.launcher.ButtonMapperPersistedState
import com.nuvio.tv.core.launcher.ButtonMappingRepository
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Remote Buttons (per Live TV screen) and the Button Mapper (device wide) can both be given the same physical button.
 * The Button Mapper reads a press first, so when both have it the Live TV setting never runs. These helpers let each
 * page say so instead of failing silently.
 */
object ButtonMapperOverlap {
    /** Whether the Button Mapper has an active mapping for any key of [button] (what it would take before Remote Buttons sees it). */
    fun mapperHas(repository: ButtonMappingRepository?, button: RemoteButton): Boolean =
        repository != null && button.codes.any { repository.activeMappingFor(it, isGlobal = false) != null }

    /** The titles of the Live TV screens where the button of [keyCode] has been changed; empty when none. */
    fun liveTvScreensFor(keyCode: Int, state: RemoteState): List<String> {
        // Not RemoteButtons.buttonFor: that object needs the app's main thread. A learned button is rebuilt from the state.
        val button = RemoteCatalog.buttonForCode(keyCode)
            ?: state.learned.firstOrNull { it.code == keyCode }?.let { RemoteButton("K${it.code}", it.name, "KEY", listOf(it.code)) }
            ?: return emptyList()
        return RemoteCatalog.SCREENS.filter { state.of(it.id, button.id).isNotEmpty() }.map { it.title }
    }
}

/** A check, kept current, for "does the Button Mapper take this button?". */
@Composable
internal fun rememberButtonMapperTaken(): (RemoteButton) -> Boolean {
    val context = LocalContext.current.applicationContext
    val repository = remember {
        runCatching { EntryPointAccessors.fromApplication(context, ButtonMapperEntryPoint::class.java).buttonMappingRepository() }.getOrNull()
    }
    val flow = remember(repository) { repository?.state ?: MutableStateFlow(ButtonMapperPersistedState()) }
    val snapshot by flow.collectAsStateWithLifecycle()
    return remember(repository, snapshot) { { button: RemoteButton -> ButtonMapperOverlap.mapperHas(repository, button) } }
}
