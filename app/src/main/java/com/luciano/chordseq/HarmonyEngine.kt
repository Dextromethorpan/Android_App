package com.luciano.chordseq

// ═════════════════════════════════════════════════════════════════════════════
//  HarmonyEngine.kt — Layer 5: Harmony Engine (Chord Progression Prediction)
//  ChordAnds · Luciano Muratore
// ═════════════════════════════════════════════════════════════════════════════
//
//  LAYER 5 — HARMONY ENGINE
//  ┌─────────────────────────────────────────────────────────────────────────┐
//  │  Input  : genre (String), KeyResult (from Layer 4),                    │
//  │           initialChord (String) selected by the user on the piano      │
//  │  Output : ProgressionResult containing the 3 chords that follow        │
//  │           the initial chord                                             │
//  │                                                                         │
//  │  Logic:                                                                 │
//  │  1. Look up the genre's progression templates (Roman numeral degrees)  │
//  │  2. Identify which Roman numeral degree the initialChord maps to in    │
//  │     the detected key                                                   │
//  │  3. Rotate the template to start from that degree                      │
//  │  4. Resolve each Roman numeral to a concrete chord name in the key     │
//  │  5. Return the 3 chords after the initial one                          │
//  └─────────────────────────────────────────────────────────────────────────┘
//
//  HOW IT CONNECTS TO OTHER LAYERS:
//  KeyDetector (L4) → KeyResult
//  User piano selection → initialChord
//  HarmonyEngine.predict() → ProgressionResult → MainActivity →
//  piano roll slots 2-4
//
//  CHORD COLOUR ROLES (for UI tinting):
//  "tonic"        → purple  (stable, home)
//  "subdominant"  → teal    (movement away)
//  "dominant"     → amber   (tension, wants to resolve)
//  "other"        → pink    (colour / passing chord)
//
//  UNIT TESTS: see HarmonyEngineTest.kt
//
// ═════════════════════════════════════════════════════════════════════════════

// ─────────────────────────────────────────────────────────────────────────────
//  Result type
// ─────────────────────────────────────────────────────────────────────────────
data class ProgressionResult(
    val initialChord : String,
    val nextChords   : List<String>,       // 3 chord names following the initial
    val degrees      : List<String>,       // Roman numerals e.g. ["IV", "V", "I"]
    val roles        : List<String>,       // "tonic" | "subdominant" | "dominant" | "other"
    val genre        : String,
    val key          : String              // e.g. "C major"
)

// ─────────────────────────────────────────────────────────────────────────────
//  HarmonyEngine
// ─────────────────────────────────────────────────────────────────────────────
object HarmonyEngine {

    // ── Chord resolution tables ───────────────────────────────────────────────

    // Semitone intervals above tonic for each scale degree in major / minor
    // Index 0 = I, 1 = II, 2 = III, 3 = IV, 4 = V, 5 = VI, 6 = VII
    private val MAJOR_INTERVALS = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    private val MINOR_INTERVALS = intArrayOf(0, 2, 3, 5, 7, 8, 10)

    // Chord quality per degree in major / minor
    private val MAJOR_QUALITIES = listOf("maj","m","m","maj","7","m","dim")
    private val MINOR_QUALITIES = listOf("m","dim","maj","m","m","maj","7")

    // Harmonic role per degree (for UI colour coding)
    private val MAJOR_ROLES = listOf("tonic","other","other","subdominant","dominant","other","other")
    private val MINOR_ROLES = listOf("tonic","other","other","subdominant","dominant","other","other")

    private val NOTE_NAMES = listOf("C","C#","D","D#","E","F","F#","G","G#","A","A#","B")

    // ── Genre progression templates ───────────────────────────────────────────
    // Each genre has 2–3 templates expressed as Roman numeral degree indices
    // (0-based: 0=I, 1=II, 2=III, 3=IV, 4=V, 5=VI, 6=VII).
    // Each template is a full 4-chord loop; we rotate to find the 3 after initial.

    private val GENRE_TEMPLATES: Map<String, List<List<Int>>> = mapOf(

        "Jazz" to listOf(
            listOf(1, 4, 0, 3),          // ii–V–I–IV  (the classic ii-V-I)
            listOf(0, 3, 1, 4),          // I–IV–ii–V
            listOf(0, 5, 1, 4)           // I–vi–ii–V  (rhythm changes flavour)
        ),

        "Blues" to listOf(
            listOf(0, 3, 0, 4),          // I–IV–I–V  (12-bar blues core)
            listOf(0, 3, 4, 0),          // I–IV–V–I
            listOf(0, 0, 3, 4)           // I–I–IV–V
        ),

        "Pop" to listOf(
            listOf(0, 4, 5, 3),          // I–V–vi–IV  (the "four chords" song)
            listOf(0, 5, 3, 4),          // I–vi–IV–V
            listOf(5, 3, 0, 4)           // vi–IV–I–V  (minor-feel pop)
        ),

        "Bossa Nova" to listOf(
            listOf(0, 1, 4, 0),          // I–ii–V–I
            listOf(0, 3, 1, 4),          // I–IV–ii–V
            listOf(0, 5, 1, 4)           // I–vi–ii–V
        ),

        "Flamenco" to listOf(
            listOf(5, 3, 2, 4),          // vi–IV–III–V  (Andalusian cadence feel)
            listOf(5, 4, 3, 2),          // vi–V–IV–III
            listOf(0, 6, 5, 4)           // i–VII–VI–V   (Phrygian descent)
        ),

        "Rock" to listOf(
            listOf(0, 4, 5, 3),          // I–V–vi–IV
            listOf(0, 3, 4, 3),          // I–IV–V–IV
            listOf(0, 2, 3, 4)           // I–iii–IV–V
        ),

        "Classical" to listOf(
            listOf(0, 3, 4, 0),          // I–IV–V–I  (authentic cadence)
            listOf(0, 5, 3, 4),          // I–vi–IV–V
            listOf(0, 1, 4, 0)           // I–ii–V–I
        ),

        "R&B, Funk & Soul" to listOf(
            listOf(0, 5, 3, 4),          // I–vi–IV–V
            listOf(0, 3, 1, 4),          // I–IV–ii–V
            listOf(5, 1, 4, 0)           // vi–ii–V–I
        ),

        "Electronic" to listOf(
            listOf(0, 5, 3, 4),          // I–vi–IV–V
            listOf(0, 2, 3, 4),          // I–iii–IV–V
            listOf(5, 3, 0, 4)           // vi–IV–I–V
        ),

        "Comedy" to listOf(
            listOf(0, 3, 4, 0),          // I–IV–V–I  (bright, resolved)
            listOf(0, 4, 5, 3),          // I–V–vi–IV
            listOf(0, 2, 4, 0)           // I–iii–V–I
        )
    )

