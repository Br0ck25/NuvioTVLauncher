package com.nuvio.tv.youtube

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Serves a video's DASH manifest on the loopback address.
 *
 * YouTube's separate video and audio streams reach the player as a generated DASH manifest. It is
 * too big to carry in a navigation route and the player wants a URL, so the manifest is held here
 * and handed out as `http://127.0.0.1:<port>/yt/<videoId>.mpd`. Only the device itself can reach it.
 *
 * It also serves the auto-translated captions. YouTube refuses to hand those out without a token only its own
 * apps can make (HTTP 429), so when a translated language is picked, the video's own captions are fetched and
 * translated here instead. Which translator does the work, and what happens when none can be reached, is
 * [YouTubeCaptionTranslation]'s business; when translation fails the original captions are served rather than
 * nothing, so the viewer still has readable subtitles.
 */
object YouTubeMpdServer {
    private const val TAG = "YouTubeCaptions"
    private const val USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    /** How much text goes to the translator in one request. */
    private const val CHUNK_CHARS = 600
    /** How much text a fallback translator can take in one request; it keeps the line breaks. */
    private const val FALLBACK_CHUNK_CHARS = 240
    /** How many translation requests may run at once. */
    private const val MAX_PARALLEL = 1

    private val manifests = ConcurrentHashMap<String, ByteArray>()
    /** YouTube's visitor cookie, used for the translated caption URL (the same idea as Morphe's caption cookies). */
    private val captionCookies = ConcurrentHashMap<String, String>()
    private val pool = Executors.newCachedThreadPool()

    @Volatile private var server: ServerSocket? = null

    /** Stores [mpd] and returns the URL to play it from. */
    @Synchronized
    fun publish(
        videoId: String,
        mpd: ByteArray,
        visitorCookie: String? = null,
        keepCaptionLanguages: Set<String> = YouTubeCaptionTracks.keepLanguages(null, null)
    ): String {
        // Kept at least as long as the view model keeps its cached lookups.
        if (manifests.size > 64) {
            manifests.clear()
            captionCookies.clear()
        }
        if (visitorCookie.isNullOrBlank()) captionCookies.remove(videoId) else captionCookies[videoId] = visitorCookie
        manifests[videoId] = rewriteTranslatedCaptions(
            videoId,
            YouTubeAudioTracks.tidy(mpd),
            keepCaptionLanguages
        )
        return "http://127.0.0.1:${ensureStarted().localPort}/yt/$videoId.mpd"
    }

    /** Where translated caption lines are remembered between videos; null keeps them in memory only. */
    fun attach(cacheDir: File?) = YouTubeCaptionTranslation.attach(cacheDir)

    /** Whether this video has a translated caption track in [language]. */
    fun hasTranslatedTrack(videoId: String, language: String): Boolean = trackIndex(videoId, language) >= 0

    /** The address of one 60-second piece of the translated captions, or null when there are none. */
    fun segmentUrl(videoId: String, language: String, number: Long): String? {
        val index = trackIndex(videoId, language).takeIf { it >= 0 } ?: return null
        val port = server?.localPort ?: return null
        return "http://127.0.0.1:$port/yt/cap/$videoId/$index/$number.vtt"
    }

    private fun trackIndex(videoId: String, language: String): Int =
        captionUrls[videoId]?.indexOfFirst { address ->
            Regex("[?&]tlang=([^&]+)").find(address)?.groupValues?.get(1) == language
        } ?: -1

    // ---- translated captions ----

    /** Each video's translated caption addresses, in the order they were swapped out of the manifest. */
    private val captionUrls = ConcurrentHashMap<String, List<String>>()
    /** The language the video's own captions are in, read from the manifest; empty when unknown. */
    private val captionLanguages = ConcurrentHashMap<String, String>()
    private val translatedCache = ConcurrentHashMap<String, ByteArray>()
    private val sourceCache = ConcurrentHashMap<String, String>()
    private val segmentLock = Any()
    private val http by lazy { OkHttpClient.Builder().followRedirects(true).build() }

