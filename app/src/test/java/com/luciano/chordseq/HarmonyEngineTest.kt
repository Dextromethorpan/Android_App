package com.luciano.chordseq

// ═════════════════════════════════════════════════════════════════════════════
//  HarmonyEngineTest.kt — Unit tests for Layer 5 (HarmonyEngine)
//  Run with: ./gradlew test
// ═════════════════════════════════════════════════════════════════════════════

import org.junit.Assert.*
import org.junit.Test

class HarmonyEngineTest {

    private val cMajor = KeyResult("C", "major", 0.91f)
    private val aMinor = KeyResult("A", "minor", 0.87f)
    private val gMajor = KeyResult("G", "major", 0.88f)

    // ── Basic prediction ──────────────────────────────────────────────────────

    @Test fun `predict returns exactly 3 next chords`() {
        val result = HarmonyEngine.predict("Jazz", cMajor, "Cmaj")
        assertEquals(3, result.nextChords.size)
        assertEquals(3, result.degrees.size)
        assertEquals(3, result.roles.size)
    }

    @Test fun `predict sets correct genre and key in result`() {
        val result = HarmonyEngine.predict("Blues", cMajor, "Cmaj")
        assertEquals("Blues",    result.genre)
        assertEquals("C major",  result.key)
        assertEquals("Cmaj",     result.initialChord)
    }

    // ── Jazz ─────────────────────────────────────────────────────────────────

    @Test fun `Jazz ii-V-I resolves correctly in C major`() {
        // Starting on Dm (ii) should give G7 (V) → Cmaj (I) → Fmaj (IV)
        val result = HarmonyEngine.predict("Jazz", cMajor, "Dm")
        assertTrue("Next chords should include G7", result.nextChords.any { it.contains("G") })
    }

    // ── Blues ─────────────────────────────────────────────────────────────────

    @Test fun `Blues from I contains IV and V`() {
        val result = HarmonyEngine.predict("Blues", cMajor, "Cmaj")
        val names = result.nextChords.map { it.take(1) }
        assertTrue("Blues should contain F (IV)", names.contains("F"))
    }

    // ── Pop ──────────────────────────────────────────────────────────────────

    @Test fun `Pop I-V-vi-IV progression in G major`() {
        val result = HarmonyEngine.predict("Pop", gMajor, "Gmaj")
        assertEquals(3, result.nextChords.size)
        // Pop template I–V–vi–IV should give D, Em, C after G
        val names = result.nextChords.map { it.take(1) }
        assertTrue("Pop from G should include D", names.contains("D"))
    }

    // ── Minor key ────────────────────────────────────────────────────────────

    @Test fun `predict works correctly in minor key`() {
        val result = HarmonyEngine.predict("Pop", aMinor, "Am")
        assertEquals(3, result.nextChords.size)
        // Degrees should use lowercase roman numerals for minor
        assertTrue("Minor degrees should contain lowercase", result.degrees.any { it[0].isLowerCase() })
    }

    // ── Roles ────────────────────────────────────────────────────────────────

    @Test fun `roles contain only valid values`() {
        val valid = setOf("tonic","subdominant","dominant","other")
        val result = HarmonyEngine.predict("Classical", cMajor, "Cmaj")
        result.roles.forEach { role ->
            assertTrue("Role '$role' not valid", valid.contains(role))
        }
    }

    // ── Supported genres ─────────────────────────────────────────────────────

    @Test fun `supportedGenres returns non-empty list`() {
        val genres = HarmonyEngine.supportedGenres()
        assertTrue(genres.isNotEmpty())
        assertTrue(genres.contains("Jazz"))
        assertTrue(genres.contains("Blues"))
        assertTrue(genres.contains("Pop"))
        assertTrue(genres.contains("Flamenco"))
        assertTrue(genres.contains("Bossa Nova"))
    }

    // ── Unknown genre fallback ────────────────────────────────────────────────

    @Test fun `unknown genre falls back gracefully`() {
        val result = HarmonyEngine.predict("ZombieMusic", cMajor, "Cmaj")
        assertEquals(3, result.nextChords.size)   // still returns 3 chords
    }

    // ── Unrecognised initial chord ────────────────────────────────────────────

    @Test fun `unrecognised initial chord defaults to tonic`() {
        val result = HarmonyEngine.predict("Pop", cMajor, "Xdim")
        assertEquals(3, result.nextChords.size)   // still returns 3 chords
    }
}