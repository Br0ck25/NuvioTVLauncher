package com.nuvio.tv.core.diagnostics

import com.nuvio.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Uploads a debug report to the Discord webhook configured by
 * `DEBUG_REPORT_DISCORD_WEBHOOK` in local.properties.
 *
 * The webhook URL is never written to the report or to an error message, so a failed upload can be
 * shown to the user without exposing it.
 */
object DebugReportUploader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun upload(report: DebugReport) = withContext(Dispatchers.IO) {
        val webhook = BuildConfig.DEBUG_REPORT_DISCORD_WEBHOOK.trim()
        if (webhook.isBlank()) {
            error("Debug reports are not configured in this build")
        }
        val url = webhook.toHttpUrlOrNull()
            ?: error("The debug report destination is not a valid URL")

        val payload = JSONObject().apply {
            put(
                "content",
                "Debug report `${report.id}` · ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            )
            put("username", "Nuvio One Debug")
            put("allowed_mentions", JSONObject().put("parse", JSONArray()))
        }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("payload_json", payload.toString())
            .addFormDataPart(
                "file",
                report.fileName,
                report.text.toRequestBody("text/plain; charset=utf-8".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Upload failed (HTTP ${response.code})")
            }
        }
    }
}