    private fun rewriteTranslatedCaptions(
        videoId: String,
        mpd: ByteArray,
        keepCaptionLanguages: Set<String>
    ): ByteArray {
        val port = ensureStarted().localPort
        val urls = ArrayList<String>()
        val fullXml = String(mpd, Charsets.UTF_8)
        captionLanguages[videoId] = subtitleLanguage(fullXml).orEmpty()
        // Drop the auto-translated languages nobody on this device is likely to pick before the
        // player builds a text track for each one. Real/manual tracks are always kept.
        val xml = YouTubeCaptionTracks.limitTranslated(fullXml, keepCaptionLanguages)
        val rewritten = Regex("<BaseURL>([^<]*timedtext[^<]*)</BaseURL>").replace(xml) { m ->
            val url = m.groupValues[1].replace("&amp;", "&")
            // Translated captions are fetched and translated here; automatic ones (their lines roll up, the old
            // one staying above the new) are tidied here. Written-by-people captions go straight to YouTube.
            if ("tlang=" !in url && !isAutomatic(url)) m.value
            else {
                urls += url
                val index = urls.size - 1
                if ("tlang=" in url && Regex("[?&]fmt=vtt(&|$)").containsMatchIn(url)) {
                    "<SegmentTemplate timescale=\"1\" duration=\"${YouTubeCaptionSegments.DURATION_SECONDS}\" startNumber=\"0\" media=\"http://127.0.0.1:$port/yt/cap/$videoId/$index/\$Number\$.vtt\"/>"
                } else {
                    "<BaseURL>http://127.0.0.1:$port/yt/cap/$videoId/$index</BaseURL>"
                }
            }
        }
        captionUrls[videoId] = urls
        if (translatedCache.size > 12) translatedCache.clear()
        return rewritten.toByteArray(Charsets.UTF_8)
    }

    /**
     * YouTube's own translated caption file for [language], when it will serve it to us.
     * The visitor cookie is what makes the translated timedtext request look like a viewer
     * rather than an anonymous automated query.
     */
    fun translatedCaptionFile(videoId: String, language: String): String? {
        val address = captionUrls[videoId]?.firstOrNull { address ->
            Regex("[?&]tlang=([^&]+)").find(address)?.groupValues?.get(1) == language
        } ?: return null
        return get(videoId, address)
    }

    /** YouTube's speech-recognition captions ("kind=asr"). */
    private fun isAutomatic(address: String) = Regex("[?&]kind=asr(&|$)").containsMatchIn(address)

    /** The language the video's own captions are in, when the address says so. */
    private fun languageOf(address: String): String? =
        Regex("[?&]lang=([^&]+)").find(address)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    /**
     * The language of the subtitle tracks in the manifest. A translated track's address does not always
     * say what it is translating from, and a translator that needs the source language cannot guess it.
     */
    private fun subtitleLanguage(mpd: String): String? =
        mpd.split("<AdaptationSet").firstOrNull { "timedtext" in it }
            ?.let { Regex("lang=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }

    /** Where the captions being translated come from: the address first, the manifest second. */
    private fun sourceLanguage(videoId: String, original: String): String? =
        languageOf(original) ?: captionLanguages[videoId]?.takeIf { it.isNotBlank() }

    private fun sourceCaptions(videoId: String, address: String): String? {
        sourceCache[address]?.let { return it }
        val source = get(videoId, address) ?: return null
        val tidy = if (isAutomatic(address) || YouTubeAutoCaptions.looksAutomatic(source)) {
            YouTubeAutoCaptions.tidy(source)
        } else {
            source
        }
        if (sourceCache.size >= 8) sourceCache.clear()
        sourceCache[address] = tidy
        return tidy
    }

    private fun fetchCaptionSegment(videoId: String, index: Int, number: Long): ByteArray? = synchronized(segmentLock) {
        val address = captionUrls[videoId]?.getOrNull(index) ?: return@synchronized null
        val language = Regex("[?&]tlang=([^&]+)").find(address)?.groupValues?.get(1) ?: return@synchronized null
        val key = "$videoId/${address.hashCode()}/$number"
        translatedCache[key]?.let { return@synchronized it }
        Log.d(TAG, "loading caption segment $number for $videoId in $language")
        val original = address.replace(Regex("&tlang=[^&]+"), "")
        val source = sourceCaptions(videoId, original) ?: return@synchronized null
        val segment = YouTubeCaptionSegments.extract(source, number)
        // If no translator can be reached the original lines are served; untranslated captions beat none.
        val sourceLang = sourceLanguage(videoId, original)
        Log.d(TAG, "caption segment $number source=${sourceLang ?: "unknown"} target=$language")
        val translated = if ("-->" in segment) translateVtt(segment, language, sourceLang) else segment
        if (translated == segment) {
            Log.w(TAG, "caption segment $number for $videoId could not be translated; serving the source")
        }
        val bytes = translated.toByteArray(Charsets.UTF_8)
        if (translated == segment) {
            // Nothing could be translated. Don't remember the original lines: a later request, once a
            // translator is reachable again, should be allowed to try.
            Log.w(TAG, "caption segment $number for $videoId is still in its original language")
            return@synchronized bytes
        }
        if (translatedCache.size >= 256) translatedCache.clear()
        bytes.also {
            translatedCache[key] = it
            Log.i(TAG, "caption segment $number for $videoId translated to $language")
        }
    }

    private fun fetchTranslatedCaption(videoId: String, index: Int): Pair<ByteArray, String>? {
        val address = captionUrls[videoId]?.getOrNull(index) ?: return null
        val language = Regex("[?&]tlang=([^&]+)").find(address)?.groupValues?.get(1)
        val key = "$videoId/${language ?: "original"}/${address.hashCode()}"
        translatedCache[key]?.let { return it to typeOf(it) }
        val began = System.currentTimeMillis()
        // The video's own captions are served without any token. Fetch those, tidy them if they're automatic,
        // and translate them when a language was asked for.
        val original = address.replace(Regex("&tlang=[^&]+"), "")
        val tidy = sourceCaptions(videoId, original) ?: return null
        val sourceLang = sourceLanguage(videoId, original)
        val result = if (language == null) tidy else translateCaptions(tidy, language, sourceLang)
        val bytes = result.toByteArray(Charsets.UTF_8)
        if (language != null && result == tidy) {
            Log.w(TAG, "captions for $key are still in their original language")
            return bytes to typeOf(bytes)
        }
        translatedCache[key] = bytes
        Log.i(TAG, "captions for $key ready in ${System.currentTimeMillis() - began} ms")
        return bytes to typeOf(bytes)
    }

    private fun typeOf(body: ByteArray) = if (String(body, 0, minOf(body.size, 8), Charsets.UTF_8).startsWith("WEBVTT")) "text/vtt" else "application/ttml+xml"

    private fun get(videoId: String, url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.youtube.com/watch?v=$videoId")
            .header("Origin", "https://www.youtube.com")
            .header("Accept-Language", "en-US,en;q=0.9")
            .apply {
                captionCookies[videoId]?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) }
            }
            .build()
        http.newCall(request).execute().use {
            if (it.isSuccessful) it.body?.string() else {
                Log.w(TAG, "caption request $videoId -> HTTP ${it.code}")
                null
            }
        }
    }.onFailure { Log.w(TAG, "caption request for $videoId failed", it) }.getOrNull()

