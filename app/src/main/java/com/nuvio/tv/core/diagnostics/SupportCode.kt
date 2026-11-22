package com.nuvio.tv.core.diagnostics

import java.security.MessageDigest
import java.util.Locale

/**
 * A short, stable code for a Nuvio account, safe to put in a support report.
 *
 * It is derived from the account UUID but is not the UUID itself, so a report pasted in
 * a public channel does not expose the account's sync key. Support can map the code back
 * to the account on the sync service.
 */
internal object SupportCode {

    fun forUserId(userId: String): String {
        val normalized = userId.trim().lowercase(Locale.US)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
            .take(10)
            .uppercase(Locale.US)
    }
}
