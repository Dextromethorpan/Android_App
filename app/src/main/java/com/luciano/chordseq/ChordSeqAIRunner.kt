package com.luciano.chordseq

// ═════════════════════════════════════════════════════════════════════════════
//  ChordSeqAIRunner.kt — App 2: ChordSeqAI ONNX Wrapper
//  ChordsPro · Luciano Muratore
//
//  Thin wrapper around ChordEngine that exposes a simple suspend fun
//  to extend a seed chord into a 4-chord progression.
//
//  Reuses ChordEngine's exact token encoding, ONNX tensor format,
//  vocabulary mapping and genre/decade conditioning — no duplication.
//
//  Usage:
//    val runner = ChordSeqAIRunner(context)
//    runner.load()                           // call once on IO thread
//    val chords = runner.extend(             // suspend, runs on IO
//        seedChord = "Cmaj7",
//        genre     = "Jazz",
//        decade    = "1960s"
//    )
//    runner.close()                          // call from onDestroy()
// ═════════════════════════════════════════════════════════════════════════════

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ChordSeqAIRunner(context: Context) {

    private val engine = ChordEngine(context)
    private var isLoaded = false

    // ── Public API ────────────────────────────────────────────────────────────

    /** Load the ONNX model. Call once from a background thread / IO dispatcher. */
    suspend fun load() = withContext(Dispatchers.IO) {
        engine.load()
        isLoaded = true
    }

    /**
     * Extend [seedChord] into a 4-chord progression conditioned on [genre]
     * and [decade]. Returns exactly 4 chord name strings.
     *
     * Runs entirely on Dispatchers.IO.
     */
    suspend fun extend(
        seedChord : String,
        genre     : String,
        decade    : String
    ): List<String> = withContext(Dispatchers.IO) {
        if (!isLoaded) error("Call load() before extend()")

        // Resolve seed chord token
        val seedToken = engine.tokenForChord(seedChord)
            ?: engine.chordsByRoot()[seedChord.take(if (seedChord.length > 1 && seedChord[1] == '#') 2 else 1)]
                ?.firstOrNull()?.let { engine.tokenForChord(it) }
            ?: return@withContext listOf(seedChord, seedChord, seedChord, seedChord)

        // Build genre and decade weight vectors
        val genreLabels  = engine.genreLabels
        val genreIdx     = genreLabels.indexOfFirst { it.equals(genre, ignoreCase = true) }
        val decadeIdx    = ChordEngine.DECADE_LABELS.indexOfFirst { it == decade }

        val genreW  = FloatArray(ChordEngine.N_GENRES).also  { w -> if (genreIdx  >= 0) w[genreIdx]  = 1f }
        val decadeW = FloatArray(ChordEngine.N_DECADES).also { w -> if (decadeIdx >= 0) w[decadeIdx] = 1f }

        // Build token history: BOS + seed
        val tokenHistory = mutableListOf(ChordEngine.BOS_TOKEN, seedToken.toLong())
        val chords       = mutableListOf(seedChord)

        // Predict 3 more chords
        repeat(3) {
            runCatching {
                val pred = engine.predictNextChord(
                    inputIds      = tokenHistory,
                    genreWeights  = genreW,
                    decadeWeights = decadeW,
                    temperature   = 1.0f
                )
                chords.add(pred.chordName)
            }
        }

        // Pad to 4 if any predictions failed
        while (chords.size < 4) chords.add(chords.last())
        chords.take(4)
    }

    /** Release ONNX resources. Call from Activity.onDestroy(). */
    fun close() = engine.close()
}