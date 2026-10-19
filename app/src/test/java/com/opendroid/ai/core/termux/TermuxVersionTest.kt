package com.opendroid.ai.core.termux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxVersionTest {
    @Test
    fun `result callbacks require Termux 0 109 or later`() {
        assertFalse(TermuxVersion.atLeast("0.108.1", 0, 109))
        assertTrue(TermuxVersion.atLeast("0.109", 0, 109))
        assertTrue(TermuxVersion.atLeast("0.118.2", 0, 109))
    }

    @Test
    fun `version parser rejects missing or malformed values`() {
        assertFalse(TermuxVersion.atLeast(null, 0, 109))
        assertFalse(TermuxVersion.atLeast("", 0, 109))
        assertFalse(TermuxVersion.atLeast("beta", 0, 109))
        assertTrue(TermuxVersion.atLeast("1.0", 0, 109))
    }
}
