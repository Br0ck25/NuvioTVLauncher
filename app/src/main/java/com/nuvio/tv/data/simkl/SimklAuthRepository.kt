package com.nuvio.tv.data.simkl

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimklAuthRepository(
    private val apiClient: SimklApiClient,
    private val configuration: SimklApiConfiguration,
    private val storage: SimklAuthStorage,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    @Inject
    constructor(
        apiClient: SimklApiClient,
        configuration: SimklApiConfiguration,
        storage: SimklAuthStorage
    ) : this(apiClient, configuration, storage, System::currentTimeMillis)

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val state: StateFlow<SimklAuthState> = storage.state

    fun hasRequiredCredentials(): Boolean = configuration.clientId.isNotBlank()

    suspend fun startPinAuth(): SimklPinSession = mutex.withLock {
        val authScope = storage.currentScope()
        if (!hasRequiredCredentials()) throw SimklAuthException(SimklAuthError.MISSING_CLIENT_ID)
        val response = requestDeviceOrPinCode()
        val payload = decodePinResponse(response.body)
        val deviceCode = if (payload.result == null) {
            payload.deviceCode?.trim()?.takeIf(String::isNotBlank)
        } else {
            null
        }
        val userCode = payload.userCode?.trim()?.takeIf(String::isNotBlank)
        val verificationUri = (payload.verificationUri ?: payload.verificationUrl)
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val expiresIn = payload.expiresIn?.takeIf { it > 0L }
        val interval = payload.interval?.coerceAtLeast(1)
        val isValidStatus = payload.result == "OK" || (payload.result == null && deviceCode != null)
        if (!isValidStatus || userCode == null || verificationUri == null || expiresIn == null || interval == null) {
            throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE)
        }
        val session = SimklPinSession(
            userCode = userCode,
            verificationUri = verificationUri,
            expiresAtEpochMs = nowEpochMs() + expiresIn * 1_000L,
            intervalSeconds = interval,
            deviceCode = deviceCode
        )
        if (!storage.savePinSession(session, authScope)) {
            throw SimklAuthException(SimklAuthError.PIN_INVALIDATED)
        }
        session
    }

    suspend fun pollPin(): SimklPinPollResult = mutex.withLock {
        val authScope = storage.currentScope()
        val session = storage.state.value.pinSession ?: return@withLock SimklPinPollResult.Invalidated
        if (nowEpochMs() >= session.expiresAtEpochMs) {
            storage.clearPinSession(SimklAuthError.PIN_EXPIRED, authScope)
            return@withLock SimklPinPollResult.Expired
        }
        if (!PIN_PATTERN.matches(session.userCode)) {
            storage.clearPinSession(SimklAuthError.PIN_INVALIDATED, authScope)
            return@withLock SimklPinPollResult.Invalidated
        }
        val deviceCode = session.deviceCode?.trim()?.takeIf(String::isNotBlank)
        if (deviceCode != null) {
            return@withLock pollOAuth2DeviceToken(deviceCode, authScope)
        }
        val response = executeAuthRequest(
            SimklApiRequest(
                method = SimklHttpMethod.GET,
                path = "/oauth/pin/${session.userCode}",
                requiresAuthentication = false
            )
        )
        val payload = decodePinResponse(response.body)
        if (!payload.deviceCode.isNullOrBlank()) {
            storage.clearPinSession(SimklAuthError.PIN_INVALIDATED, authScope)
            return@withLock SimklPinPollResult.Invalidated
        }
        val token = payload.accessToken?.trim()?.takeIf(String::isNotBlank)
        if (payload.result == "OK" && token != null) {
            if (!storage.completePinAuthorization(token, authScope)) {
                return@withLock SimklPinPollResult.Invalidated
            }
            try {
                refreshUserSettings(scope = authScope)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                Unit
            }
            return@withLock SimklPinPollResult.Authorized
        }
        if (payload.result == "KO") return@withLock SimklPinPollResult.Pending
        throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE)
    }

    suspend fun refreshUserSettings(
        activityWatermark: String? = null,
        scope: SimklAuthScope = storage.currentScope()
    ): String? {
        if (!storage.isCurrent(scope) || !storage.state.value.isAuthenticated) return null
        val response = apiClient.execute(
            request = SimklApiRequest(SimklHttpMethod.POST, "/users/settings"),
            expectedAuthScope = scope
        )
        val settings = runCatching { json.decodeFromString<SimklUserSettingsResponse>(response.body) }.getOrNull()
            ?: return null
        val username = settings.user?.name?.trim()?.takeIf(String::isNotBlank)
        val saved = storage.saveIdentity(
            username = username,
            accountId = settings.account?.id,
            settingsActivityWatermark = activityWatermark,
            scope = scope
        )
        return username.takeIf { saved }
    }

    suspend fun synchronizeUserSettings(activityWatermark: String?) {
        val authScope = storage.currentScope()
        if (!storage.state.value.isAuthenticated) return
        try {
            when (simklSettingsRefreshAction(storage.state.value, activityWatermark)) {
                SimklSettingsRefreshAction.NONE -> Unit
                SimklSettingsRefreshAction.RECORD_WATERMARK -> {
                    storage.recordSettingsActivityWatermark(requireNotNull(activityWatermark), authScope)
                }
                SimklSettingsRefreshAction.FETCH -> refreshUserSettings(activityWatermark, authScope)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            Unit
        }
    }

    fun cancelPinAuth() = storage.clearPinSession()

    fun disconnect() = storage.clearAuth()

    private suspend fun requestDeviceOrPinCode(): SimklApiResponse {
        val deviceRequest = SimklApiRequest(
            method = SimklHttpMethod.POST,
            path = "/oauth2/device",
            body = json.encodeToString(SimklDeviceCodeRequest(clientId = configuration.clientId)),
            requiresAuthentication = false
        )
        return try {
            apiClient.execute(deviceRequest)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SimklApiException) {
            if (error.status == 400 || error.status == 404 || error.status == 405) {
                executeAuthRequest(
                    SimklApiRequest(
                        method = SimklHttpMethod.GET,
                        path = "/oauth/pin",
                        requiresAuthentication = false
                    )
                )
            } else {
                throw SimklAuthException(SimklAuthError.NETWORK, error)
            }
        } catch (error: Throwable) {
            throw SimklAuthException(SimklAuthError.NETWORK, error)
        }
    }

    private suspend fun pollOAuth2DeviceToken(
        deviceCode: String,
        authScope: SimklAuthScope
    ): SimklPinPollResult {
        val response = try {
            apiClient.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.POST,
                    path = "/oauth2/token",
                    body = json.encodeToString(
                        SimklDeviceTokenRequest(
                            deviceCode = deviceCode,
                            clientId = configuration.clientId
                        )
                    ),
                    requiresAuthentication = false,
                    retryPolicy = SimklRetryPolicy.NEVER
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: SimklApiException) {
            return when (error.errorCode) {
                "authorization_pending", "slow_down" -> SimklPinPollResult.Pending
                "expired_token" -> {
                    storage.clearPinSession(SimklAuthError.PIN_EXPIRED, authScope)
                    SimklPinPollResult.Expired
                }
                "access_denied", "invalid_grant" -> {
                    storage.clearPinSession(SimklAuthError.PIN_INVALIDATED, authScope)
                    SimklPinPollResult.Invalidated
                }
                else -> throw SimklAuthException(SimklAuthError.NETWORK, error)
            }
        } catch (error: Throwable) {
            throw SimklAuthException(SimklAuthError.NETWORK, error)
        }

        val payload = decodePinResponse(response.body)
        val token = payload.accessToken?.trim()?.takeIf(String::isNotBlank)
            ?: throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE)
        if (!storage.completePinAuthorization(token, authScope)) {
            return SimklPinPollResult.Invalidated
        }
        try {
            refreshUserSettings(scope = authScope)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            Unit
        }
        return SimklPinPollResult.Authorized
    }

    private suspend fun executeAuthRequest(request: SimklApiRequest): SimklApiResponse = try {
        apiClient.execute(request)
    } catch (error: CancellationException) {
        throw error
    } catch (error: SimklAuthException) {
        throw error
    } catch (error: Throwable) {
        throw SimklAuthException(SimklAuthError.NETWORK, error)
    }

    private fun decodePinResponse(body: String): SimklPinResponse =
        runCatching { json.decodeFromString<SimklPinResponse>(body) }
            .getOrElse { throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE, it) }

    private companion object {
        val PIN_PATTERN = Regex("[A-Za-z0-9-]{4,16}")
    }
}
