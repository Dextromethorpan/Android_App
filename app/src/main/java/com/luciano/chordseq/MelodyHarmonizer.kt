package com.luciano.chordseq

// ═════════════════════════════════════════════════════════════════════════════
//  MelodyHarmonizer.kt — App 2: Melody → Chord Harmonization
//  ChordsPro · Luciano Muratore
//
//  Takes a list of detected note names + tonic key + genre and returns
//  one chord name per note, harmonized within the diatonic scale.
//
//  Usage:
//    val chords = MelodyHarmonizer.harmonize(
//        notes    = listOf("E4","D4","C4","G4"),
//        tonicKey = "C",
//        genre    = "Jazz"
//    )
//    // → ["Cmaj7", "Dm7", "Cmaj7", "G7"]
// ═════════════════════════════════════════════════════════════════════════════

object MelodyHarmonizer {

    private val NOTE_NAMES = listOf("C","C#","D","D#","E","F","F#","G","G#","A","A#","B")

    // Major scale intervals (semitones above tonic)
    private val MAJOR_INTERVALS = intArrayOf(0, 2, 4, 5, 7, 9, 11)

    // Chord quality per scale degree (I ii iii IV V vi vii)
    private val DEFAULT_QUALITIES = listOf("maj7","m7","m7","maj7","7","m7","m7b5")

    // Intervals that make up each chord quality (root = 0)
    private val CHORD_TONES = mapOf(
        "maj7"   to setOf(0, 4, 7, 11),
        "m7"     to setOf(0, 3, 7, 10),
        "7"      to setOf(0, 4, 7, 10),
        "m7b5"   to setOf(0, 3, 6, 10),
        "maj"    to setOf(0, 4, 7),
        "m"      to setOf(0, 3, 7),
        "5"      to setOf(0, 7),
        "9"      to setOf(0, 4, 7, 10, 14),
        "m9"     to setOf(0, 3, 7, 10, 14)
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Harmonize a melody note list within [tonicKey] using [genre] preferences.
     * Returns one chord name per note.
     */
    fun harmonize(notes: List<String>, tonicKey: String, genre: String): List<String> {
        val tonicIdx   = NOTE_NAMES.indexOf(tonicKey).coerceAtLeast(0)
        val diatonic   = buildDiatonicChords(tonicIdx, genre)
        return notes.mapIndexed { i, note ->
            val pc = pitchClass(note) ?: return@mapIndexed diatonic[0]
            val valid = diatonic.filter { chord -> containsNote(chord, pc) }
            pickByGenre(valid.ifEmpty { listOf(diatonic[0]) }, genre, notes, i)
        }
    }

    /**
     * Returns true if the most common root note in [notes] matches [selectedKey].
     * Used to warn the user when their melody doesn't match the selected tonic.
     */
    fun validateKey(notes: List<String>, selectedKey: String): Boolean {
        if (notes.isEmpty()) return true
        val freq = Array(12) { 0 }
        notes.forEach { note -> pitchClass(note)?.let { freq[it]++ } }
        val dominantPc = freq.indices.maxByOrNull { freq[it] } ?: 0
        val tonicPc    = NOTE_NAMES.indexOf(selectedKey)
        return dominantPc == tonicPc
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun buildDiatonicChords(tonicIdx: Int, genre: String): List<String> {
        return MAJOR_INTERVALS.indices.map { degree ->
            val root    = NOTE_NAMES[(tonicIdx + MAJOR_INTERVALS[degree]) % 12]
            val quality = qualityForGenre(degree, genre)
            "$root$quality"
        }
    }

    private fun qualityForGenre(degree: Int, genre: String): String {
        return when (genre) {
            "Blues"          -> "7"           // all dominant 7ths
            "Rock"           -> when (degree) { 0,3,4 -> "" else -> "m" }
            "Folk / Country" -> when (degree) { 0,3,4 -> "" else -> "m" }
            "Pop"            -> when (degree) { 0,3 -> "maj7"; 4 -> "7"; else -> DEFAULT_QUALITIES[degree] }
            "Funk"           -> when (degree) { 0,3 -> "maj7"; 4 -> "9"; else -> "m7" }
            else             -> DEFAULT_QUALITIES[degree]  // Jazz, Bossa Nova, Soul, Electronic
        }
    }

    private fun containsNote(chord: String, pc: Int): Boolean {
        val root    = rootPc(chord) ?: return false
        val quality = chord.removePrefix(NOTE_NAMES[root])
        val tones   = CHORD_TONES[quality] ?: CHORD_TONES["maj7"]!!
        return tones.any { interval -> (root + interval) % 12 == pc }
    }

    private fun pickByGenre(candidates: List<String>, genre: String,
                            allNotes: List<String>, idx: Int): String {
        if (candidates.size == 1) return candidates[0]

        return when (genre) {
            "Jazz", "Bossa Nova" -> {
                // Prefer chords where melody note is 7th or 3rd
                candidates.firstOrNull { chord ->
                    val root    = rootPc(chord) ?: return@firstOrNull false
                    val quality = chord.removePrefix(NOTE_NAMES[root])
                    val pc      = pitchClass(allNotes[idx]) ?: return@firstOrNull false
                    val interval = (pc - root + 12) % 12
                    quality.contains("7") && (interval == 10 || interval == 11 || interval == 3 || interval == 4)
                } ?: candidates[0]
            }
            "Rock" -> {
                // Prefer root or 5th in melody — avoid 7ths
                candidates.firstOrNull { !it.contains("7") } ?: candidates[0]
            }
            "Pop" -> {
                // Prefer I, IV, vi
                candidates.firstOrNull { c ->
                    val r = rootPc(c); r != null && r in setOf(0, 5, 9).map { (rootPc(candidates[0]) ?: 0 + it) % 12 }
                } ?: candidates[0]
            }
            "Bossa Nova" -> {
                // Smooth voice leading — prefer chord closest to previous
                if (idx == 0) return candidates[0]
                candidates.minByOrNull { chord ->
                    val r1 = rootPc(chord) ?: 0
                    val r2 = rootPc(allNotes.getOrNull(idx - 1) ?: "") ?: 0
                    minOf(Math.abs(r1 - r2), 12 - Math.abs(r1 - r2))
                } ?: candidates[0]
            }
            else -> candidates[0]
        }
    }

    private fun pitchClass(note: String): Int? {
        val stripped = note.trimEnd { it.isDigit() || it == '-' }
        val idx = NOTE_NAMES.indexOf(stripped)
        return if (idx >= 0) idx else null
    }

    private fun rootPc(chord: String): Int? {
        val root = if (chord.length >= 2 && chord[1] == '#') chord.substring(0, 2)
        else chord.substring(0, 1)
        val idx = NOTE_NAMES.indexOf(root)
        return if (idx >= 0) idx else null
    }
}