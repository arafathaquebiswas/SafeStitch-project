package com.safestitch.app.domain

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Local account passwords: PBKDF2-HMAC-SHA256 with a random 16-byte salt per user.
 * Stored as `pbkdf2-sha256$<iterations>$<salt base64>$<hash base64>`; the plain password is never stored.
 */
object PasswordHasher {
    const val ITERATIONS = 100_000
    const val MIN_LENGTH = 8
    private const val PREFIX = "pbkdf2-sha256"
    private const val KEY_BITS = 256
    private val random = SecureRandom()

    fun hash(password: String, iterations: Int = ITERATIONS): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val derived = derive(password, salt, iterations)
        val encoder = Base64.getEncoder()
        return "$PREFIX$$iterations$${encoder.encodeToString(salt)}$${encoder.encodeToString(derived)}"
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != PREFIX) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return false
        return try {
            val decoder = Base64.getDecoder()
            val expected = decoder.decode(parts[3])
            MessageDigest.isEqual(expected, derive(password, decoder.decode(parts[2]), iterations))
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Returns a problem description, or null if the password is acceptable. */
    fun problem(password: String): String? = when {
        password.length < MIN_LENGTH -> "Use at least $MIN_LENGTH characters."
        password.isBlank() -> "The password cannot be only spaces."
        else -> null
    }
}

object Usernames {
    private val pattern = Regex("^[a-z0-9._-]{3,32}$")

    fun normalize(raw: String): String = raw.trim().lowercase(Locale.ROOT)

    fun problem(raw: String): String? =
        if (pattern.matches(normalize(raw))) null else "Use 3-32 letters, numbers, dots, dashes or underscores (no spaces)."
}
