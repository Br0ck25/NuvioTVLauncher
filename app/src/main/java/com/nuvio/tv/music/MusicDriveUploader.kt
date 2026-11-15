package com.nuvio.tv.music

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.network.IPv4FirstDns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URLConnection
import java.util.Locale
import java.util.concurrent.TimeUnit

data class MusicDriveSignIn(
    val userCode: String,
    val url: String,
    val qrUrl: String
)

data class MusicDriveState(
    val connected: Boolean = false,
    val email: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val signIn: MusicDriveSignIn? = null,
    val uploaded: Int = 0,
    val total: Int = 0,
    val folderUrl: String? = null
)

/**
 * Copies local music into the user's Google Drive.
 *
 * Uses the same "TVs and limited input devices" OAuth client as the Live TV settings backup:
 * the TV shows a code, the user approves on their phone, and the app creates a "Nuvio Music"
 * folder in their own Drive. The `drive.file` scope means the app can only see files it created.
 */
object MusicDriveUploader {
    private const val TAG = "MusicDrive"
    private const val PREFS = "nuvio_music_drive"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_CLIENT_ID = "client_id"
    private const val KEY_EMAIL = "email"
    private const val KEY_FOLDER = "folder_id"
    private const val KEY_UPLOADED = "uploaded"
    private const val SCOPE = "openid email https://www.googleapis.com/auth/drive.file"
    private const val FOLDER_NAME = "Nuvio Music"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .dns(IPv4FirstDns())
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(MusicDriveState())
    val state: StateFlow<MusicDriveState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var accessToken: String? = null
    private var accessTokenExpiresAt = 0L
    private var signInJob: Job? = null

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val prefs = prefs(app)
        // The refresh token, folder and uploaded-file IDs all belong to the OAuth client that
        // created them. Moving to our own Google Cloud project makes the old ones unreadable.
        val clientId = BuildConfig.GOOGLE_DRIVE_CLIENT_ID
        if (prefs.getString(KEY_CLIENT_ID, null) != clientId) {
            prefs.edit()
                .putString(KEY_CLIENT_ID, clientId)
                .remove(KEY_REFRESH)
                .remove(KEY_EMAIL)
                .remove(KEY_FOLDER)
                .remove(KEY_UPLOADED)
                .apply()
        }
        val refresh = prefs.getString(KEY_REFRESH, null)
        val folderId = prefs.getString(KEY_FOLDER, null)
        _state.value = MusicDriveState(
            connected = !refresh.isNullOrBlank(),
            email = prefs.getString(KEY_EMAIL, null),
            folderUrl = folderId?.let { "https://drive.google.com/drive/folders/$it" }
        )
    }

    fun connect(context: Context) {
        init(context)
        if (_state.value.busy) return
        signInJob?.cancel()
        signInJob = scope.launch {
            _state.value = _state.value.copy(
                busy = true,
                message = null,
                signIn = null
            )
            val codes = runCatching {
                postForm(
                    "https://oauth2.googleapis.com/device/code",
                    mapOf(
                        "client_id" to BuildConfig.GOOGLE_DRIVE_CLIENT_ID,
                        "scope" to SCOPE
                    )
                )
            }.getOrElse {
                _state.value = _state.value.copy(
                    busy = false,
                    message = "Couldn't reach Google. Try again."
                )
                return@launch
            }
            val deviceCode = codes.optString("device_code")
            val userCode = codes.optString("user_code")
            if (deviceCode.isBlank() || userCode.isBlank()) {
                _state.value = _state.value.copy(
                    busy = false,
                    message = codes.optString("error_description")
                        .ifBlank { "Google didn't start the sign-in." }
                )
                return@launch
            }
            val verification = codes.optString("verification_url")
                .ifBlank { "https://www.google.com/device" }
            val prompt = MusicDriveSignIn(
                userCode = userCode,
                url = verification,
                qrUrl = "$verification?user_code=$userCode"
            )
            var interval = codes.optLong("interval", 5L).coerceAtLeast(2L)
            val expiresAt = System.currentTimeMillis() +
                codes.optLong("expires_in", 1800L) * 1000L
            _state.value = _state.value.copy(busy = false, signIn = prompt)

            while (System.currentTimeMillis() < expiresAt) {
                delay(interval * 1000L)
                val token = runCatching {
                    postForm(
                        "https://oauth2.googleapis.com/token",
                        mapOf(
                            "client_id" to BuildConfig.GOOGLE_DRIVE_CLIENT_ID,
                            "client_secret" to BuildConfig.GOOGLE_DRIVE_CLIENT_SECRET,
                            "device_code" to deviceCode,
                            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"
                        )
                    )
                }.getOrNull() ?: continue
                when (val error = token.optString("error")) {
                    "" -> {
                        onSignedIn(token)
                        return@launch
                    }
                    "authorization_pending" -> Unit
                    "slow_down" -> interval += 2L
                    "access_denied" -> {
                        _state.value = _state.value.copy(
                            busy = false,
                            signIn = null,
                            message = "Google Drive access was declined."
                        )
                        return@launch
                    }
                    "expired_token" -> {
                        _state.value = _state.value.copy(
                            busy = false,
                            signIn = null,
                            message = "The sign-in code expired. Try again."
                        )
                        return@launch
                    }
                    else -> {
                        _state.value = _state.value.copy(
                            busy = false,
                            signIn = null,
                            message = token.optString("error_description")
                                .ifBlank { "Google sign-in failed ($error)." }
                        )
                        return@launch
                    }
                }
            }
            _state.value = _state.value.copy(
                busy = false,
                signIn = null,
                message = "The sign-in code expired. Try again."
            )
        }
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        signInJob = null
        _state.value = _state.value.copy(busy = false, signIn = null)
    }

    fun disconnect() {
        signInJob?.cancel()
        signInJob = null
        val app = appContext ?: return
        val prefs = prefs(app)
        val refresh = prefs.getString(KEY_REFRESH, null)
        prefs.edit().clear().apply()
        accessToken = null
        accessTokenExpiresAt = 0L
        _state.value = MusicDriveState()
        if (refresh != null) {
            scope.launch {
                runCatching {
                    postForm("https://oauth2.googleapis.com/revoke", mapOf("token" to refresh))
                }
            }
        }
    }

    fun uploadAll(context: Context) {
        init(context)
        if (_state.value.busy) return
        if (!_state.value.connected) {
            _state.value = _state.value.copy(message = "Connect Google Drive first.")
            return
        }
        val tracks = LocalMusicRepository.tracks.value
        if (tracks.isEmpty()) {
            _state.value = _state.value.copy(message = "No local music to upload.")
            return
        }
        scope.launch {
            _state.value = _state.value.copy(
                busy = true,
                message = null,
                uploaded = 0,
                total = tracks.size
            )
            val token = token()
            if (token == null) {
                _state.value = _state.value.copy(
                    busy = false,
                    message = "Google Drive access expired. Connect again."
                )
                return@launch
            }
            val folderId = runCatching { ensureFolder(token) }.getOrElse {
                _state.value = _state.value.copy(
                    busy = false,
                    message = "Couldn't create the Nuvio Music folder in Drive."
                )
                return@launch
            }
            val uploadedMap = loadUploaded(appContext ?: return@launch)
            var done = 0
            var skipped = 0
            var failed = 0
            for (track in tracks) {
                val key = track.localUri ?: track.localPath ?: continue
                if (uploadedMap.has(key)) {
                    skipped++
                    done++
                    _state.value = _state.value.copy(uploaded = done)
                    continue
                }
                val ok = runCatching {
                    val fileId = uploadTrack(token, folderId, track)
                    uploadedMap.put(key, fileId)
                    saveUploaded(appContext ?: return@launch, uploadedMap)
                    true
                }.getOrElse {
                    Log.w(TAG, "Upload failed for ${track.title}: ${it.message}")
                    false
                }
                if (ok) done++ else failed++
                _state.value = _state.value.copy(uploaded = done)
            }
            _state.value = _state.value.copy(
                busy = false,
                uploaded = done,
                total = tracks.size,
                message = when {
                    failed > 0 -> "Uploaded ${done - skipped} tracks; $failed failed."
                    skipped > 0 -> "Everything is already in Drive ($skipped already there)."
                    else -> "Uploaded ${done} tracks to Google Drive."
                }
            )
        }
    }

    private suspend fun onSignedIn(token: JSONObject) {
        val app = appContext ?: return
        val refresh = token.optString("refresh_token").takeIf { it.isNotBlank() }
        if (refresh == null) {
            _state.value = _state.value.copy(
                busy = false,
                signIn = null,
                message = "Google didn't return a refresh token. Try connecting again."
            )
            return
        }
        accessToken = token.optString("access_token").takeIf { it.isNotBlank() }
        accessTokenExpiresAt = System.currentTimeMillis() +
            token.optLong("expires_in", 3600L) * 1000L
        val email = emailFromIdToken(token.optString("id_token"))
        prefs(app).edit()
            .putString(KEY_REFRESH, refresh)
            .putString(KEY_EMAIL, email)
            .apply()
        _state.value = _state.value.copy(
            connected = true,
            email = email,
            busy = false,
            signIn = null,
            message = "Google Drive connected."
        )
    }

    private suspend fun token(): String? {
        val app = appContext ?: return null
        val refresh = prefs(app).getString(KEY_REFRESH, null) ?: return null
        accessToken?.let {
            if (System.currentTimeMillis() < accessTokenExpiresAt - 60_000) return it
        }
        val result = runCatching {
            postForm(
                "https://oauth2.googleapis.com/token",
                mapOf(
                    "client_id" to BuildConfig.GOOGLE_DRIVE_CLIENT_ID,
                    "client_secret" to BuildConfig.GOOGLE_DRIVE_CLIENT_SECRET,
                    "refresh_token" to refresh,
                    "grant_type" to "refresh_token"
                )
            )
        }.getOrElse {
            _state.value = _state.value.copy(message = "Couldn't reach Google Drive.")
            return null
        }
        if (result.optString("error") == "invalid_grant") {
            prefs(app).edit().remove(KEY_REFRESH).apply()
            _state.value = _state.value.copy(
                connected = false,
                email = null,
                message = "Google Drive access was removed. Connect again."
            )
            return null
        }
        val access = result.optString("access_token").takeIf { it.isNotBlank() } ?: return null
        accessToken = access
        accessTokenExpiresAt = System.currentTimeMillis() +
            result.optLong("expires_in", 3600L) * 1000L
        return access
    }

    private suspend fun ensureFolder(token: String): String {
        val app = appContext ?: error("not initialized")
        val prefs = prefs(app)
        prefs.getString(KEY_FOLDER, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val metadata = JSONObject()
            .put("name", FOLDER_NAME)
            .put("mimeType", "application/vnd.google-apps.folder")
        val body = metadata.toString().toRequestBody(JSON)
        val response = execute(
            Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files?fields=id")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()
        )
        val id = JSONObject(response).optString("id")
        if (id.isBlank()) error("no folder id")
        prefs.edit().putString(KEY_FOLDER, id).apply()
        _state.value = _state.value.copy(
            folderUrl = "https://drive.google.com/drive/folders/$id"
        )
        return id
    }

    private suspend fun uploadTrack(token: String, folderId: String, track: com.music.bitchord.data.model.Song): String {
        val app = appContext ?: error("not initialized")
        val uri = track.localUri?.let(Uri::parse)
        val mime = when {
            uri?.scheme == "content" -> app.contentResolver.getType(uri)
            else -> URLConnection.guessContentTypeFromName(track.localPath ?: "")
        } ?: "audio/mpeg"
        val name = downloadName(track)
        val length = contentLength(app, track)
        val temp = if (length <= 0) {
            val file = File.createTempFile("music-upload-", ".bin", app.cacheDir)
            openStream(app, track)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: error("can't open file")
            file
        } else {
            null
        }
        val uploadLength = temp?.length() ?: length
        val metadata = JSONObject()
            .put("name", name)
            .put("mimeType", mime)
            .put("parents", JSONArray().put(folderId))
        val init = executeRaw(
            Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id")
                .header("Authorization", "Bearer $token")
                .header("X-Upload-Content-Type", mime)
                .header("X-Upload-Content-Length", uploadLength.toString())
                .post(metadata.toString().toRequestBody(JSON))
                .build()
        )
        val location = init.second.header("Location") ?: error("no upload URL")
        init.second.close()
        val body = object : RequestBody() {
            override fun contentType() = mime.toMediaType()
            override fun contentLength() = uploadLength
            override fun writeTo(sink: BufferedSink) {
                if (temp != null) {
                    FileInputStream(temp).source().use { sink.writeAll(it) }
                } else {
                    openStream(app, track)?.source()?.use { sink.writeAll(it) }
                        ?: error("can't open file")
                }
            }
        }
        val response = executeRaw(
            Request.Builder()
                .url(location)
                .put(body)
                .build()
        )
        val text = response.second.body?.string().orEmpty()
        response.second.close()
        runCatching { temp?.delete() }
        val id = runCatching { JSONObject(text).optString("id") }.getOrDefault("")
        if (id.isBlank()) error("no file id")
        return id
    }

    private fun openStream(context: Context, track: com.music.bitchord.data.model.Song): InputStream? {
        val uri = track.localUri?.let(Uri::parse) ?: return null
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            "file" -> runCatching { FileInputStream(File(uri.path ?: return null)) }.getOrNull()
            "content" -> runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            else -> track.localPath?.let {
                runCatching { FileInputStream(File(it)) }.getOrNull()
            }
        }
    }

    private fun contentLength(context: Context, track: com.music.bitchord.data.model.Song): Long {
        val uri = track.localUri?.let(Uri::parse)
        if (uri?.scheme == "content") {
            return runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull() ?: -1L
        }
        return track.localPath?.let { File(it).length() } ?: -1L
    }

    private fun downloadName(track: com.music.bitchord.data.model.Song): String {
        val safe = track.title
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "track" }
        val extension = track.localPath
            ?.substringAfterLast('.', "")
            ?.takeIf { it.length in 1..5 }
            ?: "mp3"
        return "$safe.$extension"
    }

    private suspend fun execute(request: Request): String {
        val (_, response) = executeRaw(request)
        return response.use { it.body?.string().orEmpty() }
    }

    private suspend fun executeRaw(request: Request): Pair<Int, okhttp3.Response> =
        withContext(Dispatchers.IO) {
            val response = http.newCall(request).execute()
            if (!response.isSuccessful) {
                val text = response.body?.string().orEmpty()
                val code = response.code
                response.close()
                error("HTTP $code ${text.take(160)}")
            }
            response.code to response
        }
    private suspend fun postForm(url: String, fields: Map<String, String>): JSONObject =
        withContext(Dispatchers.IO) {
            val form = FormBody.Builder().apply {
                fields.forEach { (key, value) -> add(key, value) }
            }.build()
            http.newCall(Request.Builder().url(url).post(form).build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                runCatching { JSONObject(text) }.getOrElse {
                    JSONObject().put("error", "http_${response.code}")
                }
            }
        }

    private fun emailFromIdToken(idToken: String): String? {
        if (idToken.isBlank()) return null
        val payload = idToken.split('.').getOrNull(1) ?: return null
        return runCatching {
            val bytes = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            JSONObject(String(bytes)).optString("email").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadUploaded(context: Context): JSONObject =
        runCatching { JSONObject(prefs(context).getString(KEY_UPLOADED, "{}")) }
            .getOrDefault(JSONObject())

    private fun saveUploaded(context: Context, uploaded: JSONObject) {
        prefs(context).edit().putString(KEY_UPLOADED, uploaded.toString()).apply()
    }

    private val JSON = "application/json; charset=UTF-8".toMediaType()
}