    private val cue = Regex("(<p\\b[^>]*>)(.*?)(</p>)", RegexOption.DOT_MATCHES_ALL)

    /** Translates the text of every cue in a caption file (WebVTT or TTML), keeping the timing. */
    private fun translateCaptions(file: String, language: String, source: String?): String =
        if (file.trimStart().startsWith("WEBVTT")) translateVtt(file, language, source)
        else translateTtml(file, language, source)

    private fun translateVtt(vtt: String, language: String, source: String?): String {
        val blocks = vtt.replace("\r\n", "\n").split(Regex("\n{2,}"))
        // Each cue is a block with a timing line; its text is whatever follows that line.
        val timingIndex = blocks.map { block -> block.lines().indexOfFirst { "-->" in it } }
        val texts = blocks.indices.mapNotNull { i ->
            val t = timingIndex[i]
            if (t < 0) null else plainText(blocks[i].lines().drop(t + 1).joinToString(" "))
        }
        if (texts.none { it.isNotBlank() }) return vtt
        val translated = translateAll(texts, language, source)
        var n = 0
        val rebuilt = blocks.indices.map { i ->
            val t = timingIndex[i]
            if (t < 0) blocks[i]
            else (blocks[i].lines().take(t + 1) + translated[n++]).joinToString("\n")
        }.joinToString("\n\n")
        Log.i(TAG, "translated ${texts.size} caption cues to $language")
        return rebuilt + "\n"
    }

    private fun translateTtml(ttml: String, language: String, source: String?): String {
        val cues = cue.findAll(ttml).toList()
        if (cues.isEmpty()) return ttml
        val translated = translateAll(cues.map { plainText(it.groupValues[2]) }, language, source)
        var n = 0
        val rebuilt = cue.replace(ttml) { m -> m.groupValues[1] + escape(translated[n++]) + m.groupValues[3] }
        Log.i(TAG, "translated ${cues.size} caption cues to $language")
        return rebuilt
    }

    /**
     * Translates every text. YouTube's automatic captions repeat each line as the words roll in, so only the
     * different lines are sent, and the requests go out side by side instead of one after another.
     */
    private fun translateAll(texts: List<String>, language: String, source: String?): List<String> {
        val unique = texts.filter { it.isNotBlank() }.distinct()
        if (unique.isEmpty()) return texts
        val chunks = ArrayList<List<String>>()
        var start = 0
        while (start < unique.size) {
            var end = start
            var chars = 0
            while (end < unique.size && (chars == 0 || chars + unique[end].length < CHUNK_CHARS)) {
                chars += unique[end].length + 1
                end++
            }
            chunks += unique.subList(start, end)
            start = end
        }
        val began = System.currentTimeMillis()
        val workers = Executors.newFixedThreadPool(minOf(MAX_PARALLEL, maxOf(chunks.size, 1)))
        val results = try {
            chunks.map { chunk -> workers.submit<List<String>?> { translateChunk(chunk, language, source) } }
                .map { runCatching { it.get() }.getOrNull() }
        } finally {
            workers.shutdownNow()
        }
        val byText = HashMap<String, String>()
        results.forEachIndexed { i, translated ->
            if (translated == null) return@forEachIndexed
            chunks[i].forEachIndexed { j, text -> translated.getOrNull(j)?.let { byText[text] = it } }
        }
        Log.i(TAG, "translated ${byText.size} of ${unique.size} different lines (of ${texts.size}) in ${chunks.size} request(s), ${System.currentTimeMillis() - began} ms")
        if (byText.isEmpty()) Log.w(TAG, "no translator could translate to $language right now; showing the original lines")
        // A chunk no translator could handle keeps its original lines rather than losing them.
        return texts.map { if (it.isBlank()) it else byText[it] ?: it }
    }

