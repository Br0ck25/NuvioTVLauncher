package com.nuvio.tv.livetv.stalker

import com.nuvio.stalker.StalkerClient
import com.nuvio.stalker.StalkerConfig
import com.nuvio.stalker.StalkerContentKind
import com.nuvio.stalker.StalkerError
import com.nuvio.stalker.StalkerException
import com.nuvio.stalker.StalkerHttpResponse
import com.nuvio.stalker.StalkerGuideHttpResponse
import com.nuvio.stalker.StalkerGuideReader
import com.nuvio.stalker.StalkerGuideRow
import com.nuvio.stalker.StalkerStreamingGuideTransport
import com.nuvio.stalker.StalkerResolvedStream
import com.nuvio.stalker.StalkerStreamRef
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.PlaylistSource
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One in-memory, shared session per configured source; temporary links are never cached. */
@Singleton
class StalkerGateway @Inject constructor(
    private val prefs: LiveTvPreferences,
    private val profiles: ProfileManager,
    private val profileDataStore: ProfileDataStore,
) {
    private class OwnedPolicy(val source: PlaylistSource, val policy: WeakReference<StalkerMediaPolicy>)
    private class Session(val config: StalkerConfig, val client: StalkerClient)
    private val mutex = Mutex()
    private val sessions = mutableMapOf<String, Session>()
    private val policies = mutableMapOf<String, MutableList<OwnedPolicy>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        // Portal identity headers may only follow a redirect to the same origin.
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .build()

    init {
        // Backup restores also edit sources without going through the settings ViewModel.
        scope.launch { prefs.playlists.collect {
            mutex.withLock {
                val current = prefs.currentPlaylists().associateBy { it.id }
                sessions.keys.toList().forEach { id ->
                    val source = current[id]
                    if (source == null || !source.enabled || !source.isStalker || source.stalkerConfig != sessions[id]?.config) retire(id)
                }
                policies.entries.removeAll { (id, owned) ->
                    val latest = current[id]
                    owned.removeAll { entry ->
                        val policy = entry.policy.get()
                        val changed = latest == null || !latest.enabled || !latest.isStalker ||
                            latest.stalkerConfig != entry.source.stalkerConfig || latest.importLive != entry.source.importLive ||
                            latest.importVod != entry.source.importVod
                        if (changed) policy?.retire()
                        changed || policy == null
                    }
                    owned.isEmpty()
                }
            }
        } }
    }

    suspend fun client(source: PlaylistSource): StalkerClient = withContext(Dispatchers.IO) {
        awaitProfile()
        val config = source.stalkerConfig?.takeIf { source.isStalker }
            ?: throw StalkerException(StalkerError.INVALID_CONFIG)
        assertCurrent(source)
        mutex.withLock {
            assertCurrent(source)
            val existing = sessions[source.id]
            if (existing != null && existing.config == config) return@withLock existing.client
            retire(source.id)
            // Two source rows for one identity must not handshake over one another.
            sessions.values.firstOrNull { it.config == config }?.let {
                sessions[source.id] = it
                return@withLock it.client
            }
            val created = StalkerClient(
                config, object : StalkerStreamingGuideTransport {
                    override suspend fun get(url: String, headers: Map<String, String>) = request(url, headers)
                    override suspend fun getGuide(url: String, headers: Map<String, String>, onRow: (String?, StalkerGuideRow) -> Unit) =
                        guideRequest(url, headers, onRow)
                },
                System::currentTimeMillis, ::parsePortalTime,
            )
            sessions[source.id] = Session(config, created)
            created
        }
    }

    suspend fun invalidate(sourceId: String) {
        withContext(Dispatchers.IO) { mutex.withLock {
            policies.remove(sourceId)?.forEach { it.policy.get()?.retire() }
            retire(sourceId)
        } }
    }

    private suspend fun retire(sourceId: String) {
        val removed = sessions.remove(sourceId) ?: return
        if (sessions.values.none { it === removed }) removed.client.invalidate()
    }

    suspend fun assertCurrent(source: PlaylistSource) {
        val current = prefs.currentPlaylists().firstOrNull { it.id == source.id }
        if (current == null || !current.isStalker || !current.enabled ||
            current.stalkerConfig != source.stalkerConfig || current.importLive != source.importLive ||
            current.importVod != source.importVod
        ) throw StalkerException(StalkerError.STALE_SESSION)
    }

    suspend fun resolve(sourceId: String, ref: StalkerStreamRef): StalkerResolvedStream {
        awaitProfile()
        val source = prefs.currentPlaylists().firstOrNull { it.id == sourceId && it.isStalker && it.enabled }
            ?: throw StalkerException(StalkerError.STALE_SESSION)
        if (ref.sourceIdentity == null || ref.sourceIdentity != identity(source))
            throw StalkerException(StalkerError.STALE_SESSION)
        if (ref.kind in setOf(StalkerContentKind.LIVE, StalkerContentKind.ARCHIVE)) {
            if (!source.liveEnabled) throw StalkerException(StalkerError.STALE_SESSION)
        } else if (!source.importVod) throw StalkerException(StalkerError.STALE_SESSION)
        val stream = client(source).resolve(ref)
        val latest = prefs.currentPlaylists().firstOrNull { it.id == sourceId && it.isStalker }
        if (latest?.stalkerConfig != source.stalkerConfig) {
            invalidate(sourceId)
            throw StalkerException(StalkerError.STALE_SESSION)
        }
        // The resolved CDN link receives only stream headers, never portal Cookie/Bearer.
        assertCurrent(source)
        return stream
    }

    suspend fun resolve(channel: LiveChannel): StalkerResolvedStream = channel.stalkerRef?.let {
        resolve(channel.sourceId, it)
    } ?: StalkerResolvedStream(channel.url, channel.headers)

    private suspend fun awaitProfile() = withTimeout(10_000L) {
        val savedId = profileDataStore.activeProfileId.first()
        // ProfileManager starts with a placeholder; do not read another profile's sources.
        profiles.activeProfileId.first { it == savedId }
    }

    suspend fun prepare(channel: LiveChannel): StalkerPlayback {
        val ref = channel.stalkerRef ?: throw StalkerException(StalkerError.INVALID_CONFIG)
        return prepare(channel.sourceId, ref)
    }

    suspend fun prepare(sourceId: String, ref: StalkerStreamRef): StalkerPlayback {
        val stream = resolve(sourceId, ref)
        val source = prefs.currentPlaylists().firstOrNull { it.id == sourceId && it.isStalker && it.enabled }
            ?: throw StalkerException(StalkerError.STALE_SESSION)
        if (ref.sourceIdentity != identity(source)) throw StalkerException(StalkerError.STALE_SESSION)
        val authorization = client(source).mediaAuthorization()
        assertCurrent(source)
        val policy = StalkerMediaPolicy(authorization,
            URI(stream.url).scheme.equals("https", true)) { _ ->
            // Called on the HTTP worker, including every redirected/child request.
            runBlocking { assertCurrent(source) }
        }
        mutex.withLock {
            assertCurrent(source)
            val owned = policies.getOrPut(sourceId) { mutableListOf() }
            owned.removeAll { it.policy.get() == null }
            owned += OwnedPolicy(source, WeakReference(policy))
        }
        try {
            val mimeType = StalkerMimeProbe.mimeFromUrl(stream.url) ?: policy.probeMimeType(stream.url)
            assertCurrent(source)
            return StalkerPlayback(stream.url, stream.headers, policy, mimeType)
        } catch (failure: Exception) {
            policy.retire()
            throw failure
        }
    }

    private suspend fun request(url: String, headers: Map<String, String>): StalkerHttpResponse =
        withContext(Dispatchers.IO) {
            val (response, address) = open(url, headers)
            response.use {
                val body = response.body ?: throw StalkerException(StalkerError.INVALID_RESPONSE)
                val text = StringBuilder()
                body.charStream().use { reader ->
                    val buffer = CharArray(8 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = reader.read(buffer)
                        if (count < 0) break
                        if (text.length + count > MAX_RESPONSE_CHARS) throw StalkerException(StalkerError.TOO_LARGE)
                        text.append(buffer, 0, count)
                    }
                }
                StalkerHttpResponse(response.code, text.toString(), finalUrl = address)
            }
        }

    private suspend fun guideRequest(url: String, headers: Map<String, String>,
        onRow: (String?, StalkerGuideRow) -> Unit): StalkerGuideHttpResponse = withContext(Dispatchers.IO) {
        val (response, address) = open(url, headers)
        response.use {
            val body = response.body ?: throw StalkerException(StalkerError.INVALID_RESPONSE)
            val context = coroutineContext
            val error = body.charStream().use { reader -> StalkerGuideReader.read(reader) { id, row ->
                context.ensureActive()
                onRow(id, row)
            } }
            StalkerGuideHttpResponse(response.code, error, finalUrl = address)
        }
    }

    private suspend fun open(url: String, headers: Map<String, String>): Pair<Response, String> =
        withContext(Dispatchers.IO) {
            val origin = URI(url)
            var address = url
            repeat(6) { hop ->
                coroutineContext.ensureActive()
                val request = Request.Builder().url(address).apply { headers.forEach { (name, value) -> header(name, value) } }.build()
                val call = http.newCall(request)
                val response = call.await()
                try {
                    val location = response.header("Location")
                    if (response.code in setOf(301, 302, 303, 307, 308)) {
                        if (hop == 5 || location.isNullOrBlank()) throw StalkerException(StalkerError.NETWORK)
                        val target = URI(address).resolve(location)
                        if (!sameOrigin(origin, target)) throw StalkerException(StalkerError.NETWORK)
                        address = target.toString()
                    } else {
                        return@withContext response to address
                    }
                } catch (cancelled: CancellationException) {
                    call.cancel()
                    throw cancelled
                } catch (failure: Exception) {
                    response.close()
                    throw failure
                }
                run {
                    response.close()
                }
            }
            throw StalkerException(StalkerError.NETWORK)
        }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(StalkerException(StalkerError.NETWORK))
            }
            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) { _, value, _ -> value.close() }
                else response.close()
            }
        })
    }

    companion object {
        private const val MAX_RESPONSE_CHARS = 32 * 1024 * 1024
        fun identity(source: PlaylistSource): String = source.stalkerConfig?.let(::configIdentity).orEmpty()
        fun configIdentity(config: StalkerConfig): String = MessageDigest.getInstance("SHA-256")
            .digest(config.toJson().toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        fun failureMessage(failure: Throwable): String = when {
            failure is StalkerException -> failure.message.orEmpty()
            failure is IOException && failure.message == StalkerMimeProbe.DASH_RECORDING_UNAVAILABLE -> StalkerMimeProbe.DASH_RECORDING_UNAVAILABLE
            else -> "The Stalker stream could not be opened. Check the portal and retry."
        }
        internal fun sameOrigin(origin: URI, target: URI): Boolean =
            target.scheme?.lowercase() in setOf("http", "https") &&
                origin.scheme.equals(target.scheme, true) && origin.host.equals(target.host, true) &&
                port(origin) == port(target) && target.userInfo == null && target.fragment == null

        private fun port(uri: URI): Int = if (uri.port != -1) uri.port else if (uri.scheme.equals("https", true)) 443 else 80

        private fun parsePortalTime(value: String, timezone: String): Long? = runCatching {
            val formatter = if ('T' in value) DateTimeFormatter.ISO_LOCAL_DATE_TIME else DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)
            LocalDateTime.parse(value, formatter).atZone(ZoneId.of(timezone)).toInstant().toEpochMilli()
        }.getOrNull()
    }
}
