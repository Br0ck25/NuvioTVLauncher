package com.liskovsoft.youtubeapi.app.visitor

import android.content.Context
import android.os.Looper
import android.util.Log
import com.liskovsoft.googlecommon.common.locale.LocaleManager
import com.liskovsoft.sharedutils.okhttp.OkHttpManager
import com.liskovsoft.youtubeapi.app.AppService
import com.liskovsoft.youtubeapi.app.PoTokenGate
import com.liskovsoft.youtubeapi.common.helpers.AppClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * YouTube issues a visitor id per client. Reusing one visitor id for every client raises the
 * bot score, which surfaces as 403s and missing formats. Keep a separate, persisted id for each
 * client, and reuse the PoToken visitor when that client's token is bound to it.
 *
 * This mirrors the visitor handling in Morphe's patched YouTube client, implemented against our
 * own request stack instead of patching bytecode.
 */
object ClientVisitorService {
    private const val TAG = "YouTubeVisitor"
    private const val TTL_MS = 30L * 24 * 60 * 60 * 1000
    private const val PREFS = "youtube_client_visitors"

    private const val FAILURE_BACKOFF_MS = 5L * 60 * 1000

    private data class Entry(val visitorId: String, val fetchedAtMs: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val failedUntil = ConcurrentHashMap<String, Long>()

    private val prefs by lazy {
        AppService.instance().context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private val http by lazy {
        OkHttpManager.instance().client.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @JvmStatic
    fun getVisitorData(client: AppClient): String? {
        // A PoToken is minted for a specific visitor. Sending a different visitor with it
        // makes the token useless.
        PoTokenGate.getVisitorData(client)?.takeIf { it.isNotBlank() }?.let {
            Log.i(TAG, "using PoToken visitor for ${client.name}")
            return it
        }

        load(client)?.let {
            Log.i(TAG, "using cached visitor for ${client.name}")
            return it
        }

        // Never block playback on the main thread for an optional optimisation.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return AppService.instance().visitorData
        }

        if ((failedUntil[client.name] ?: 0L) > System.currentTimeMillis()) {
            return AppService.instance().visitorData
        }

        val fetched = fetch(client)
        if (fetched == null) {
            failedUntil[client.name] = System.currentTimeMillis() + FAILURE_BACKOFF_MS
            return AppService.instance().visitorData
        }

        failedUntil.remove(client.name)
        save(client, fetched)
        Log.i(TAG, "fetched visitor for ${client.name}")
        return fetched
    }

    /** The player response sometimes rotates the visitor id for that client. */
    @JvmStatic
    fun updateFromPlayer(client: AppClient, visitorId: String?) {
        if (!visitorId.isNullOrBlank()) save(client, visitorId)
    }

    private fun load(client: AppClient): String? {
        val now = System.currentTimeMillis()
        cache[client.name]?.let { entry ->
            if (now - entry.fetchedAtMs < TTL_MS) return entry.visitorId
            cache.remove(client.name)
        }

        val raw = prefs.getString(client.name, null) ?: return null
        val parts = raw.split('|', limit = 2)
        val visitorId = parts.getOrNull(0)
        val fetchedAtMs = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        if (visitorId.isNullOrBlank() || now - fetchedAtMs >= TTL_MS) return null

        cache[client.name] = Entry(visitorId, fetchedAtMs)
        return visitorId
    }

    private fun save(client: AppClient, visitorId: String) {
        val now = System.currentTimeMillis()
        cache[client.name] = Entry(visitorId, now)
        prefs.edit().putString(client.name, "$visitorId|$now").apply()
    }

    private fun fetch(client: AppClient): String? {
        val clientNameId = client.innerTubeName ?: return null
        // TVHTML5 does not expose /visitor_id; its guide call returns the same context.
        val endpoint = if (client.isTVClient) "guide" else "visitor_id"
        val url = "https://youtubei.googleapis.com/youtubei/v1/$endpoint" +
            "?prettyPrint=false&fields=responseContext.visitorData"

        val locale = LocaleManager.instance()
        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", client.clientName)
                    put("clientVersion", client.clientVersion)
                    put("hl", locale.language)
                    put("gl", locale.country)
                    put("utcOffsetMinutes", locale.utcOffsetMinutes)
                })
                put("request", JSONObject().put("useSsl", true))
                put("user", JSONObject().put("lockedSafetyMode", false))
            })
        }.toString()

        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("User-Agent", client.userAgent)
            .header("X-YouTube-Client-Name", clientNameId)
            .header("X-YouTube-Client-Version", client.clientVersion)
            .build()

        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "visitor lookup ${client.name} -> HTTP ${response.code}")
                    return null
                }
                val text = response.body?.string().orEmpty()
                JSONObject(text)
                    .optJSONObject("responseContext")
                    ?.optString("visitorData")
                    ?.takeIf { it.isNotBlank() }
            }
        }.onFailure {
            Log.w(TAG, "visitor lookup ${client.name} failed: ${it.message}")
        }.getOrNull()
    }
}
