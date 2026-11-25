package com.nuvio.tv.music

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class GeminiSuggestion(
    val type: String,
    val title: String,
    val artist: String,
    val year: Int?,
    val reason: String,
)

/**
 * A small direct client for the user's own Gemini API key. Gemini only proposes titles; the app
 * verifies them against YouTube Music before they appear and never lets Gemini choose a stream.
 */
internal object MusicGeminiClient {
    private const val HOST = "https://generativelanguage.googleapis.com"
    private val JSON = "application/json".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    fun generate(apiKey: String, model: String, prompt: String): Result<List<GeminiSuggestion>> = runCatching {
        val key = apiKey.trim()
        if (key.isBlank()) throw IOException("Add a Gemini API key first.")
        val modelId = model.trim().ifBlank { GeminiSettings.DEFAULT_MODEL }
        val body = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                )
            )
            put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.65)
                    .put("responseMimeType", "application/json")
            )
        }
        val request = Request.Builder()
            .url("$HOST/v1beta/models/$modelId:generateContent")
            .header("x-goog-api-key", key)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(friendlyError(response.code, raw))
            val root = runCatching { JSONObject(raw) }.getOrElse {
                throw IOException("Gemini answered, but not with a usable response.")
            }
            val text = root.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                ?.trim()
                ?.removePrefix("```json")
                ?.removePrefix("```")
                ?.removeSuffix("```")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw IOException("Gemini returned no suggestions.")
            parseSuggestions(text)
        }
    }

    /**
     * Reads the JSON Gemini was asked for. Anything malformed or incomplete is dropped rather
     * than shown, so a partial answer can never become a broken row.
     */
    internal fun parseSuggestions(text: String): List<GeminiSuggestion> {
        val payload = runCatching { JSONObject(text) }.getOrElse {
            throw IOException("Gemini's suggestions weren't in the expected format.")
        }
        val items = payload.optJSONArray("items") ?: throw IOException("Gemini returned no suggestions.")
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val type = item.optString("type").lowercase().ifBlank { "track" }
                val title = item.optString("title").trim()
                val artist = item.optString("artist").trim()
                if (title.isBlank() || artist.isBlank()) continue
                if (type != "track" && type != "album") continue
                add(
                    GeminiSuggestion(
                        type = type,
                        title = title,
                        artist = artist,
                        year = item.optInt("year", 0).takeIf { it > 1900 },
                        reason = item.optString("reason").trim(),
                    )
                )
            }
        }.distinctBy { "${it.type}|${it.title.lowercase()}|${it.artist.lowercase()}" }
    }

    private fun friendlyError(code: Int, raw: String): String {
        val detail = runCatching {
            JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("")
        return when (code) {
            400 -> if (detail.contains("API key", ignoreCase = true)) {
                "Gemini rejected the API key. Check it and try again."
            } else {
                "Gemini couldn't understand the request. Check the model name."
            }
            401, 403 -> "Gemini rejected this API key, or it isn't allowed to use the Generative Language API."
            404 -> "That Gemini model isn't available for this key."
            429 -> "Gemini's quota for this key is used up. Try again later."
            in 500..599 -> "Gemini is having trouble right now. Try again shortly."
            else -> detail.takeIf { it.isNotBlank() } ?: "Gemini returned HTTP $code."
        }
    }
}
