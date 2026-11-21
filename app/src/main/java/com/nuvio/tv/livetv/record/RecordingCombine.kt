package com.nuvio.tv.livetv.record

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.livetv.player.HlsPieces
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class CombineResult(val destination: String, val bytes: Long)

/**
 * Joins a finished recording's HLS segments into one MPEG-TS file.
 *
 * The output is byte-for-byte the recorded media (no re-encode), so it is exactly as large as
 * the segments. SMB recordings are read from the share; with [toShare] the result is written to
 * an `Exports` folder on the share, otherwise next to the recording on this device.
 */
internal object RecordingCombine {

    private const val EXPORTS = "Exports"
    private val invalidName = Regex("""[\\/:*?"<>|\p{Cntrl}]""")

    suspend fun combine(
        context: Context,
        rec: LiveTvRecording,
        toShare: Boolean,
        onProgress: (doneBytes: Long, totalBytes: Long) -> Unit
    ): Result<CombineResult> {
        if (rec.isRecording) {
            return Result.failure(IllegalStateException(context.getString(R.string.iptv_rec_combine_stop_first)))
        }
        val config = SmbSettings.load(context)
        if (toShare && config == null) {
            return Result.failure(IllegalStateException(context.getString(R.string.iptv_rec_combine_no_config)))
        }
        if (!toShare && rec.smb) {
            return Result.failure(IllegalStateException(context.getString(R.string.iptv_rec_combine_smb_only)))
        }

        val playlist = if (rec.smb) {
            SmbClient.readText(config!!, "${rec.shareDir}/index.m3u8")
        } else {
            runCatching { File(rec.dir, "index.m3u8").readText() }.getOrNull()
        } ?: return Result.failure(IllegalStateException(context.getString(R.string.iptv_rec_combine_no_pieces)))

        if (HlsPieces.isMaster(playlist)) {
            // Separate picture and sound need a remux, not a concatenation.
            return Result.failure(IllegalStateException(context.getString(R.string.iptv_rec_combine_separate)))
        }
        val media = HlsPieces.parseMedia(playlist)
        if (media.encrypted) {
            return Result.failure(IllegalStateException(context.getString(R.string.iptv_rec_combine_not_ts)))
        }
        if (media.mapUri != null || media.pieces.isEmpty()) {
            return Result.failure(IllegalStateException(context.getString(R.string.iptv_rec_combine_no_pieces)))
        }

        val lengths = media.pieces.map { piece ->
            if (rec.smb) {
                SmbClient.length(config!!, "${rec.shareDir}/${piece.uri}").coerceAtLeast(0L)
            } else {
                File(rec.dir, piece.uri).length()
            }
        }
        val total = lengths.sum()
        if (total <= 0L) {
            return Result.failure(IllegalStateException(context.getString(R.string.iptv_rec_combine_no_pieces)))
        }

        val name = exportName(rec)
        var output: java.io.OutputStream? = null
        var localFile: File? = null
        var remotePath: String? = null
        try {
            if (toShare) {
                remotePath = uniqueRemote(config!!, "$EXPORTS/$name")
                output = SmbClient.openOutputStream(config, remotePath)
                    ?: error(context.getString(R.string.iptv_rec_combine_failed, "couldn't create the file"))
            } else {
                val root = File(rec.dir).parentFile
                    ?: return Result.failure(IllegalStateException(context.getString(R.string.iptv_rec_combine_failed, "no recording folder")))
                val dir = File(root, EXPORTS).apply { mkdirs() }
                localFile = uniqueLocal(dir, name)
                output = FileOutputStream(localFile)
            }

            var done = 0L
            var lastReport = 0L
            onProgress(0L, total)
            for (piece in media.pieces) {
                currentCoroutineContext().ensureActive()
                val input = if (rec.smb) {
                    SmbClient.openInputStream(config!!, "${rec.shareDir}/${piece.uri}")
                        ?: error(context.getString(R.string.iptv_rec_combine_no_pieces))
                } else {
                    File(rec.dir, piece.uri).inputStream()
                }
                input.use { source ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        output!!.write(buffer, 0, read)
                        done += read
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 500L) {
                            lastReport = now
                            onProgress(done, total)
                        }
                    }
                }
            }
            output.flush()
            output.close()
            output = null
            onProgress(done, total)
            val destination = if (toShare) {
                config!!.label + "/" + remotePath
            } else {
                localFile!!.absolutePath
            }
            return Result.success(CombineResult(destination, done))
        } catch (error: Exception) {
            runCatching { output?.close() }
            if (toShare && remotePath != null) runCatching { SmbClient.deleteRecursively(config!!, remotePath) }
            if (!toShare && localFile != null) runCatching { localFile.delete() }
            if (error is kotlinx.coroutines.CancellationException) throw error
            return Result.failure(error)
        }
    }

    private fun exportName(rec: LiveTvRecording): String {
        val title = rec.title.trim()
            .replace(invalidName, "_")
            .trim('_', ' ', '.')
            .take(80)
            .ifBlank { "Recording" }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(Date(rec.startedAtMs))
        return "$title $stamp.ts"
    }

    private fun uniqueLocal(dir: File, name: String): File {
        var file = File(dir, name)
        var n = 2
        while (file.exists()) {
            file = File(dir, "${name.removeSuffix(".ts")} ($n).ts")
            n++
        }
        return file
    }

    private fun uniqueRemote(config: SmbConfig, path: String): String {
        var candidate = path
        var n = 2
        while (SmbClient.exists(config, candidate)) {
            candidate = "${path.removeSuffix(".ts")} ($n).ts"
            n++
        }
        return candidate
    }
}
