package com.nuvio.tv.ui.screens.player.seekpreview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemStoryboard
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "StoryboardPreview"
private const val MAX_DISK_BYTES = 24L * 1024 * 1024
private const val MEMORY_SHEETS = 8
private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

/** Storyboard previews for OneTube: no second player, no video decoder, just a sprite-sheet tile. */
internal fun openYouTubeStoryboardFrameSource(
    context: Context,
    storyboard: MediaItemStoryboard,
    videoId: String
): SeekPreviewFrameSource = YouTubeStoryboardFrameSource(context.applicationContext, storyboard, videoId)

/**
 * Sprite sheets are written under cacheDir and deleted when the preview controller is disposed.
 * The 24 MB cap is only a safety ceiling for very long videos.
 */
internal object YouTubeStoryboardCache {
    private const val DIR = "youtube-storyboards"

    fun cleanup(context: Context) {
        val directory = File(context.cacheDir, DIR)
        val existed = directory.exists()
        val bytes = if (existed) {
            runCatching { directory.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
        } else {
            0L
        }
        runCatching { directory.deleteRecursively() }
        Log.i(TAG, "cache cleanup existed=$existed bytes=$bytes")
    }

    fun directory(context: Context, videoId: String): File =
        File(File(context.cacheDir, DIR), videoId)
}

private class YouTubeStoryboardFrameSource(
    context: Context,
    private val storyboard: MediaItemStoryboard,
    videoId: String,
) : SeekPreviewFrameSource {
    private val directory = YouTubeStoryboardCache.directory(context, videoId)
    private val sheets = LruCache<String, ByteArray>(MEMORY_SHEETS)
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    @Volatile
    private var closed = false

    override fun frameAt(positionMs: Long, maxWidthPx: Int): ImageBitmap? {
        if (closed) return null
        val size = runCatching { storyboard.groupSize }.getOrNull() ?: return null
        val width = size.width
        val height = size.height
        val columns = size.colCount.coerceAtLeast(1)
        val rows = size.rowCount.coerceAtLeast(1)
        if (width <= 0 || height <= 0) return null

        val groupDurationMs = storyboard.groupDurationMS.coerceAtLeast(1)
        val tileDurationMs = size.durationEachMS.coerceAtLeast(1)
        val safePosition = positionMs.coerceAtLeast(0L)
        val group = (safePosition / groupDurationMs).toInt()
        val groupStart = group.toLong() * groupDurationMs
        val tile = ((safePosition - groupStart).coerceAtLeast(0L) / tileDurationMs)
            .toInt()
            .coerceIn(0, rows * columns - 1)
        val url = runCatching { storyboard.getGroupUrl(group) }.getOrNull() ?: return null
        val bytes = sheets.get(url) ?: loadSheet(group, url) ?: return null

        val x = (tile % columns) * width
        val y = (tile / columns) * height
        val decoder = runCatching {
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        }.getOrNull() ?: return null
        return try {
            decoder.decodeRegion(
                Rect(x, y, x + width, y + height),
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            )?.asImageBitmap()
        } catch (error: Throwable) {
            Log.w(TAG, "could not decode tile $group/$tile", error)
            null
        } finally {
            decoder.recycle()
        }
    }

    private fun loadSheet(group: Int, url: String): ByteArray? {
        val file = File(directory, "g$group.jpg")
        if (file.isFile && file.length() > 0) {
            runCatching { file.readBytes() }.getOrNull()?.let { cached ->
                sheets.put(url, cached)
                return cached
            }
        }

        val bytes = runCatching {
            http.newCall(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "sheet $group -> HTTP ${response.code}")
                    null
                } else {
                    response.body?.bytes()
                }
            }
        }.onFailure { Log.w(TAG, "sheet $group request failed", it) }.getOrNull() ?: return null

        sheets.put(url, bytes)
        runCatching {
            directory.mkdirs()
            val temp = File(directory, "g$group.tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) {
                file.writeBytes(bytes)
                temp.delete()
            }
            trimDiskCache()
        }.onFailure { Log.w(TAG, "could not cache sheet $group", it) }
        return bytes
    }

    private fun trimDiskCache() {
        val files = directory.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= MAX_DISK_BYTES) break
            total -= file.length()
            file.delete()
        }
    }

    override fun close() {
        closed = true
        sheets.evictAll()
        runCatching { directory.deleteRecursively() }
    }
}
