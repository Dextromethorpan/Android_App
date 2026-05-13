package com.luciano.chordseq

// ═════════════════════════════════════════════════════════════════════════════
//  MainActivity2.kt — App 2: Melody to Chords
//  ChordsPro · Luciano Muratore
//
//  Pipeline:
//    User hums → PitchDetector (YIN) → note list
//    → MelodyHarmonizer → seed chords
//    → ChordSeqAIRunner (ONNX) → 4-chord progression
//    → ChordDeriver → 7 instrument tracks
//    → UI cards
// ═════════════════════════════════════════════════════════════════════════════

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity2 : AppCompatActivity() {

    private lateinit var pitchDetector    : PitchDetector

    private lateinit var chordSeqRunner   : ChordSeqAIRunner

    private var selectedKey    = "C"
    private var selectedGenre  = "Jazz"
    private var selectedDecade = "1960s"
    private var detectedNotes  = listOf<String>()
    private var isRecording    = false

    // UI refs
    private lateinit var tvSelectedKey   : TextView
    private lateinit var tvDetectedNotes : TextView
    private lateinit var tvKeyWarning    : TextView
    private lateinit var tvStatus        : TextView
    private lateinit var btnRecord       : TextView
    private lateinit var btnGenerate     : TextView
    private lateinit var chordTimeline    : LinearLayout   // 4-chord cards
    private var currentChords             = listOf<String>()
    private var currentTracks             : ChordDeriver.TrackResult? = null
    private var barsPerChord              = 2
    private lateinit var exportBtn          : TextView
    private lateinit var exportSection      : View

    // Per-track mini rolls and play buttons
    private val trackRolls   = mutableListOf<TrackMiniRollView>()
    private var playAllJob   : kotlinx.coroutines.Job? = null
    private var chordEngine2 : ChordEngine? = null
    private lateinit var tracksSection   : LinearLayout

    // 7-track TextViews
    private lateinit var tvBass          : TextView
    private lateinit var tvRhythmGuitar  : TextView
    private lateinit var tvPiano         : TextView
    private lateinit var tvPads          : TextView
    private lateinit var tvLead          : TextView
    private lateinit var tvCounter       : TextView
    private lateinit var tvPercussion    : TextView

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP  = ViewGroup.LayoutParams.WRAP_CONTENT

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()

        pitchDetector = PitchDetector(this)

        chordSeqRunner = ChordSeqAIRunner(this)

        tvStatus.text = "Loading model…"
        lifecycleScope.launch {
            runCatching { chordSeqRunner.load() }
                .onSuccess  { tvStatus.text = "Ready ✓" }
                .onFailure  { tvStatus.text = "Model load failed" }
        }

        if (!pitchDetector.hasPermission()) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.RECORD_AUDIO), 201)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        chordSeqRunner.close()
        chordEngine2?.close()
        playAllJob?.cancel()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 201 && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            tvDetectedNotes.text = "Microphone permission denied"
        }
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private fun buildUI() {
        val scroll = ScrollView(this).apply { setBackgroundColor(C.BG_WRAP) }
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG_SCREEN)
        }

        screen.addView(buildTopBar())
        screen.addView(hDivider())
        screen.addView(buildKeySelector())
        screen.addView(hDivider())
        screen.addView(buildStylePickers())
        screen.addView(hDivider())
        screen.addView(buildRecordSection())
        screen.addView(hDivider())
        screen.addView(buildChordSection())
        screen.addView(hDivider())
        screen.addView(buildTracksSection())
        screen.addView(buildExportSection())

        scroll.addView(screen)
        setContentView(scroll)
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(10))
            gravity = Gravity.CENTER_VERTICAL
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = "ChordsPro"; textSize = 16f
            setTypeface(null, Typeface.BOLD); setTextColor(C.TXT_PRIMARY)
        })
        col.addView(TextView(this).apply {
            text = "Melody → Chords"; textSize = 10f; setTextColor(C.TXT_MUTED)
        })
        tvStatus = TextView(this).apply {
            text = "Starting…"; textSize = 9f; setTextColor(C.TXT_HINT)
        }
        col.addView(tvStatus)
        bar.addView(col, lp(0, WRAP) { weight = 1f })

        // Back button — finishes this activity (returns to launcher or App 1)
        bar.addView(TextView(this).apply {
            text = "← back"; textSize = 11f; setTextColor(C.PURPLE_MID)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        })
        return bar
    }

    private fun buildKeySelector(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(secLabel("① select key"))

        val keyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), 0, dp(14), dp(10))
            gravity = Gravity.CENTER_VERTICAL
        }

        val piano = PianoKeySelectorView(this) { key ->
            selectedKey = key
            tvSelectedKey.text = key
            tvKeyWarning.visibility = View.GONE
        }
        keyRow.addView(piano, lp(0, dp(64)) { weight = 1f; marginEnd = dp(12) })

        val keyCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        keyCol.addView(TextView(this).apply { text = "Key"; textSize = 9f; setTextColor(C.TXT_HINT) })
        tvSelectedKey = TextView(this).apply {
            text = "C"; textSize = 22f
            setTypeface(null, Typeface.BOLD); setTextColor(C.PURPLE_LITE)
            gravity = Gravity.CENTER
        }
        keyCol.addView(tvSelectedKey)
        keyRow.addView(keyCol, lp(dp(48), WRAP))
        col.addView(keyRow)
        return col
    }

    private fun buildStylePickers(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(secLabel("② style settings"))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), 0, dp(14), dp(12))
        }

        val genres  = listOf("Jazz","Blues","Rock","Pop","Soul / R&B",
            "Folk / Country","Funk","Bossa Nova","Electronic")
        val decades = ChordEngine.DECADE_LABELS

        row.addView(spinnerCard("Genre", genres)  { selectedGenre  = it }, lp(0, WRAP) { weight = 1f; marginEnd = dp(8) })
        row.addView(spinnerCard("Decade", decades) { selectedDecade = it }, lp(0, WRAP) { weight = 1f })
        col.addView(row)
        return col
    }

    private fun buildRecordSection(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(secLabel("③ hum your melody"))

        // Record button row
        val recRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), 0, dp(14), dp(8))
            gravity = Gravity.CENTER_VERTICAL
        }

        btnRecord = TextView(this).apply {
            text = "🎤 Hold to Record"; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(C.PURPLE_LITE)
            setBackgroundColor(C.PURPLE)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { startRecording(); true }
                    MotionEvent.ACTION_UP   -> { stopRecording();  true }
                    else -> false
                }
            }
        }
        recRow.addView(btnRecord, lp(0, WRAP) { weight = 1f; marginEnd = dp(10) })

        btnGenerate = TextView(this).apply {
            text = "Generate ↗"; textSize = 12f; gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setTextColor(C.PURPLE_LITE)
            setBackgroundColor(C.PURPLE_DARK)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isEnabled = false; alpha = 0.38f
            setOnClickListener { onGenerateClicked() }
        }
        recRow.addView(btnGenerate)
        col.addView(recRow)

        // Detected notes
        tvDetectedNotes = TextView(this).apply {
            text = "Detected notes appear here…"
            textSize = 11f; setTextColor(C.TXT_MUTED)
            typeface = Typeface.MONOSPACE
            setPadding(dp(14), dp(4), dp(14), dp(8))
        }
        col.addView(tvDetectedNotes)

        // Key warning
        tvKeyWarning = TextView(this).apply {
            text = "⚠ Melody may not match selected key"
            textSize = 10f; setTextColor(Color.parseColor("#FF6B35"))
            setPadding(dp(14), 0, dp(14), dp(8))
            visibility = View.GONE
        }
        col.addView(tvKeyWarning)
        return col
    }

    private fun buildChordSection(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(secLabel("④ chord progression"))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), 0, dp(14), dp(10))
        }

        // 4 chord slots
        chordTimeline = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(C.BG_SECTION)
        }

        // Placeholder slots
        for (i in 0..3) {
            val cols = C.SLOTS[i % C.SLOTS.size]
            val slot = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(7), dp(8), 0)
                setBackgroundColor(C.BG_SECTION)
                alpha = 0.35f
            }
            slot.addView(TextView(this).apply {
                text = "—"; textSize = 13f
                setTypeface(null, Typeface.BOLD); setTextColor(C.TXT_HINT)
                gravity = Gravity.CENTER
            })
            slot.addView(View(this).apply {
                setBackgroundColor(cols[3])
                layoutParams = LinearLayout.LayoutParams(MATCH, dp(3)).apply { topMargin = dp(5) }
            })
            chordTimeline.addView(slot, lp(0, MATCH) { weight = 1f })
        }

        col.addView(chordTimeline, lp(MATCH, dp(56)) { setMargins(dp(14), 0, dp(14), 0) })
        col.visibility = View.GONE
        col.tag = "chord_section"
        return col
    }

    private fun displayChordProgression(chords: List<String>) {
        currentChords = chords
        chordTimeline.removeAllViews()
        chords.forEachIndexed { i, name ->
            val cols = C.SLOTS[i % C.SLOTS.size]
            val slot = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(7), dp(8), 0)
                setBackgroundColor(cols[0])
            }
            slot.addView(TextView(this).apply {
                text = name; textSize = 13f
                setTypeface(null, Typeface.BOLD); setTextColor(cols[1])
            })
            slot.addView(TextView(this).apply {
                text = listOf("I","II","III","IV")[i]; textSize = 9f; setTextColor(cols[2])
            })
            slot.addView(View(this).apply {
                setBackgroundColor(cols[3])
                layoutParams = LinearLayout.LayoutParams(MATCH, dp(3)).apply { topMargin = dp(5) }
            })

            chordTimeline.addView(slot, lp(0, MATCH) { weight = 1f })
        }

        // Show chord section
        val chordSection = chordTimeline.parent as? LinearLayout
        chordSection?.visibility = View.VISIBLE


    }





    private fun buildTracksSection(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ── Play All button ───────────────────────────────────────────────────
        val playAllRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(10), dp(14), dp(6))
            gravity = Gravity.CENTER_VERTICAL
        }
        playAllRow.addView(secLabel("④ instrument tracks").apply {
            setPadding(0, 0, 0, 0)
        }, lp(0, WRAP) { weight = 1f })
        playAllRow.addView(TextView(this).apply {
            text = "▶ Play All"; textSize = 11f; gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD); setTextColor(C.PURPLE_LITE)
            setBackgroundColor(C.PURPLE)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setOnClickListener { onPlayAllClicked() }
        })
        col.addView(playAllRow)

        val instruments = listOf(
            "Bass"           to "root · oct 2",
            "Rhythm Guitar"  to "genre voicing",
            "Piano"          to "split LH / RH",
            "Pads / Strings" to "extended chord",
            "Lead Melody"    to "7th · oct 5",
            "Countermelody"  to "3rd · oct 4",
            "Percussion"     to "pattern guide"
        )
        val colors = listOf(
            C.SLOTS[0], C.SLOTS[1], C.SLOTS[2], C.SLOTS[3],
            C.SLOTS[0], C.SLOTS[1], C.SLOTS[2]
        )

        trackRolls.clear()
        val tvRefs = mutableListOf<TextView>()

        instruments.forEachIndexed { i, (name, rule) ->
            val cols = colors[i]
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(cols[0])
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }

            // Header row: name + rule + play button
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(TextView(this).apply {
                text = name; textSize = 12f
                setTypeface(null, Typeface.BOLD); setTextColor(cols[1])
            }, lp(0, WRAP) { weight = 1f })
            header.addView(TextView(this).apply {
                text = rule; textSize = 9f; setTextColor(cols[2])
            })
            val trackIdx = i
            header.addView(TextView(this).apply {
                text = "  ▶"; textSize = 12f; setTextColor(cols[3])
                setPadding(dp(8), dp(2), 0, dp(2))
                setOnClickListener { onPlayTrackClicked(trackIdx) }
            })
            card.addView(header)

            // Accent bar
            card.addView(View(this).apply {
                setBackgroundColor(cols[3])
                layoutParams = LinearLayout.LayoutParams(MATCH, dp(2)).apply {
                    topMargin = dp(5); bottomMargin = dp(5)
                }
            })

            // Text output (chord names)
            val tv = TextView(this).apply {
                text = "—"; textSize = 11f; setTextColor(cols[1])
                typeface = Typeface.MONOSPACE; alpha = 0.5f
            }
            card.addView(tv)
            tvRefs.add(tv)

            // Mini piano roll
            val roll = TrackMiniRollView(this, cols[3])
            trackRolls.add(roll)
            card.addView(roll, LinearLayout.LayoutParams(MATCH, dp(60)).apply {
                topMargin = dp(6)
            })

            col.addView(card, lp(MATCH, WRAP) { setMargins(dp(14), 0, dp(14), dp(6)) })
        }

        tvBass         = tvRefs[0]; tvRhythmGuitar = tvRefs[1]; tvPiano    = tvRefs[2]
        tvPads         = tvRefs[3]; tvLead         = tvRefs[4]; tvCounter  = tvRefs[5]
        tvPercussion   = tvRefs[6]
        tracksSection  = col
        col.visibility = View.GONE
        return col
    }

    // ── Track playback ────────────────────────────────────────────────────────

    private fun onPlayTrackClicked(trackIdx: Int) {
        val tracks = currentTracks ?: return
        val notesList: List<String> = when (trackIdx) {
            0 -> tracks.bass
            1 -> tracks.rhythmGuitar
            2 -> tracks.piano.map { it.substringAfter("LH:").substringBefore(" ").split("+").first() }
            3 -> tracks.pads
            4 -> tracks.leadMelody
            5 -> tracks.counterMelody
            else -> return   // percussion — no pitched notes
        }
        lifecycleScope.launch {
            notesList.forEachIndexed { ci, noteName ->
                trackRolls.getOrNull(trackIdx)?.highlightChord(ci)
                val midi = MidiExporter.noteNameToMidi(noteName.trim()) ?: return@forEachIndexed
                PianoSynth.playChord(listOf(midi), durationMs = 1400)
                kotlinx.coroutines.delay(1700)
            }
            trackRolls.getOrNull(trackIdx)?.highlightChord(-1)
        }
    }

    private fun onPlayAllClicked() {
        val tracks = currentTracks ?: return
        playAllJob?.cancel()
        playAllJob = lifecycleScope.launch {
            // Build per-chord combined MIDI note lists (all tracks together)
            val chordCount = currentChords.size
            for (ci in 0 until chordCount) {
                // Highlight all rolls at this chord position
                trackRolls.forEach { it.highlightChord(ci) }

                // Gather all pitched notes from all tracks for this chord
                val allNotes = mutableListOf<Int>()
                listOf(
                    tracks.bass.getOrNull(ci),
                    tracks.rhythmGuitar.getOrNull(ci),
                    tracks.leadMelody.getOrNull(ci),
                    tracks.counterMelody.getOrNull(ci),
                    tracks.pads.getOrNull(ci)
                ).forEach { name ->
                    if (name != null) MidiExporter.noteNameToMidi(name.trim())?.let { allNotes.add(it) }
                }

                // Also add piano LH root note
                tracks.piano.getOrNull(ci)?.let { pianoStr ->
                    val lhRoot = pianoStr.substringAfter("LH:").substringBefore("+").trim()
                    MidiExporter.noteNameToMidi(lhRoot)?.let { allNotes.add(it) }
                }

                if (allNotes.isNotEmpty()) {
                    PianoSynth.playChord(allNotes.distinct(), durationMs = 1400)
                }
                kotlinx.coroutines.delay(1700)
            }
            trackRolls.forEach { it.highlightChord(-1) }
        }
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    private fun startRecording() {
        if (!pitchDetector.hasPermission()) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.RECORD_AUDIO), 201)
            return
        }
        isRecording = true
        detectedNotes = emptyList()
        tvDetectedNotes.text = "🎤 Listening…"
        tvKeyWarning.visibility = View.GONE
        btnGenerate.isEnabled = false; btnGenerate.alpha = 0.38f
        hideTracks()

        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                pitchDetector.startRecording { note ->
                    runOnUiThread {
                        val current = tvDetectedNotes.text.toString()
                        tvDetectedNotes.text = if (current == "🎤 Listening…") note
                        else "$current  $note"
                    }
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    isRecording = false
                    tvDetectedNotes.text = "Microphone error: ${e.message}"
                    btnGenerate.isEnabled = false
                    android.util.Log.e("App2", "startRecording failed", e)
                }
            }
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        lifecycleScope.launch(Dispatchers.IO) {
            detectedNotes = pitchDetector.stopRecording()
            withContext(Dispatchers.Main) {
                if (detectedNotes.isEmpty()) {
                    tvDetectedNotes.text = "No notes detected — try again"
                } else {
                    tvDetectedNotes.text = detectedNotes.joinToString("  ")
                    btnGenerate.isEnabled = true; btnGenerate.alpha = 1f
                    // Validate key
                    if (!MelodyHarmonizer.validateKey(detectedNotes, selectedKey)) {
                        tvKeyWarning.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    // ── Generate ──────────────────────────────────────────────────────────────

    private fun onGenerateClicked() {
        if (detectedNotes.isEmpty()) return
        btnGenerate.text = "Generating…"; btnGenerate.isEnabled = false
        tvStatus.text = "Running pipeline…"

        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                // Layer 3 → harmonize melody to seed chords
                val seedChords = MelodyHarmonizer.harmonize(detectedNotes, selectedKey, selectedGenre)
                val seedChord  = seedChords.firstOrNull() ?: selectedKey

                // ChordSeqAI → extend to 4-chord progression
                val chords = chordSeqRunner.extend(seedChord, selectedGenre, selectedDecade)

                // Layer 2 → derive 7 instrument tracks
                val tracks = ChordDeriver.deriveAllTracks(chords, selectedGenre, selectedDecade)

                withContext(Dispatchers.Main) {
                    currentTracks = tracks
                    displayChordProgression(chords)
                    displayTracks(tracks)
                    // Show export section (find by tag in screen)
                    showExportSection()
                    tvStatus.text = "Done · ${chords.joinToString(" → ")}"
                    btnGenerate.text = "Generate ↗"; btnGenerate.isEnabled = true; btnGenerate.alpha = 1f
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    Log.e("App2", "Pipeline failed", e)
                    tvStatus.text = "Error: ${e.message}"
                    btnGenerate.text = "Generate ↗"; btnGenerate.isEnabled = true; btnGenerate.alpha = 1f
                }
            }
        }
    }

    private fun displayTracks(tracks: ChordDeriver.TrackResult) {
        fun List<String>.fmt() = joinToString("  ·  ")
        tvBass.text         = tracks.bass.fmt()
        tvRhythmGuitar.text = tracks.rhythmGuitar.fmt()
        tvPiano.text        = tracks.piano.joinToString("\n")
        tvPads.text         = tracks.pads.fmt()
        tvLead.text         = tracks.leadMelody.fmt()
        tvCounter.text      = tracks.counterMelody.fmt()
        tvPercussion.text   = tracks.percussion.joinToString("\n")
        listOf(tvBass,tvRhythmGuitar,tvPiano,tvPads,tvLead,tvCounter,tvPercussion)
            .forEach { it.alpha = 1f }

        // Feed mini rolls
        val allTrackNotes = listOf(
            tracks.bass, tracks.rhythmGuitar,
            tracks.piano.map { it.substringAfter("LH:").substringBefore(" ").split("+").first() },
            tracks.pads, tracks.leadMelody, tracks.counterMelody,
            tracks.percussion  // percussion shows text pattern, roll stays empty
        )
        trackRolls.forEachIndexed { i, roll ->
            roll.setNotes(allTrackNotes.getOrElse(i) { emptyList() }, chordEngine2)
        }

        tracksSection.visibility = View.VISIBLE
    }

    private fun hideTracks() {
        tracksSection.visibility = View.GONE
        listOf(tvBass,tvRhythmGuitar,tvPiano,tvPads,tvLead,tvCounter,tvPercussion)
            .forEach { it.text = "—"; it.alpha = 0.5f }
        // Also reset chord timeline and export section
        val chordSection = chordTimeline.parent as? LinearLayout
        chordSection?.visibility = View.GONE
        currentTracks = null
        if (::exportSection.isInitialized) exportSection.visibility = View.GONE

    }

    // ── Widget helpers ────────────────────────────────────────────────────────

    private fun buildExportSection(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(14), dp(10), dp(14), dp(14))
        }
        exportSection = col

        col.addView(secLabel("⑤ export to DAW"))

        // Bars per chord selector
        val barsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(10))
        }
        barsRow.addView(TextView(this).apply {
            text = "Bars per chord:"; textSize = 11f; setTextColor(C.TXT_MUTED)
        }, lp(0, WRAP) { weight = 1f })

        listOf(1, 2, 4).forEach { bars ->
            val btn = TextView(this).apply {
                text = "$bars"; textSize = 12f; gravity = Gravity.CENTER
                setTextColor(if (bars == barsPerChord) C.PURPLE_LITE else C.TXT_MUTED)
                setBackgroundColor(if (bars == barsPerChord) C.PURPLE else C.BG_CARD)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                tag = bars
            }
            btn.setOnClickListener {
                barsPerChord = bars
                // Update button styles
                val parent = btn.parent as LinearLayout
                for (i in 1 until parent.childCount) {
                    val b = parent.getChildAt(i) as? TextView ?: continue
                    val v = b.tag as? Int ?: continue
                    b.setTextColor(if (v == barsPerChord) C.PURPLE_LITE else C.TXT_MUTED)
                    b.setBackgroundColor(if (v == barsPerChord) C.PURPLE else C.BG_CARD)
                }
            }
            barsRow.addView(btn, lp(WRAP, WRAP) { marginStart = dp(6) })
        }
        col.addView(barsRow)

        // Export button
        exportBtn = TextView(this).apply {
            text = "Export MIDI ↗"; textSize = 13f; gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setTextColor(C.PURPLE_LITE); setBackgroundColor(C.PURPLE)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setOnClickListener { onExportClicked() }
        }
        col.addView(exportBtn, lp(MATCH, WRAP))
        return col
    }

    private fun showExportSection() {
        exportSection.visibility = View.VISIBLE
    }

    private fun onExportClicked() {
        val tracks = currentTracks ?: return
        if (currentChords.isEmpty()) return

        exportBtn.text = "Generating…"; exportBtn.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val midi = MidiExporter.export(
                    tracks       = tracks,
                    chords       = currentChords,
                    bpm          = 120,
                    barsPerChord = barsPerChord
                )

                // Save to Downloads
                val fileName = "ChordsPro_${System.currentTimeMillis()}.mid"
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
                val file = java.io.File(downloadsDir, fileName)
                file.writeBytes(midi)

                // Share sheet
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this@MainActivity2,
                    "${packageName}.provider",
                    file
                )
                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "audio/midi"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "ChordsPro MIDI Export")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                withContext(Dispatchers.Main) {
                    startActivity(android.content.Intent.createChooser(shareIntent, "Share MIDI file"))
                    exportBtn.text = "Export MIDI ↗"; exportBtn.isEnabled = true
                    tvStatus.text = "Saved: $fileName"
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    android.util.Log.e("App2", "MIDI export failed", e)
                    tvStatus.text = "Export failed: ${e.message}"
                    exportBtn.text = "Export MIDI ↗"; exportBtn.isEnabled = true
                }
            }
        }
    }

    private fun secLabel(txt: String) = TextView(this).apply {
        text = txt; textSize = 10f; setTextColor(C.TXT_HINT)
        setPadding(dp(14), dp(10), dp(14), dp(6))
    }

    private fun hDivider() = View(this).apply {
        setBackgroundColor(C.BORDER)
        layoutParams = LinearLayout.LayoutParams(MATCH, 1).apply {
            setMargins(dp(14), 0, dp(14), 0)
        }
    }

    private fun spinnerCard(label: String, items: List<String>,
                            onSelected: (String) -> Unit): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG_SECTION)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        col.addView(TextView(this).apply {
            text = label; textSize = 9f; setTextColor(C.TXT_HINT)
        })
        val valueText = TextView(this).apply {
            text = items.first(); textSize = 13f
            setTextColor(C.TXT_PRIMARY)
            setTypeface(null, Typeface.BOLD)
        }
        col.addView(valueText)
        col.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(label)
                .setItems(items.toTypedArray()) { _, pos ->
                    valueText.text = items[pos]
                    onSelected(items[pos])
                }.show()
        }
        return col
    }

    private fun lp(w: Int, h: Int, block: LinearLayout.LayoutParams.() -> Unit = {}) =
        LinearLayout.LayoutParams(w, h).apply(block)

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()
}

