package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.AuthSessionNoticeDataStore
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktAuthState
import com.nuvio.tv.data.remote.api.TraktApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

class TraktAuthServiceTest {
    // The service refuses to refresh without a Trakt client id, which comes from local.properties or the
    // CI secrets. Without one these tests have nothing to exercise, so skip them instead of failing.
    @Before
    fun requireTraktClientId() {
        assumeTrue("TRAKT_CLIENT_ID is not set", BuildConfig.TRAKT_CLIENT_ID.isNotBlank())
    }

    @Test
    fun `refresh token 400 clears credentials and prevents another refresh`() = runTest {
        val traktApi = mockk<TraktApi>()
        val traktAuthDataStore = mockk<TraktAuthDataStore>()
        val authSessionNoticeDataStore = mockk<AuthSessionNoticeDataStore>()
        var authState = authenticatedState()

        coEvery { traktAuthDataStore.getCurrentState() } answers { authState }
        coEvery { traktAuthDataStore.clearAuth() } answers { authState = TraktAuthState() }
        coEvery { authSessionNoticeDataStore.markTraktReconnectRequired() } returns Unit
        coEvery { traktApi.refreshToken(any()) } returns Response.error(400, "invalid_grant".toResponseBody())

        val service = TraktAuthService(
            context = mockk<Context>(relaxed = true),
            traktApi = traktApi,
            traktAuthDataStore = traktAuthDataStore,
            authSessionNoticeDataStore = authSessionNoticeDataStore
        )

        assertFalse(service.refreshTokenIfNeeded(force = true))
        assertFalse(service.refreshTokenIfNeeded(force = true))

        coVerify(exactly = 1) { traktApi.refreshToken(any()) }
        coVerify(exactly = 1) { authSessionNoticeDataStore.markTraktReconnectRequired() }
        coVerify(exactly = 1) { traktAuthDataStore.clearAuth() }
    }

    @Test
    fun `refresh token server error keeps credentials and allows a later retry`() = runTest {
        val traktApi = mockk<TraktApi>()
        val traktAuthDataStore = mockk<TraktAuthDataStore>()
        val authSessionNoticeDataStore = mockk<AuthSessionNoticeDataStore>()
        val authState = authenticatedState()

        coEvery { traktAuthDataStore.getCurrentState() } returns authState
        coEvery { traktApi.refreshToken(any()) } returns Response.error(503, "unavailable".toResponseBody())

        val service = TraktAuthService(
            context = mockk<Context>(relaxed = true),
            traktApi = traktApi,
            traktAuthDataStore = traktAuthDataStore,
            authSessionNoticeDataStore = authSessionNoticeDataStore
        )

        assertFalse(service.refreshTokenIfNeeded(force = true))
        assertFalse(service.refreshTokenIfNeeded(force = true))

        coVerify(exactly = 2) { traktApi.refreshToken(any()) }
        coVerify(exactly = 0) { authSessionNoticeDataStore.markTraktReconnectRequired() }
        coVerify(exactly = 0) { traktAuthDataStore.clearAuth() }
    }

    private fun authenticatedState(): TraktAuthState {
        return TraktAuthState(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            tokenType = "bearer",
            createdAt = 1L,
            expiresIn = 3600
        )
    }
}
