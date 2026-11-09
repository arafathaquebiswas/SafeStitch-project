package com.safestitch.app.domain

import java.util.Locale

/**
 * QR label format. SafeStitch prints labels as `SAFESTITCH:CP:<CODE>`, but plain codes
 * (for example existing asset tags such as `FE-0012`) are accepted too.
 */
object QrPayload {
    const val PREFIX = "SAFESTITCH:CP:"
    const val MAX_CODE_LENGTH = 64

    fun forCheckpoint(code: String): String = PREFIX + code

    /** Returns the normalised (upper-case, trimmed) checkpoint code, or null if unusable. */
    fun parseCheckpointCode(raw: String): String? {
        val trimmed = raw.trim()
        val body = if (trimmed.startsWith(PREFIX, ignoreCase = true)) {
            trimmed.substring(PREFIX.length).trim()
        } else {
            trimmed
        }
        if (body.isEmpty() || body.length > MAX_CODE_LENGTH) return null
        if (body.any { it.isISOControl() }) return null
        return body.uppercase(Locale.ROOT)
    }
}
