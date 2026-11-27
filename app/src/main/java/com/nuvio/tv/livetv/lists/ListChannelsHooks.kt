package com.nuvio.tv.livetv.lists

import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live TV fork: the viewer's list channels (made from Home rows, folders and library lists) are kept per
 * profile; switching profile switches the channels. Called once from `MainActivity.onCreate`.
 */
fun ComponentActivity.attachListChannelsToProfile(profileManager: ProfileManager) {
    lifecycleScope.launch {
        profileManager.activeProfileId.collect { id ->
            runCatching {
                withContext(Dispatchers.IO) {
                    ListChannels.attach(applicationContext, id)
                }
            }
        }
    }
}
