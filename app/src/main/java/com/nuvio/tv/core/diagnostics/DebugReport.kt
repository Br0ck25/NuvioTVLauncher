package com.nuvio.tv.core.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.player.LastPlaybackDiagnostics
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.livetv.crash.CrashCatcher
import com.nuvio.tv.livetv.model.LiveTvLoadReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** A redacted debug report that is ready to upload. */
data class DebugReport(
    val id: String,
    val fileName: String,
    val text: String
)

/**
 * Collects the information needed to investigate a user report: app and device versions, the
 * active addon list, the last playback diagnostics and the recent app log.
 *
 * Everything passes through [DebugReportRedactor] before leaving the device. Addon URLs are cut
 * down to their host, and tokens, passwords, email addresses and IP addresses are removed.
 */
object DebugReportBuilder {

    suspend fun build(
        context: Context,
        addons: List<Addon>,
        diagnostics: LastPlaybackDiagnostics,
        playerPreference: String,
        internalPlayerEngine: String,
        authState: AuthState
    ): DebugReport = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString().replace("-", "").take(8).uppercase(Locale.US)
        val raw = buildString {
            appendLine("Nuvio TV Launcher debug report")
            appendLine("ID: $id")
            appendLine("Generated: ${timestamp()}")

            section("APP")
            appendLine("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("package=${BuildConfig.APPLICATION_ID}")
            appendLine("buildType=${BuildConfig.BUILD_TYPE}")
            appendLine("debugBuild=${BuildConfig.IS_DEBUG_BUILD}")
            appendLine("environment=${BuildConfig.SENTRY_ENVIRONMENT}")
            appendLine("sentry=${if (BuildConfig.SENTRY_DSN.isBlank()) "not configured" else "configured"}")
            appendLine("reportUpload=${if (BuildConfig.DEBUG_REPORT_DISCORD_WEBHOOK.isBlank()) "not configured" else "configured"}")
            when (authState) {
                is AuthState.FullAccount -> {
                    appendLine("account=signed in")
                    // Stable, non-reversible: lets support find the sync row without the UUID
                    // appearing in a public report.
                    appendLine("accountSupportCode=${SupportCode.forUserId(authState.userId)}")
                }
                AuthState.SignedOut -> appendLine("account=signed out")
                AuthState.Loading -> appendLine("account=loading")
            }

            section("DEVICE")
            appendLine("manufacturer=${Build.MANUFACTURER}")
            appendLine("brand=${Build.BRAND}")
            appendLine("model=${Build.MODEL}")
            appendLine("device=${Build.DEVICE}")
            appendLine("product=${Build.PRODUCT}")
            appendLine("android=${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
            val metrics = context.resources.displayMetrics
            appendLine("screen=${metrics.widthPixels}x${metrics.heightPixels} @ ${metrics.densityDpi}dpi")
            appendLine("locale=${Locale.getDefault().toLanguageTag()}")
            appendLine("timezone=${TimeZone.getDefault().id}")
            appendLine("fireTv=${Build.MANUFACTURER.equals("Amazon", ignoreCase = true)}")
            val packageManager = context.packageManager
            appendLine("leanback=${packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)}")
            appendLine("television=${packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)}")

            section("PLAYBACK")
            appendLine("playerPreference=$playerPreference")
            appendLine("internalPlayerEngine=$internalPlayerEngine")
            appendLine("lastPlayback=${if (diagnostics.timestampMs == 0L) "none" else "present"}")
            appendLine(
                runCatching { JSONObject(diagnostics.toJson()).toString(2) }
                    .getOrElse { diagnostics.toJson() }
            )

            section("ADDONS (${addons.size})")
            if (addons.isEmpty()) {
                appendLine("(none)")
            } else {
                addons.sortedBy { it.name.lowercase(Locale.US) }.forEach { addon ->
                    val host = runCatching { Uri.parse(addon.baseUrl).host }.getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?: "unknown"
                    appendLine(
                        "- ${addon.name} | version=${addon.version} | " +
                            "${if (addon.enabled) "enabled" else "disabled"} | " +
                            "types=${addon.rawTypes.joinToString(",")} | " +
                            "resources=${addon.resources.joinToString(",") { it.name }} | host=$host"
                    )
                }
            }

            section("LIVE TV")
            appendLine(LiveTvLoadReport.shortText(limit = 8_000))

            runCatching { CrashCatcher.lastReport(context) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { crash ->
                    section("SAVED CRASH OR FREEZE REPORT")
                    appendLine(crash.take(20_000))
                }

            section("RECENT LOG")
            appendLine(readRecentLogs())
        }
        val text = DebugReportRedactor.redact(raw)
        DebugReport(id = id, fileName = "debug-report-$id.txt", text = text)
    }

    private fun StringBuilder.section(name: String) {
        appendLine()
        appendLine("== $name ==")
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date())

    /**
     * Reads the app's own logcat tail. Android only lets an app read its own UID's log, which is
     * exactly what a report needs. If a device blocks it, the report says so instead of failing.
     */
    private fun readRecentLogs(maxLines: Int = 20_000): String {
        return runCatching {
            val process = ProcessBuilder(
                // Same UID, not just this PID: after a native crash the app restarts with a new
                // PID, and the old process's last lines (including the VLC/native crash) are
                // exactly what the report needs.
                "logcat", "-d", "-v", "time", "-t", maxLines.toString(), "--uid=${Process.myUid()}"
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            // YouTube's engine logs a "Path not found" line for every optional JSON field it
            // reads. A single video lookup fills the whole buffer with them and pushes the
            // useful YouTubeVM/player lines out, so leave them out of the report.
            val useful = output.lineSequence()
                .filterNot { it.contains("JsonPathTypeAdapter") }
                .joinToString("\n")
            if (useful.isBlank()) {
                "No recent log lines were available."
            } else {
                useful.trimEnd().takeLast(150_000)
            }
        }.getOrElse { error ->
            "Log unavailable (${error.javaClass.simpleName})."
        }
    }
}

/**
 * Removes credentials and personal data from a report. Addon and stream URLs keep only their
 * host, because the path and query usually contain a user's configuration token.
 */
object DebugReportRedactor {

    private val webhook = BuildConfig.DEBUG_REPORT_DISCORD_WEBHOOK.trim()

    private val bearer = Regex("""(?i)\b(bearer\s+)[A-Za-z0-9._~+/=-]{8,}""")
    private val keyValue = Regex(
        """(?i)\b(authorization|proxy-authorization|x-api-key|set-cookie|cookie|access[_-]?token|refresh[_-]?token|api[_-]?key|apikey|debrid[_-]?key|premiumize[_-]?key|torbox[_-]?key|rd[_-]?key|password|passwd|secret|token|auth)(\s*[=:]\s*)("[^"]*"|'[^']*'|[^\s&,;]+)"""
    )
    private val querySecret = Regex(
        """(?i)([?&](?:access[_-]?token|refresh[_-]?token|api[_-]?key|apikey|token|secret|password|auth|code)=)[^&\s]+"""
    )
    private val email = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val url = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
    private val ipv4 = Regex(
        """(?<![\d.])(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)(?![\d.])"""
    )
    private val uuid = Regex("""(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b""")
    private val longToken = Regex("""\b[A-Za-z0-9_-]{48,}\b""")

    fun redact(text: String): String {
        var out = text
        if (webhook.isNotBlank()) out = out.replace(webhook, "[redacted webhook]")
        out = bearer.replace(out) { "${it.groupValues[1]}[redacted]" }
        out = keyValue.replace(out) {
            "${it.groupValues[1]}${it.groupValues[2]}[redacted]"
        }
        out = querySecret.replace(out) { "${it.groupValues[1]}[redacted]" }
        out = url.replace(out) { match ->
            val value = match.value
            val scheme = value.indexOf("://")
            if (scheme < 0) {
                value
            } else {
                val hostStart = scheme + 3
                val hostEnd = value.indexOfAny(charArrayOf('/', '?', '#'), hostStart)
                    .let { if (it < 0) value.length else it }
                val host = value.substring(hostStart, hostEnd).substringAfterLast('@')
                value.substring(0, hostStart) + host + "/[redacted]"
            }
        }
        out = email.replace(out, "[email]")
        out = ipv4.replace(out, "[ip]")
        out = uuid.replace(out, "[id]")
        out = longToken.replace(out, "[redacted]")
        return out
    }
}
