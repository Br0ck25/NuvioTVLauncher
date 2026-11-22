package com.nuvio.tv.youtube

import android.util.Log
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Translates caption text for the auto-translated tracks.
 *
 * YouTube hands translated captions only to its own apps, so the video's own captions are fetched
 * and translated here. The free Google endpoint that does the translating refuses some networks
 * outright: instead of a translation it answers with a captcha (HTTP 302 to google.com/sorry), and
 * a home connection behind a VPN or a shared IP can stay barred for a long time.
 *
 * So more than one translator is tried. A provider that refuses is put on a cool-down and skipped
 * for the next few minutes, which keeps the page from waiting on a service that is already saying
 * no. Lines that did get translated are remembered in memory and on disk, so seeking back over the
 * same minute costs no requests at all.
 */
internal object YouTubeCaptionTranslation {
    private const val TAG = "YouTubeCaptions"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    /** How long a refusing translator is skipped for. */
    private const val COOLDOWN_MS = 10 * 60 * 1000L
    /** MyMemory answers a single GET, and the free endpoint caps the query at about 500 URL-encoded bytes. */
    private const val MYMEMORY_MAX_CHARS = 300
    /** Translated lines kept in memory for the session. */
    private const val MEMORY_LIMIT = 4_000
    /** Translated lines read back from a language's cache file. */
    private const val DISK_LIMIT = 20_000
    private const val DISK_MAX_BYTES = 4L * 1024 * 1024
    private const val SEP = "\u0000"

    private class Provider(val name: String, val translate: (String, String, String?) -> String?)

    private val providers = listOf(
        Provider("google", ::translateWithGoogle),
        Provider("mymemory", ::translateWithMyMemory)
    )

    private val cooldownUntil = ConcurrentHashMap<String, Long>()
    private val memory = ConcurrentHashMap<String, String>()
    private val diskMaps = ConcurrentHashMap<String, MutableMap<String, String>>()

