package com.nuvio.tv.ui.screens.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerRuntimeControllerErrorRecoveryTest {

    @Test
    fun decodingResourcesReclaimedIsRetryable() {
        val error = PlaybackException(
            "decoder reclaimed",
            null,
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED
        )
        assertTrue(isRetryablePlaybackError(error))
    }

    @Test
    fun resourceReclaimCanUseTheSoftwareDecoderFallback() {
        assertTrue(
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED in VIDEO_DECODER_ERROR_CODES
        )
    }
}