    // Fallback for unknown genres
    private val DEFAULT_TEMPLATE = listOf(0, 3, 4, 0)   // I–IV–V–I

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Predicts the 3 chords following [initialChord] given a [genre] and [keyResult].
     *
     * @param genre        e.g. "Jazz", "Blues", "Pop"
     * @param keyResult    from KeyDetector.detect()
     * @param initialChord e.g. "Dm", "Cmaj7", "G7" — the chord chosen by the user
     * @return             ProgressionResult with 3 next chords, or null if key unknown
     *
     * Example:
     *   predict("Jazz", KeyResult("C","major",0.91f), "Dm")
     *   → ProgressionResult(nextChords=["G7","Cmaj","Fmaj"], degrees=["V","I","IV"], …)
     */
    fun predict(
        genre        : String,
        keyResult    : KeyResult,
        initialChord : String
    ): ProgressionResult {

        val templates  = GENRE_TEMPLATES[genre] ?: GENRE_TEMPLATES["Pop"]!!
        val intervals  = if (keyResult.mode == "major") MAJOR_INTERVALS else MINOR_INTERVALS
        val qualities  = if (keyResult.mode == "major") MAJOR_QUALITIES else MINOR_QUALITIES
        val roles      = if (keyResult.mode == "major") MAJOR_ROLES     else MINOR_ROLES
        val tonicIdx   = NOTE_NAMES.indexOf(keyResult.tonic).coerceAtLeast(0)

        // Build the 7 diatonic chord names for this key
        val diatonicChords = (0..6).map { degree ->
            val noteIdx = (tonicIdx + intervals[degree]) % 12
            NOTE_NAMES[noteIdx] + qualities[degree]
        }

        // Find which degree the initialChord best matches
        val startDegree = findDegree(initialChord, diatonicChords) ?: 0

        // Try each template — pick the one where startDegree appears
        val template = templates.firstOrNull { it.contains(startDegree) }
            ?: templates.first()

        // Rotate template so startDegree is at position 0
        val startPos = template.indexOf(startDegree).coerceAtLeast(0)
        val rotated  = (0..3).map { template[(startPos + it) % template.size] }

        // Take the 3 degrees AFTER the initial chord
        val nextDegrees = rotated.drop(1).take(3)

        val nextChords  = nextDegrees.map { diatonicChords[it] }
        val nextRomans  = nextDegrees.map { romanNumeral(it, keyResult.mode) }
        val nextRoles   = nextDegrees.map { roles[it] }

        return ProgressionResult(
            initialChord = initialChord,
            nextChords   = nextChords,
            degrees      = nextRomans,
            roles        = nextRoles,
            genre        = genre,
            key          = "${keyResult.tonic} ${keyResult.mode}"
        )
    }

    /**
     * Returns all supported genre names — use to populate the genre picker.
     */
    fun supportedGenres(): List<String> = GENRE_TEMPLATES.keys.sorted()

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Finds which scale degree (0–6) the given chord name best matches.
     * Matches on root note first, then quality.
     * Returns null if no match found.
     */
    private fun findDegree(chord: String, diatonicChords: List<String>): Int? {
        // Exact match
        val exact = diatonicChords.indexOfFirst {
            it.equals(chord, ignoreCase = true)
        }
        if (exact >= 0) return exact

        // Root-only match (strip quality)
        val root = chord.take(if (chord.length > 1 && chord[1] == '#') 2 else 1)
        return diatonicChords.indexOfFirst { it.startsWith(root) }.takeIf { it >= 0 }
    }

    /**
     * Converts a 0-based degree index to a Roman numeral string.
     * Major degrees: I II III IV V VI VII
     * Minor degrees: i ii III iv v VI VII
     */
    private fun romanNumeral(degree: Int, mode: String): String {
        val major = listOf("I","II","III","IV","V","VI","VII")
        val minor = listOf("i","ii","III","iv","v","VI","VII")
        val list  = if (mode == "major") major else minor
        return list.getOrElse(degree) { "?" }
    }
}