    /** Translates [lines] together; falls back to smaller groups, then one at a time. */
    private fun translateChunk(lines: List<String>, language: String, source: String?): List<String>? {
        val joined = translate(lines.joinToString("\n"), language, source)
        if (joined != null) {
            val split = joined.split("\n")
            if (split.size == lines.size) return split
            Log.w(TAG, "lines didn't line up (${split.size} vs ${lines.size}); translating in smaller groups")
        } else {
            // Too long for the translator, or it refused the whole block.
            Log.d(TAG, "translating ${lines.size} caption lines in smaller groups")
        }
        val result = ArrayList<String>(lines.size)
        for (batch in batches(lines, FALLBACK_CHUNK_CHARS)) {
            val translated = translate(batch.joinToString("\n"), language, source)?.split("\n")
            if (translated != null && translated.size == batch.size) {
                // Kept one line at a time too, so a later request for the same line costs nothing.
                batch.forEachIndexed { i, text ->
                    YouTubeCaptionTranslation.rememberLine(language, text, translated[i])
                    result += translated[i]
                }
            } else {
                batch.forEach { text ->
                    result += if (text.isBlank()) text else translate(text, language, source) ?: return null
                }
            }
        }
        return result
    }

    /** Splits [lines] into groups whose joined text fits within [limit] characters. */
    private fun batches(lines: List<String>, limit: Int): List<List<String>> {
        val groups = ArrayList<List<String>>()
        var start = 0
        while (start < lines.size) {
            var end = start
            var chars = 0
            while (end < lines.size && (end == start || chars + lines[end].length + 1 <= limit)) {
                chars += lines[end].length + 1
                end++
            }
            groups += lines.subList(start, end)
            start = end
        }
        return groups
    }

    private fun translate(text: String, language: String, source: String?): String? =
        YouTubeCaptionTranslation.translate(text, language, source)

    private fun plainText(inner: String): String =
        inner.replace(Regex("<br\\s*/?>"), " ").replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
            .replace(Regex("\\s+"), " ").trim()

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // ---- the server ----

    private fun ensureStarted(): ServerSocket {
        server?.takeIf { !it.isClosed }?.let { return it }
        val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        server = socket
        Thread({
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                pool.execute { runCatching { handle(client) } }
            }
        }, "yt-mpd-server").apply { isDaemon = true }.start()
        return socket
    }

    private fun reply(out: java.io.OutputStream, status: String, type: String?, body: ByteArray?) {
        val head = StringBuilder("HTTP/1.1 $status\r\n")
        if (type != null) head.append("Content-Type: $type\r\n")
        head.append("Content-Length: ${body?.size ?: 0}\r\nConnection: close\r\n\r\n")
        out.write(head.toString().toByteArray())
        if (body != null) out.write(body)
        out.flush()
    }

    private fun handle(client: Socket) = client.use { c ->
        val request = c.getInputStream().bufferedReader().readLine().orEmpty()
        val out = c.getOutputStream()
        val segment = Regex("""GET /yt/cap/([^/ ]+)/(\d+)/(\d+)\.vtt""").find(request)
        if (segment != null) {
            val index = segment.groupValues[2].toIntOrNull()
            val number = segment.groupValues[3].toLongOrNull()
            val result = if (index != null && number != null) fetchCaptionSegment(segment.groupValues[1], index, number) else null
            if (result == null) reply(out, "404 Not Found", null, null) else reply(out, "200 OK", "text/vtt", result)
            return@use
        }
        val caption = Regex("""GET /yt/cap/([^/ ]+)/(\d+)""").find(request)
        if (caption != null) {
            val result = fetchTranslatedCaption(caption.groupValues[1], caption.groupValues[2].toInt())
            if (result == null) reply(out, "404 Not Found", null, null) else reply(out, "200 OK", result.second, result.first)
            return@use
        }
        val id = Regex("""GET /yt/([^./ ]+)\.mpd""").find(request)?.groupValues?.get(1)
        val body = id?.let { manifests[it] }
        if (body == null) reply(out, "404 Not Found", null, null) else reply(out, "200 OK", "application/dash+xml", body)
    }
}
