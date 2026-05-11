package com.luciano.chordseq

// ═════════════════════════════════════════════════════════════════════════════
//  AppStartupTest.kt — Startup & Integration Test Battery
//  ChordAnds · Luciano Muratore
//
//  PURPOSE: Pinpoint exactly which layer is causing the app crash on startup.
//  Each test is isolated so a failure in one does not affect the others.
//
//  Run with: ./gradlew test
//  Tests are grouped by layer — read the output top to bottom to find
//  the first failing group. That group is where the crash originates.
// ═════════════════════════════════════════════════════════════════════════════

import org.junit.Assert.*
import org.junit.Test
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AppStartupTest {

    // ─────────────────────────────────────────────────────────────────────────
    //  GROUP 1 — Pure data / no Android dependencies
    //  These must ALL pass. If any fail, the crash is in core logic.
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `01_ChordAnalyser_recognises_C_major_triad`() {
        val name = ChordAnalyser.analyse(listOf(60, 64, 67)) // C E G
        assertEquals("C", name)
    }

    @Test fun `01_ChordAnalyser_recognises_A_minor`() {
        val name = ChordAnalyser.analyse(listOf(57, 60, 64)) // A C E
        assertEquals("Am", name)
    }

    @Test fun `01_ChordAnalyser_returns_dash_for_empty`() {
        assertEquals("—", ChordAnalyser.analyse(emptyList()))
    }

    @Test fun `01_ChordAnalyser_handles_single_note`() {
        val name = ChordAnalyser.analyse(listOf(60))
        assertEquals("C", name)
    }

    @Test fun `01_ChordAnalyser_recognises_G7`() {
        val name = ChordAnalyser.analyse(listOf(55, 59, 62, 65)) // G B D F
        assertEquals("G7", name)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  GROUP 2 — KeyDetector (pure Kotlin, no Android)
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `02_KeyDetector_pitchClass_C4_is_0`() {
        assertEquals(0, KeyDetector.pitchClassOf("C4"))
    }

    @Test fun `02_KeyDetector_pitchClass_Asharp4_is_10`() {
        assertEquals(10, KeyDetector.pitchClassOf("A#4"))
    }

    @Test fun `02_KeyDetector_pitchClass_unknown_is_null`() {
        assertNull(KeyDetector.pitchClassOf("X4"))
    }

    @Test fun `02_KeyDetector_detects_C_major`() {
        val notes = listOf("C4","E4","G4","C5","E5","G5","C4","E4")
        val result = KeyDetector.detect(notes)
        assertNotNull("Should detect a key from clear C major input", result)
        assertEquals("C",     result!!.tonic)
        assertEquals("major", result.mode)
    }

    @Test fun `02_KeyDetector_returns_null_for_empty`() {
        assertNull(KeyDetector.detect(emptyList()))
    }

    @Test fun `02_KeyDetector_confidence_is_between_0_and_1`() {
        val result = KeyDetector.detect(listOf("C4","E4","G4","C5"))
        assertNotNull(result)
        assertTrue("Confidence must be 0–1", result!!.confidence in 0f..1f)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  GROUP 3 — HarmonyEngine (pure Kotlin, no Android)
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `03_HarmonyEngine_returns_3_chords`() {
        val key    = KeyResult("C", "major", 0.9f)
        val result = HarmonyEngine.predict("Jazz", key, "Cmaj")
        assertEquals(3, result.nextChords.size)
    }

    @Test fun `03_HarmonyEngine_returns_3_degrees`() {
        val key    = KeyResult("C", "major", 0.9f)
        val result = HarmonyEngine.predict("Pop", key, "Cmaj")
        assertEquals(3, result.degrees.size)
    }

    @Test fun `03_HarmonyEngine_returns_3_roles`() {
        val key    = KeyResult("C", "major", 0.9f)
        val result = HarmonyEngine.predict("Blues", key, "Cmaj")
        assertEquals(3, result.roles.size)
    }

    @Test fun `03_HarmonyEngine_roles_are_valid_strings`() {
        val valid  = setOf("tonic","subdominant","dominant","other")
        val key    = KeyResult("G", "major", 0.88f)
        val result = HarmonyEngine.predict("Rock", key, "Gmaj")
        result.roles.forEach { assertTrue("Invalid role: $it", it in valid) }
    }

    @Test fun `03_HarmonyEngine_key_string_is_formatted`() {
        val key    = KeyResult("A", "minor", 0.85f)
        val result = HarmonyEngine.predict("Flamenco", key, "Am")
        assertEquals("A minor", result.key)
    }

    @Test fun `03_HarmonyEngine_supported_genres_not_empty`() {
        assertTrue(HarmonyEngine.supportedGenres().isNotEmpty())
    }

    @Test fun `03_HarmonyEngine_all_supported_genres_produce_results`() {
        val key = KeyResult("C", "major", 0.9f)
        HarmonyEngine.supportedGenres().forEach { genre ->
            val result = HarmonyEngine.predict(genre, key, "Cmaj")
            assertEquals("Genre '$genre' should return 3 chords", 3, result.nextChords.size)
        }
    }

    @Test fun `03_HarmonyEngine_unknown_genre_falls_back`() {
        val key    = KeyResult("C", "major", 0.9f)
        val result = HarmonyEngine.predict("UnknownGenre", key, "Cmaj")
        assertEquals(3, result.nextChords.size)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  GROUP 4 — EditableChord data model
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `04_EditableChord_creates_correctly`() {
        val chord = EditableChord("Cmaj", mutableListOf(60, 64, 67))
        assertEquals("Cmaj", chord.name)
        assertEquals(3, chord.midiNotes.size)
    }

    @Test fun `04_EditableChord_notes_are_mutable`() {
        val chord = EditableChord("Cmaj", mutableListOf(60, 64, 67))
        chord.midiNotes.add(72)
        assertEquals(4, chord.midiNotes.size)
    }

    @Test fun `04_EditableChord_name_is_mutable`() {
        val chord = EditableChord("Cmaj", mutableListOf(60, 64, 67))
        chord.name = "Caug"
        assertEquals("Caug", chord.name)
    }

    @Test fun `04_EditableChord_reanalysis_after_note_change`() {
        val chord = EditableChord("Cmaj", mutableListOf(60, 64, 67))
        // Remove E (64) → now only C and G remain → C5 (power chord / custom)
        chord.midiNotes.remove(64)
        chord.name = ChordAnalyser.analyse(chord.midiNotes)
        // Should not crash and should return some string
        assertNotNull(chord.name)
        assertTrue(chord.name.isNotEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  GROUP 5 — YIN Pitch Detector (pure Kotlin, no Android)
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `05_YIN_detects_440hz_sine_as_A4`() {
        val sampleRate = 44100
        val bufferSize = 2048
        val detector   = YINPitchDetector(sampleRate, bufferSize)

        // Generate a pure 440 Hz sine wave (A4)
        val buffer = ShortArray(bufferSize) { i ->
            (Short.MAX_VALUE * Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate)).toInt().toShort()
        }

        val result = detector.detect(buffer, bufferSize)
        assertNotNull("YIN should detect a clear 440Hz tone", result)
        val hz = result!!.pitchHz
        assertTrue("Detected pitch $hz should be near 440Hz", hz in 420f..460f)
    }

    @Test fun `05_YIN_silence_is_filtered_by_pitch_range`() {
        // Silence produces tau=2 → pitchHz = 44100/2 = 22050 Hz (ultrasonic).
        // AudioCapture filters anything outside 50–2000 Hz, so silence is
        // never emitted to the UI. We verify the pitch is out of musical range.
        val detector = YINPitchDetector(44100, 2048)
        val silence  = ShortArray(2048) { 0 }
        val result   = detector.detect(silence, 2048)
        // Either null, or pitch outside musical range (both are correctly filtered)
        if (result != null) {
            val inMusicalRange = result.pitchHz in 50f..2000f
            assertFalse("Silence pitch ${result.pitchHz}Hz should be outside musical range", inMusicalRange)
        }
    }

    @Test fun `05_YIN_handles_noise_without_crashing`() {
        val detector = YINPitchDetector(44100, 2048)
        val noise    = ShortArray(2048) { (Math.random() * Short.MAX_VALUE * 2 - Short.MAX_VALUE).toInt().toShort() }
        // Should not throw an exception
        assertDoesNotThrow { detector.detect(noise, 2048) }
    }

    @Test fun `05_YIN_detects_middle_C_261hz`() {
        val sampleRate = 44100
        val bufferSize = 4096
        val detector   = YINPitchDetector(sampleRate, bufferSize)
        val freq       = 261.63  // C4

        val buffer = ShortArray(bufferSize) { i ->
            (Short.MAX_VALUE * 0.8 * Math.sin(2.0 * Math.PI * freq * i / sampleRate)).toInt().toShort()
        }

        val result = detector.detect(buffer, bufferSize)
        assertNotNull("YIN should detect 261Hz (C4)", result)
        assertTrue("Detected pitch should be near 261Hz", result!!.pitchHz in 245f..278f)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  GROUP 6 — Layer integration (no Android context needed)
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `06_integration_YIN_to_KeyDetector_pipeline`() {
        // Simulate what happens when the user hums C major notes
        val simulatedNotes = listOf("C4","E4","G4","C5","E5","G5","C4","E4","G4")
        val keyResult = KeyDetector.detect(simulatedNotes)
        assertNotNull("Pipeline should produce a key", keyResult)
        assertTrue("Key should have sufficient confidence", keyResult!!.confidence >= 0.6f)
    }

    @Test fun `06_integration_KeyDetector_to_HarmonyEngine_pipeline`() {
        // Simulate full pipeline: notes → key → chord progression
        val notes     = listOf("C4","E4","G4","C5","E5","G5","C4","E4","G4")
        val keyResult = KeyDetector.detect(notes)!!
        val result    = HarmonyEngine.predict("Jazz", keyResult, "Cmaj")

        assertEquals(3,       result.nextChords.size)
        assertEquals("Jazz",  result.genre)
        assertTrue(result.key.isNotEmpty())
    }

    @Test fun `06_integration_note_edit_reanalysis_pipeline`() {
        // Simulate user editing a chord on the piano roll
        val chord = EditableChord("Cmaj", mutableListOf(60, 64, 67))

        // User adds a 7th
        chord.midiNotes.add(71)  // B4
        chord.midiNotes.sort()
        chord.name = ChordAnalyser.analyse(chord.midiNotes)
        assertEquals("Cmaj7", chord.name)

        // User removes the root
        chord.midiNotes.remove(60)
        chord.name = ChordAnalyser.analyse(chord.midiNotes)
        assertNotNull(chord.name)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Utility
    // ─────────────────────────────────────────────────────────────────────────

    private fun assertDoesNotThrow(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            fail("Expected no exception but got: ${e::class.simpleName}: ${e.message}")
        }
    }
}