package com.nuvio.tv.livetv.record

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import java.io.File
import java.net.URLEncoder
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/** A network recording destination. Credentials never leave the encrypted preferences. */
data class SmbConfig(
    val host: String,
    val port: Int = 445,
    val share: String,
    val folder: String = "",
    val domain: String = "",
    val username: String = "",
    val password: String = "",
) {
    val label: String
        get() = buildString {
            append(host)
            if (port != 445) append(':').append(port)
            append('/').append(share)
            folder.trim('/').takeIf { it.isNotEmpty() }?.let { append('/').append(it) }
        }

    val baseUrl: String
        get() {
            val cleanHost = host.trim().removePrefix("smb://").trimEnd('/')
            val hostPart = if (port in 1..65535 && port != 445) "$cleanHost:$port" else cleanHost
            return buildString {
                append("smb://").append(hostPart).append('/').append(encode(share.trim('/'))).append('/')
                val parts = folder.trim('/').split('/').filter { it.isNotBlank() }
                if (parts.isNotEmpty()) append(parts.joinToString("/") { encode(it) }).append('/')
            }
        }

    internal fun key(): String =
        "$host|$port|$share|$folder|$domain|$username|${password.hashCode()}"

    companion object {
        fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}

/** Small encrypted store for the one SMB recording destination. */
internal object SmbSettings {
    private const val TAG = "SmbSettings"
    private const val ENCRYPTED = "livetv_smb_recording_secure"
    private const val PLAIN = "livetv_smb_recording"
    private const val HOST = "host"
    private const val PORT = "port"
    private const val SHARE = "share"
    private const val FOLDER = "folder"
    private const val DOMAIN = "domain"
    private const val USER = "user"
    private const val PASSWORD = "password"

    @Volatile private var opened: SharedPreferences? = null

    @Synchronized
    private fun prefs(context: Context): SharedPreferences {
        opened?.let { return it }
        val app = context.applicationContext
        val store = runCatching {
            val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                app,
                ENCRYPTED,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse { failure ->
            Log.w(TAG, "Keystore-backed SMB preferences unavailable; using a plain file", failure)
            app.getSharedPreferences(PLAIN, Context.MODE_PRIVATE)
        }
        opened = store
        return store
    }

    fun load(context: Context): SmbConfig? = runCatching {
        val p = prefs(context)
        val host = p.getString(HOST, "").orEmpty().trim()
        val share = p.getString(SHARE, "").orEmpty().trim()
        if (host.isBlank() || share.isBlank()) return null
        SmbConfig(
            host = host,
            port = p.getInt(PORT, 445).coerceIn(1, 65535),
            share = share,
            folder = p.getString(FOLDER, "").orEmpty(),
            domain = p.getString(DOMAIN, "").orEmpty(),
            username = p.getString(USER, "").orEmpty(),
            password = p.getString(PASSWORD, "").orEmpty(),
        )
    }.getOrNull()

    fun save(context: Context, config: SmbConfig) {
        prefs(context).edit()
            .putString(HOST, config.host.trim())
            .putInt(PORT, config.port.coerceIn(1, 65535))
            .putString(SHARE, config.share.trim())
            .putString(FOLDER, config.folder.trim('/'))
            .putString(DOMAIN, config.domain.trim())
            .putString(USER, config.username)
            .putString(PASSWORD, config.password)
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}

/** One cached SMB context per destination, so a recording doesn't reconnect for every file. */
internal object SmbClient {    private val contexts = ConcurrentHashMap<String, CIFSContext>()

    private fun context(config: SmbConfig): CIFSContext =
        contexts.getOrPut(config.key()) {
            val props = Properties().apply {
                setProperty("jcifs.smb.client.enableSMB2", "true")
                setProperty("jcifs.smb.client.disableSMB1", "true")
                // Skip the SMB1 negotiate probe and the NetBIOS domain lookup: SMB2/3 servers
                // (NAS boxes, routers) answer the plain SMB2 negotiate, and the probe only
                // adds a timeout on servers that don't speak SMB1.
                setProperty("jcifs.smb.client.useSMB2Negotiation", "true")
                setProperty("jcifs.smb.client.dfs.disabled", "true")
                setProperty("jcifs.smb.client.responseTimeout", "30000")
                setProperty("jcifs.smb.client.soTimeout", "35000")
            }
            BaseContext(PropertyConfiguration(props)).withCredentials(
                NtlmPasswordAuthenticator(
                    config.domain.ifBlank { null },
                    config.username.ifBlank { null },
                    config.password,
                )
            )
        }

    fun file(config: SmbConfig, relative: String): SmbFile {
        val clean = relative.trim('/')
        val base = SmbFile(config.baseUrl, context(config))
        return if (clean.isEmpty()) base else SmbFile(base, clean)
    }

    fun recordingDir(config: SmbConfig, id: String): SmbFile =
        SmbFile(config.baseUrl + SmbConfig.encode(id) + "/", context(config))

    fun test(config: SmbConfig): Result<String> = runCatching {
        val dir = file(config, "")
        dir.mkdirs()
        val probe = file(config, ".nuvio-write-test-${System.nanoTime()}.tmp")
        try {
            probe.openOutputStream().use { it.write("Nuvio SMB test".toByteArray()) }
        } finally {
            runCatching { probe.delete() }
        }
        val free = runCatching { dir.getDiskFreeSpace() }.getOrDefault(0L)
        if (free > 0) "Connected. ${RecordingStorage.sizeLabel(free)} free." else "Connected."
    }.onFailure { Log.w("SmbClient", "connection test failed", it) }

    fun upload(config: SmbConfig, local: File, relative: String) {
        val target = file(config, relative)
        target.parent?.takeIf { it.isNotBlank() }?.let { parent ->
            runCatching { SmbFile(parent, context(config)).mkdirs() }
        }
        local.inputStream().use { input ->
            target.openOutputStream().use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    /** Copies a whole recording folder down from the share, keeping its layout. */
    fun downloadTree(config: SmbConfig, relative: String, targetDir: File): Boolean = runCatching {
        val root = file(config, relative)
        if (!root.exists()) return@runCatching false
        targetDir.mkdirs()
        fun copy(resource: SmbFile, out: File) {
            if (resource.isDirectory) {
                out.mkdirs()
                resource.listFiles().orEmpty().forEach { child ->
                    copy(child, File(out, child.name.trimEnd('/')))
                }
            } else {
                out.parentFile?.mkdirs()
                resource.openInputStream().use { input ->
                    out.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
                }
            }
        }
        copy(root, targetDir)
        true
    }.getOrDefault(false)

    fun sizeOfTree(config: SmbConfig, relative: String): Long = runCatching {
        fun size(resource: SmbFile): Long =
            if (resource.isDirectory) resource.listFiles().orEmpty().sumOf(::size) else resource.length()
        size(file(config, relative))
    }.getOrDefault(0L)

    fun deleteRecursively(config: SmbConfig, relative: String) {
        val root = runCatching { file(config, relative) }.getOrNull() ?: return
        if (!runCatching { root.exists() }.getOrDefault(false)) return
        fun delete(resource: SmbFile) {
            if (runCatching { resource.isDirectory }.getOrDefault(false)) {
                runCatching { resource.listFiles() }.getOrDefault(emptyArray()).forEach(::delete)
            }
            runCatching { resource.delete() }
        }
        delete(root)
    }

    fun freeBytes(config: SmbConfig): Long =
        runCatching { file(config, "").getDiskFreeSpace() }.getOrDefault(0L)

    fun length(config: SmbConfig, relative: String): Long =
        runCatching { file(config, relative).length() }.getOrDefault(-1L)

    fun readText(config: SmbConfig, relative: String): String? = runCatching {
        file(config, relative).openInputStream().bufferedReader().use { it.readText() }
    }.getOrNull()

    fun randomAccess(config: SmbConfig, relative: String): SmbRandomAccessFile? =
        runCatching { file(config, relative).openRandomAccess("r") }.getOrNull()

    fun exists(config: SmbConfig, relative: String): Boolean =
        runCatching { file(config, relative).exists() }.getOrDefault(false)

    /** Directories directly under [relative] (the recording folders on the share). */
    fun listDirs(config: SmbConfig, relative: String): List<SmbFile> =
        runCatching {
            file(config, relative).listFiles()?.filter { it.isDirectory }.orEmpty()
        }.getOrDefault(emptyList())

    fun openInputStream(config: SmbConfig, relative: String): java.io.InputStream? =
        runCatching { file(config, relative).openInputStream() }.getOrNull()

    fun openOutputStream(config: SmbConfig, relative: String): java.io.OutputStream? =
        runCatching {
            val target = file(config, relative)
            target.parent?.takeIf { it.isNotBlank() }?.let { parent ->
                SmbFile(parent, context(config)).mkdirs()
            }
            target.openOutputStream()
        }.getOrNull()
}

/**
 * Mirrors a recording folder to SMB while it is being made. The local folder remains the
 * authoritative copy until [finish] uploads everything; then media pieces are removed locally
 * and served from the share by [LiveTvRecordingServer].
 */
internal class SmbMirror(
    private val config: SmbConfig,
    private val localDir: File,
    private val remoteDir: String,
    private val onUploaded: (totalBytes: Long) -> Unit = {},
    private val onProblem: (String) -> Unit = {},
) {
    @Volatile private var running = false
    @Volatile private var thread: Thread? = null
    private val syncLock = Any()
    private val uploaded = HashMap<String, Pair<Long, Long>>()
    @Volatile var uploadedBytes: Long = 0L
        private set
    @Volatile var lastProblem: String? = null
        private set

    fun start() {
        if (running) return
        running = true
        thread = kotlin.concurrent.thread(name = "smb-mirror", isDaemon = true) {
            while (running) {
                sync(final = false)
                try { Thread.sleep(2_000L) } catch (_: InterruptedException) { break }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { thread?.join(5_000L) }
        thread = null
    }

    /** Uploads the last files, then removes media from the local staging folder. */
    fun finish(): Boolean {
        stop()
        return sync(final = true)
    }

    private fun sync(final: Boolean): Boolean = synchronized(syncLock) {
        if (!localDir.isDirectory) return@synchronized false
        val files = localDir.walkTopDown().filter { it.isFile }
            .filter { !it.name.endsWith(".tmp", true) && !it.name.endsWith(".part", true) }
            .sortedBy { if (it.name == "info.json") 1 else 0 }
            .toList()
        if (files.isEmpty()) return@synchronized true
        var ok = true
        for (file in files) {
            val relative = file.relativeTo(localDir).invariantSeparatorsPath
            if (relative.isBlank() || relative.contains("..")) continue
            val stamp = file.length() to file.lastModified()
            val same = uploaded[relative] == stamp
            if (!same) {
                try {
                    SmbClient.upload(config, file, remoteDir + relative)
                    uploaded[relative] = stamp
                    uploadedBytes = uploaded.values.sumOf { it.first }
                    lastProblem = null
                    onUploaded(uploadedBytes)
                } catch (failure: Exception) {
                    ok = false
                    lastProblem = failure.message ?: failure.javaClass.simpleName
                    onProblem(lastProblem.orEmpty())
                    break
                }
            }
        }
        if (ok && final) {
            // Metadata and playlists stay local: the server needs them and they are tiny.
            files.filter { !it.name.equals("info.json", true) && !it.name.endsWith(".m3u8", true) }
                .forEach { runCatching { it.delete() } }
        }
        return@synchronized ok
    }
}

/**
 * The share's free space, refreshed off the main thread. The settings list and the recorder read
 * the last known value instead of waiting on the network.
 */
internal object SmbSpace {
    @Volatile var freeBytes: Long = 0L
        private set
    @Volatile var totalBytes: Long = 0L
        private set
    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastRefreshAt = 0L

    fun refresh(context: Context, config: SmbConfig? = SmbSettings.load(context)) {
        val real = config ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRefreshAt < 60_000L) return
        if (!refreshing.compareAndSet(false, true)) return
        lastRefreshAt = now
        kotlin.concurrent.thread(name = "smb-space", isDaemon = true) {
            try {
                val free = SmbClient.freeBytes(real)
                if (free > 0L) freeBytes = free
                totalBytes = 0L
            } finally {
                refreshing.set(false)
            }
        }
    }
}