    @Volatile private var cacheDir: File? = null

    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "yt-caption-cache").apply { isDaemon = true }
    }

    private val http by lazy {
        // Redirects are not followed: a captcha block is a redirect, and following it would read as
        // a successful HTML page rather than the refusal it is.
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** Tells the translator where to keep its cache. Null keeps translations in memory only. */
    fun attach(dir: File?) {
        cacheDir = dir
    }

    /** Translates [text] into [language]; null when no translator could be reached. */
    fun translate(text: String, language: String, source: String?): String? {
        if (text.isBlank()) return text
        val target = targetCode(language)
        val sourceCode = source?.let(::sourceCode)
        val key = target + SEP + text

        memory[key]?.let { return it }
        diskMap(target)[text]?.let {
            memory[key] = it
            return it
        }

        for (provider in providers) {
            if (isCooling(provider.name)) continue
            val result = runCatching { provider.translate(text, target, sourceCode) }
                .onFailure { Log.w(TAG, "${provider.name} translate failed", it) }
                .getOrNull()
            if (!result.isNullOrBlank()) {
                if (provider.name != "google") Log.d(TAG, "translated the captions with ${provider.name}")
                remember(target, text, result)
                return result
            }
        }
        val cooling = providers.filter { isCooling(it.name) }.joinToString(",") { it.name }
        Log.w(TAG, "no translator could handle the captions right now (source=${source ?: "unknown"}, cooling=[$cooling])")
        return null
    }

    // ---- translators ----

    private fun translateWithGoogle(text: String, target: String, source: String?): String? {
        val request = Request.Builder()
            .url("https://translate.googleapis.com/translate_a/single?client=gtx&sl=${source ?: "auto"}&tl=$target&dt=t")
            .header("User-Agent", USER_AGENT)
            .post(FormBody.Builder().add("q", text).build())
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // 302 is the captcha block, 403 and 429 are the same refusal phrased differently.
                if (response.code == 302 || response.code == 403 || response.code == 429) {
                    cool("google")
                    Log.w(TAG, "google translate refused this network (HTTP ${response.code}); using another translator")
                } else {
                    Log.w(TAG, "google translate -> HTTP ${response.code}")
                }
                return null
            }
            val parts = JSONArray(response.body.string()).getJSONArray(0)
            val translated = buildString {
                for (i in 0 until parts.length()) append(parts.getJSONArray(i).optString(0))
            }
            return translated.takeIf { it.isNotBlank() }
        }
    }

    private fun translateWithMyMemory(text: String, target: String, source: String?): String? {
        // It cannot guess the source language, and the free endpoint only takes short queries.
        if (source == null || text.length > MYMEMORY_MAX_CHARS) return null
        val query = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
        val request = Request.Builder()
            .url("https://api.mymemory.translated.net/get?q=$query&langpair=$source%7C$target")
            .header("User-Agent", USER_AGENT)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "mymemory -> HTTP ${response.code}")
                return null
            }
            val json = JSONObject(response.body.string())
            if (json.optBoolean("quotaFinished")) {
                cool("mymemory")
                Log.w(TAG, "mymemory is out of quota for today; using another translator")
                return null
            }
            val translated = json.optJSONObject("responseData")?.optString("translatedText").orEmpty()
            if (translated.isBlank() || translated.contains("MYMEMORY WARNING", ignoreCase = true)) {
                cool("mymemory")
                return null
            }
            return translated
        }
    }

    private fun isCooling(name: String) = (cooldownUntil[name] ?: 0L) > System.currentTimeMillis()

    private fun cool(name: String) {
        cooldownUntil[name] = System.currentTimeMillis() + COOLDOWN_MS
    }

    // ---- remembered lines ----

    /** Remembers one line that a caller translated in a batch. */
    fun rememberLine(language: String, text: String, translated: String) {
        if (text.isBlank() || translated.isBlank()) return
        remember(targetCode(language), text, translated)
    }

    private fun remember(language: String, text: String, translated: String) {
        memory[language + SEP + text] = translated
        if (memory.size > MEMORY_LIMIT) memory.clear()
        diskMap(language)[text] = translated

        val file = fileFor(language) ?: return
        writer.execute {
            runCatching {
                if (file.length() > DISK_MAX_BYTES) {
                    file.delete()
                    diskMaps[language]?.clear()
                }
                file.appendText(escape(text) + "\t" + escape(translated) + "\n")
            }.onFailure { Log.w(TAG, "couldn't cache a translated caption line", it) }
        }
    }

    private fun diskMap(language: String): MutableMap<String, String> =
        diskMaps[language] ?: synchronized(diskMaps) {
            diskMaps.getOrPut(language) { readDisk(language) }
        }

    private fun readDisk(language: String): MutableMap<String, String> {
        val lines = HashMap<String, String>()
        val file = fileFor(language) ?: return lines
        if (!file.exists()) return lines
        runCatching {
            file.useLines { all ->
                all.take(DISK_LIMIT).forEach { line ->
                    val tab = line.indexOf('\t')
                    if (tab <= 0) return@forEach
                    val text = unescape(line.substring(0, tab))
                    if (text.isNotEmpty()) lines[text] = unescape(line.substring(tab + 1))
                }
            }
        }.onFailure { Log.w(TAG, "couldn't read the caption translation cache", it) }
        return lines
    }

    private fun fileFor(language: String): File? {
        val dir = cacheDir ?: return null
        val folder = File(dir, "yt-captions").apply { mkdirs() }
        return File(folder, language.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".tsv")
    }

    // ---- codes ----

    internal fun targetCode(language: String): String = when (language) {
        "zh-Hans" -> "zh-CN"
        "zh-Hant" -> "zh-TW"
        "iw" -> "he"
        else -> language
    }

    internal fun sourceCode(language: String): String =
        if (language == "iw") "he" else language.substringBefore('-')

    // ---- cache file escaping ----

    internal fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\r", "")
        .replace("\n", "\\n")

    internal fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    't' -> out.append('\t')
                    'n' -> out.append('\n')
                    '\\' -> out.append('\\')
                    else -> out.append(value[i + 1])
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