// ─────────────────────────────────────────────────────────────────────────────
//  PianoKeySelectorView — one-octave piano for key selection
//  Same dark style as PianoSelectorView in MainActivity but single-octave
// ─────────────────────────────────────────────────────────────────────────────
class PianoKeySelectorView(
    context: android.content.Context,
    private val onKeySelected: (String) -> Unit
) : View(context) {

    private val whiteNotes = listOf("C","D","E","F","G","A","B")
    private val blackNotes = listOf("C#" to 0,"D#" to 1, null to -1,"F#" to 3,"G#" to 4,"A#" to 5)
    private var selectedKey = "C"

    private val whitePaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E8E8F0"); style = Paint.Style.FILL }
    private val blackPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A1A20"); style = Paint.Style.FILL }
    private val selWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#7F77DD"); style = Paint.Style.FILL }
    private val selBlackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#534AB7"); style = Paint.Style.FILL }
    private val borderPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3A3A50"); style = Paint.Style.STROKE; strokeWidth = 1f }
    private val labelPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#888899"); textSize = 22f; textAlign = Paint.Align.CENTER }
    private val selLblPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22f; textAlign = Paint.Align.CENTER }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val kW = w / 7f; val bW = kW * 0.62f; val bH = h * 0.60f
        whiteNotes.forEachIndexed { i, note ->
            val x = i * kW; val sel = note == selectedKey
            canvas.drawRect(x+1f, 0f, x+kW-1f, h-1f, if (sel) selWhitePaint else whitePaint)
            canvas.drawRect(x+1f, 0f, x+kW-1f, h-1f, borderPaint)
            canvas.drawText(note, x+kW/2f, h-10f, if (sel) selLblPaint else labelPaint)
        }
        blackNotes.forEach { (note, idx) ->
            if (note == null) return@forEach
            val x = idx*kW + kW - bW/2f; val sel = note == selectedKey
            canvas.drawRoundRect(x, 0f, x+bW, bH, 6f, 6f, if (sel) selBlackPaint else blackPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return false
        val kW = width.toFloat()/7f; val bW = kW*0.62f; val bH = height*0.60f
        val x = event.x; val y = event.y
        if (y < bH) {
            blackNotes.forEach { (note, idx) ->
                if (note == null) return@forEach
                val bx = idx*kW + kW - bW/2f
                if (x in bx..(bx+bW)) { selectedKey = note; onKeySelected(note); invalidate(); return true }
            }
        }
        val idx = (x/kW).toInt().coerceIn(0,6)
        selectedKey = whiteNotes[idx]; onKeySelected(selectedKey); invalidate()
        return true
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  TrackMiniRollView — compact piano roll per instrument track
//  Shows 4 chord note blocks, highlights the active chord during playback
// ─────────────────────────────────────────────────────────────────────────────
class TrackMiniRollView(
    context: android.content.Context,
    private val accentColor: Int
) : android.view.View(context) {

    private var noteNames   : List<String>   = emptyList()
    private var engine      : ChordEngine?   = null
    private var activeChord : Int            = -1

    // Row layout: 13 semitones C4–C5
    private val ROWS       = 13
    private val keyIsBlack = listOf(false,true,false,true,false,true,false,false,true,false,true,false,false)
    private val midiToRow  = mapOf(
        60 to 0, 59 to 1, 58 to 2, 57 to 3, 56 to 4, 55 to 5,
        53 to 6, 52 to 7, 51 to 8, 50 to 9, 49 to 10, 48 to 11, 47 to 12
    )

    private val bgPaint   = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val notePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
    }
    private val linePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeWidth = 0.5f
    }
    private val phPaint   = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = C.ORANGE; style = android.graphics.Paint.Style.FILL
    }

    fun setNotes(names: List<String>, eng: ChordEngine?) {
        noteNames = names; engine = eng; invalidate()
    }

    fun highlightChord(idx: Int) { activeChord = idx; invalidate() }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val rowH = h / ROWS

        // Row backgrounds
        keyIsBlack.forEachIndexed { i, black ->
            bgPaint.color = if (black) android.graphics.Color.parseColor("#0D0D18")
            else android.graphics.Color.parseColor("#111120")
            canvas.drawRect(0f, i * rowH, w, (i+1) * rowH, bgPaint)
        }

        // Column dividers
        if (noteNames.isNotEmpty()) {
            val slotW = w / noteNames.size
            linePaint.color = android.graphics.Color.parseColor("#2A2A3E")
            for (i in 1 until noteNames.size) {
                canvas.drawLine(i * slotW, 0f, i * slotW, h, linePaint)
            }
        }

        if (noteNames.isEmpty()) return

        val slotW = w / noteNames.size.coerceAtLeast(1)

        noteNames.forEachIndexed { ci, noteName ->
            val isActive = ci == activeChord
            val nx = ci * slotW + 2f
            val nw = slotW - 4f

            // Try to get MIDI from chord name via engine, else parse note name directly
            val midiList: List<Int> = if (engine != null) {
                engine!!.notesForChord(noteName).filter { it in 47..60 }.ifEmpty {
                    MidiExporter.noteNameToMidi(noteName.trim())?.let { listOf(it) } ?: emptyList()
                }
            } else {
                MidiExporter.noteNameToMidi(noteName.trim())?.let { listOf(it) } ?: emptyList()
            }

            val displayRows = midiList.mapNotNull { midiToRow[it] }.ifEmpty {
                listOf(6) // middle row as fallback
            }

            displayRows.forEachIndexed { ni, row ->
                notePaint.color = accentColor
                notePaint.alpha = when {
                    isActive && ni == 0 -> 255
                    isActive            -> 160
                    ni == 0             -> 200
                    else                -> 120
                }
                val y = row * rowH + 1f
                canvas.drawRoundRect(nx, y, nx + nw, y + rowH - 1f, 2f, 2f, notePaint)
            }
        }
        notePaint.alpha = 255

        // Playhead on active chord
        if (activeChord >= 0 && noteNames.isNotEmpty()) {
            val slotW2 = w / noteNames.size
            val px = activeChord * slotW2 + slotW2 * 0.5f
            phPaint.style = android.graphics.Paint.Style.STROKE
            val ph = android.graphics.Paint(phPaint).apply { strokeWidth = 2f }
            canvas.drawLine(px, 0f, px, h, ph)
        }
    }
}