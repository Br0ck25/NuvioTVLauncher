package com.nuvio.tv.music

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/** One folder or audio file on a WebDAV server. */
data class WebDavEntry(
    val name: String,
    val url: String,
    val isDirectory: Boolean,
    val size: Long = 0L
)

data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = ""
) {
    val configured: Boolean get() = url.isNotBlank()
}

/**
 * A deliberately small WebDAV client for the Music section.
 *
 * It only needs to list a collection and stream audio files; the files themselves are handed
 * to ExoPlayer as ordinary HTTP URLs, with the server's Basic auth header attached.
 */
object MusicWebDav {
    private const val PREFS = "nuvio_music_webdav"
    private const val KEY_URL = "url"
    private const val KEY_USER = "user"
    private const val KEY_PASSWORD = "password"

    private val audioExtensions = setOf(
        "mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "m4b"
    )

    private val _config = MutableStateFlow(WebDavConfig())
    val config: StateFlow<WebDavConfig> = _config.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private var appContext: Context? = null

    @Synchronized
    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _config.value = WebDavConfig(
            url = prefs.getString(KEY_URL, "").orEmpty(),
            username = prefs.getString(KEY_USER, "").orEmpty(),
            password = prefs.getString(KEY_PASSWORD, "").orEmpty()
        )
    }

    fun save(context: Context, url: String, username: String, password: String) {
        val app = context.applicationContext
        appContext = app
        val normalized = url.trim().let { if (it.endsWith("/")) it else "$it/" }
        _config.value = WebDavConfig(normalized, username.trim(), password)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_URL, normalized)
            .putString(KEY_USER, username.trim())
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun clear(context: Context) {
        val app = context.applicationContext
        appContext = app
        _config.value = WebDavConfig()
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Headers for ExoPlayer when it opens a URL from this server. */
    fun headersFor(url: String): Map<String, String> {
        val current = _config.value
        if (!current.configured || current.username.isBlank()) return emptyMap()
        val server = runCatching { URI(current.url) }.getOrNull() ?: return emptyMap()
        val target = runCatching { URI(url) }.getOrNull() ?: return emptyMap()
        if (!target.scheme.equals(server.scheme, ignoreCase = true) ||
            !target.host.equals(server.host, ignoreCase = true)
        ) {
            return emptyMap()
        }
        return mapOf("Authorization" to Credentials.basic(current.username, current.password))
    }

    /** Saves and verifies a server before keeping the new configuration. */
    suspend fun connect(
        context: Context,
        url: String,
        username: String,
        password: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val previous = _config.value
        save(context, url, username, password)
        val result = list(context, _config.value.url)
        if (result.isFailure) {
            if (previous.configured) {
                save(context, previous.url, previous.username, previous.password)
            } else {
                clear(context)
            }
        }
        result.map { }
    }

    fun isAudio(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in audioExtensions

    /**
     * Lists one collection. [url] is the absolute URL of the directory. Relative hrefs are
     * resolved against it, which is what most WebDAV servers return.
     */
    suspend fun list(context: Context, url: String): Result<List<WebDavEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                init(context)
                val current = _config.value
                require(current.configured) { "Add a WebDAV server first." }
                val requestBody = """
                    <?xml version="1.0" encoding="utf-8" ?>
                    <d:propfind xmlns:d="DAV:">
                      <d:prop>
                        <d:displayname />
                        <d:resourcetype />
                        <d:getcontentlength />
                      </d:prop>
                    </d:propfind>
                """.trimIndent().toRequestBody("application/xml; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(url)
                    .method("PROPFIND", requestBody)
                    .header("Depth", "1")
                    .header("Accept", "application/xml, text/xml")
                    .apply {
                        if (current.username.isNotBlank()) {
                            header(
                                "Authorization",
                                Credentials.basic(current.username, current.password)
                            )
                        }
                    }
                    .build()
                client.newCall(request).execute().use { response ->
                    require(response.isSuccessful) {
                        "WebDAV returned HTTP ${response.code}."
                    }
                    val body = response.body?.string().orEmpty()
                    parseEntries(body, url)
                }
            }
        }

    private fun parseEntries(xml: String, baseUrl: String): List<WebDavEntry> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(xml.reader())
        val entries = mutableListOf<WebDavEntry>()
        var href: String? = null
        var name: String? = null
        var directory = false
        var size = 0L
        val base = baseUrl.trimEnd('/')

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "response" -> {
                        href = null
                        name = null
                        directory = false
                        size = 0L
                    }
                    "href" -> href = parser.nextText()
                    "displayname" -> name = parser.nextText()
                    "collection" -> directory = true
                    "getcontentlength" -> size = parser.nextText().toLongOrNull() ?: 0L
                }
                XmlPullParser.END_TAG -> if (parser.name == "response") {
                    val rawHref = href
                    if (!rawHref.isNullOrBlank()) {
                        val resolved = runCatching {
                            URI(baseUrl).resolve(rawHref).toString()
                        }.getOrDefault(rawHref)
                        if (resolved.trimEnd('/') != base && resolved.trimEnd('/') != baseUrl) {
                            val display = name?.takeIf { it.isNotBlank() }
                                ?: decodeName(resolved)
                            if (directory || display.isNotBlank()) {
                                entries += WebDavEntry(
                                    name = display.ifBlank { "Folder" },
                                    url = resolved,
                                    isDirectory = directory,
                                    size = size
                                )
                            }
                        }
                    }
                }
            }
            parser.next()
        }
        return entries.sortedWith(
            compareBy<WebDavEntry> { !it.isDirectory }
                .thenBy { it.name.lowercase() }
        )
    }

    private fun decodeName(url: String): String {
        val part = url.trimEnd('/').substringAfterLast('/')
        return runCatching { URLDecoder.decode(part, "UTF-8") }.getOrDefault(part)
    }
}
