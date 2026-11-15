package com.nuvio.tv.updater

import android.content.Context
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.updater.model.AppUpdate
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One in-flight update download, wherever it was started (the card or the background job). */
data class UpdateDownloadProgress(
    val tag: String,
    val downloadedBytes: Long,
    val totalBytes: Long?
) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0 }
            ?.let { (downloadedBytes.toFloat() / it).coerceIn(0f, 1f) }
}

/**
 * Keeps the downloaded update APK in app storage and remembers which release it belongs to, so a download made in the
 * background can be installed later, from the banner or by the idle install job.
 */
@Singleton
class UpdateDownloadStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updatePreferences: UpdatePreferences,
    private val apkDownloader: ApkDownloader
) {
    // Files storage instead of cache, which the system may clear before the update is installed.
    private val updatesDir: File get() = File(context.filesDir, "updates")

    // The banner and the background job can ask for the same download; the second one waits and reuses the file.
    private val downloadMutex = Mutex()

    private val _progress = MutableStateFlow<UpdateDownloadProgress?>(null)
    /** Shared so the card shows the background job's progress instead of sitting at 0%. */
    val progress: StateFlow<UpdateDownloadProgress?> = _progress.asStateFlow()

    fun destinationFor(update: AppUpdate): File {
        val safeName = update.assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(updatesDir, safeName)
    }

    /** Returns the already downloaded APK for this release, or null when it is missing or incomplete. */
    suspend fun downloadedFileFor(update: AppUpdate): File? {
        val (tag, path) = updatePreferences.downloadedUpdate.first() ?: return null
        if (tag != update.tag) return null
        val file = File(path)
        val expectedSize = update.assetSizeBytes
        return file.takeIf { it.exists() && (expectedSize == null || it.length() == expectedSize) }
    }

    /** Returns the downloaded APK when it is for a version newer than the installed one. */
    suspend fun pendingInstall(): Pair<String, File>? {
        val (tag, path) = updatePreferences.downloadedUpdate.first() ?: return null
        val file = File(path)
        if (!file.exists() || !VersionUtils.isRemoteNewer(tag, BuildConfig.VERSION_NAME)) return null
        return tag to file
    }

    suspend fun download(
        update: AppUpdate,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> }
    ): Result<File> = downloadMutex.withLock {
        downloadedFileFor(update)?.let { return@withLock Result.success(it) }
        val destination = destinationFor(update)
        _progress.value = UpdateDownloadProgress(update.tag, 0L, update.assetSizeBytes)
        try {
            apkDownloader.download(update.assetUrl, destination, update.assetSha256, update.assetSizeBytes) { downloaded, total ->
                _progress.value = UpdateDownloadProgress(
                    tag = update.tag,
                    downloadedBytes = downloaded,
                    totalBytes = total ?: update.assetSizeBytes
                )
                onProgress(downloaded, total)
            }.onSuccess { file ->
                updatesDir.listFiles()?.filter { it != file }?.forEach { it.delete() }
                updatePreferences.setDownloadedUpdate(update.tag, file.absolutePath)
            }
        } finally {
            _progress.value = null
        }
    }

    /** Drops the downloaded APK once it is installed, outdated, or no longer wanted. */
    suspend fun clear() {
        updatesDir.deleteRecursively()
        updatePreferences.setDownloadedUpdate(null, null)
    }

    suspend fun clearIfInstalled() {
        val (tag, _) = updatePreferences.downloadedUpdate.first() ?: return
        if (!VersionUtils.isRemoteNewer(tag, BuildConfig.VERSION_NAME)) clear()
    }
}
