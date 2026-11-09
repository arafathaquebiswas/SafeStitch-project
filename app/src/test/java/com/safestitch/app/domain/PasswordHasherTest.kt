package com.safestitch.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordHasherTest {
    @Test
    fun hashVerifiesOnlyTheRightPassword() {
        val stored = PasswordHasher.hash("correct horse battery", iterations = 1_000)
        assertTrue(PasswordHasher.verify("correct horse battery", stored))
        assertFalse(PasswordHasher.verify("correct horse batterY", stored))
        assertFalse(PasswordHasher.verify("", stored))
    }

    @Test
    fun hashIsSaltedAndNeverContainsThePassword() {
        val a = PasswordHasher.hash("SamePassword1", iterations = 1_000)
        val b = PasswordHasher.hash("SamePassword1", iterations = 1_000)
        assertNotEquals(a, b)
        assertFalse(a.contains("SamePassword1"))
        assertEquals(4, a.split('$').size)
        assertTrue(a.startsWith("pbkdf2-sha256$1000$"))
    }

    @Test
    fun defaultIterationCountIsUsedAndRoundTrips() {
        val stored = PasswordHasher.hash("factory-safety")
        assertTrue(stored.startsWith("pbkdf2-sha256$${PasswordHasher.ITERATIONS}$"))
        assertTrue(PasswordHasher.verify("factory-safety", stored))
    }

    @Test
    fun malformedStoredValuesAreRejectedNotCrashing() {
        assertFalse(PasswordHasher.verify("x", ""))
        assertFalse(PasswordHasher.verify("x", "plain-text-password"))
        assertFalse(PasswordHasher.verify("x", "pbkdf2-sha256\$0\$AAAA\$AAAA"))
        assertFalse(PasswordHasher.verify("x", "pbkdf2-sha256\$1000\$not base64!\$AAAA"))
    }

    @Test
    fun passwordAndUsernameRules() {
        assertNotNull(PasswordHasher.problem("short"))
        assertNotNull(PasswordHasher.problem("        "))
        assertNull(PasswordHasher.problem("long enough"))
        assertEquals("r.ahmed", Usernames.normalize("  R.Ahmed "))
        assertNull(Usernames.problem("R.Ahmed"))
        assertNotNull(Usernames.problem("ra"))
        assertNotNull(Usernames.problem("has space"))
    }
}
