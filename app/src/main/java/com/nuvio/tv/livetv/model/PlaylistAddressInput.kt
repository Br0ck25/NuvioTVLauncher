package com.nuvio.tv.livetv.model

private val playlistAddressScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

/** The scheme shown outside an address field when the user hasn't typed one. */
internal fun playlistAddressPrefix(value: String, useHttps: Boolean = false): String {
    val address = value.trim()
    val scheme = if (useHttps) "https" else "http"
    return if (address.startsWith("//") || playlistAddressScheme.containsMatchIn(address)) "" else "$scheme://"
}

/** Complete an entered address without changing its path, query, encoding or explicit scheme. */
internal fun completePlaylistAddress(value: String, useHttps: Boolean = false): String {
    val scheme = if (useHttps) "https" else "http"
    val address = value.trim()
    return when {
        address.isEmpty() -> ""
        address.startsWith("//") -> "$scheme:$address"
        playlistAddressScheme.containsMatchIn(address) -> address
        else -> "$scheme://$address"
    }
}

/** Drops a typed or pasted scheme so the HTTP/HTTPS switch can own it. */
internal fun stripPlaylistAddressScheme(value: String): String =
    value.trimStart().replaceFirst(playlistAddressScheme, "")
