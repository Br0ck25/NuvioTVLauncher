package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Carries "this movie just finished" from the player to the detail screen.
 *
 * It is only a nudge: the detail screen decides whether to ask, based on the user's setting and the
 * fact that it is showing the same title. Nothing is recorded, and it never triggers for episodes.
 */
object MovieRatingPromptBus {
    data class Request(val contentId: String)

    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> = _request.asStateFlow()

    fun request(contentId: String) {
        if (contentId.isNotBlank()) _request.value = Request(contentId.trim())
    }

    fun consume() {
        _request.value = null
    }
}
