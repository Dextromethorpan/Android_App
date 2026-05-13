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

    // Extra TV refs for split tracks
    private lateinit var tvPianoLH   : TextView
    private lateinit var tvPianoRH   : TextView
    private lateinit var tvKick      : TextView
    private lateinit var tvSnare     : TextView
    private lateinit var tvHiHat     : TextView
    private lateinit var tvCrash     : TextView
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
            "Bass"               to "root · oct 2",
            "Rhythm Guitar"      to "genre voicing",
            "Piano — Left Hand"  to "root + 5th · oct 3",
            "Piano — Right Hand" to "3rd + 7th · oct 4",
            "Pads / Strings"     to "extended chord",
            "Lead Melody"        to "7th · oct 5",
            "Countermelody"      to "3rd · oct 4",
            "Kick"               to "beat 1 & 3",
            "Snare"              to "beat 2 & 4",
            "Hi-Hat"             to "8ths / 16ths",
            "Crash"              to "accent"
        )
        val colors = listOf(
            C.SLOTS[0], C.SLOTS[1],
            C.SLOTS[2], C.SLOTS[2],
            C.SLOTS[3], C.SLOTS[0], C.SLOTS[1],
            C.SLOTS[2], C.SLOTS[3], C.SLOTS[0], C.SLOTS[1]
        )

        trackRolls.clear()
        val tvRefs = mutableListOf<TextView>()

        instruments.forEachIndexed { i, (name, rule) ->
            val cols = colors[i]
            val trackIdx = i

            val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

            // Track label row
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(14), dp(8), dp(14), dp(4))
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(TextView(this).apply {
                text = name; textSize = 11f
                setTypeface(null, Typeface.BOLD); setTextColor(C.TXT_HINT)
            }, lp(0, WRAP) { weight = 1f })
            header.addView(TextView(this).apply {
                text = rule; textSize = 9f; setTextColor(C.TXT_HINT)
            })
            header.addView(TextView(this).apply {
                text = "  ▶"; textSize = 12f; setTextColor(cols[3])
                setPadding(dp(8), dp(2), 0, dp(2))
                setOnClickListener { onPlayTrackClicked(trackIdx) }
            })
            wrapper.addView(header)

            // 4 value cards side by side — like App 1 chord timeline
            val timeline = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val slotTexts = mutableListOf<TextView>()
            for (s in 0..3) {
                val sc = C.SLOTS[s % C.SLOTS.size]
                val slot = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(8), dp(7), dp(8), 0)
                    setBackgroundColor(sc[0])
                }
                val valueText = TextView(this).apply {
                    text = "—"; textSize = 12f
                    setTypeface(null, Typeface.BOLD); setTextColor(sc[1])
                }
                slot.addView(valueText)
                slot.addView(TextView(this).apply {
                    text = listOf("I","II","III","IV")[s]
                    textSize = 9f; setTextColor(sc[2])
                })
                slot.addView(View(this).apply {
                    setBackgroundColor(sc[3])
                    layoutParams = LinearLayout.LayoutParams(MATCH, dp(3)).apply { topMargin = dp(5) }
                })
                slotTexts.add(valueText)
                timeline.addView(slot, lp(0, dp(56)) { weight = 1f })
            }

            // Store slot refs via a dummy TextView tag
            val tv = TextView(this).apply { text = "—"; visibility = View.GONE; tag = slotTexts }
            tvRefs.add(tv)
            wrapper.addView(tv)
            wrapper.addView(timeline, lp(MATCH, dp(56)) { setMargins(dp(14), 0, dp(14), 0) })

            // Wide piano roll below all 4 slots
            val roll = TrackMiniRollView(this, cols[3])
            trackRolls.add(roll)
            val rollH = if (i >= 7) dp(40) else dp(140)
            wrapper.addView(roll, LinearLayout.LayoutParams(MATCH, rollH).apply {
                setMargins(dp(14), dp(4), dp(14), 0)
            })

            wrapper.addView(View(this).apply {
                setBackgroundColor(C.BORDER)
                layoutParams = LinearLayout.LayoutParams(MATCH, 1).apply { topMargin = dp(8) }
            })

            col.addView(wrapper, lp(MATCH, WRAP))
        }

        tvBass         = tvRefs[0];  tvRhythmGuitar = tvRefs[1]
        tvPianoLH      = tvRefs[2];  tvPianoRH      = tvRefs[3]
        tvPiano        = tvRefs[2]
        tvPads         = tvRefs[4];  tvLead         = tvRefs[5]; tvCounter = tvRefs[6]
        tvKick         = tvRefs[7];  tvSnare        = tvRefs[8]
        tvHiHat        = tvRefs[9];  tvCrash        = tvRefs[10]
        tvPercussion   = tvRefs[7]
        tracksSection  = col
        col.visibility = View.GONE
        return col
    }

    // ── Track playback ────────────────────────────────────────────────────────

    private fun onPlayTrackClicked(trackIdx: Int) {
        val tracks = currentTracks ?: return
        // Map track index to note list
        // 0=Bass 1=Guitar 2=PianoLH 3=PianoRH 4=Pads 5=Lead 6=Counter 7-10=Perc
        val notesList: List<String>? = when (trackIdx) {
            0 -> tracks.bass
            1 -> tracks.rhythmGuitar
            2 -> tracks.piano.map { p -> // LH: root+fifth
                p.substringAfter("LH:").substringBefore(" ").split("+").firstOrNull() ?: "C3"
            }
            3 -> tracks.piano.map { p -> // RH: third+seventh
                p.substringAfter("RH:").split("+").firstOrNull() ?: "E4"
            }
            4 -> tracks.pads
            5 -> tracks.leadMelody
            6 -> tracks.counterMelody
            else -> null  // percussion — handled separately below
        }

        if (notesList != null) {
            // Pitched track playback
            lifecycleScope.launch {
                notesList.forEachIndexed { ci, noteName ->
                    trackRolls.getOrNull(trackIdx)?.highlightChord(ci)
                    val midi = MidiExporter.noteNameToMidi(noteName.trim()) ?: return@forEachIndexed
                    PianoSynth.playChord(listOf(midi), durationMs = 1400)
                    kotlinx.coroutines.delay(1700)
                }
                trackRolls.getOrNull(trackIdx)?.highlightChord(-1)
            }
        } else {
            // Percussion track — play GM drum hits
            // GM drum MIDI notes (played via PianoSynth on channel 10 equivalent)
            // We use specific MIDI note numbers that map to drum sounds in GM:
            //   36 = Kick, 38 = Snare, 42 = Closed Hi-Hat, 49 = Crash
            val drumMidi = when (trackIdx) {
                7  -> listOf(36) // Kick
                8  -> listOf(38) // Snare
                9  -> listOf(42) // Hi-Hat (closed)
                10 -> listOf(49) // Crash cymbal
                else -> return
            }
            // Repeat the hit for each chord duration so user hears the pattern
            lifecycleScope.launch {
                val tracks2 = currentTracks ?: return@launch
                val chordCount = currentChords.size
                repeat(chordCount) { ci ->
                    trackRolls.getOrNull(trackIdx)?.highlightChord(ci)
                    // Play the hit multiple times within each chord (simulating the pattern)
                    val hitsPerChord = when (trackIdx) {
                        9  -> 4  // Hi-Hat plays more frequently
                        else -> 2
                    }
                    repeat(hitsPerChord) {
                        PianoSynth.playChord(drumMidi, durationMs = 200)
                        kotlinx.coroutines.delay((1700L / hitsPerChord))
                    }
                }
                trackRolls.getOrNull(trackIdx)?.highlightChord(-1)
            }
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

                // Add kick and snare to the mix for full band feel
                allNotes.add(36)  // Kick
                allNotes.add(38)  // Snare

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
        val lhNotes = tracks.piano.map { p -> p.substringAfter("LH:").substringBefore(" ").split("+").firstOrNull() ?: "C3" }
        val rhNotes = tracks.piano.map { p -> p.substringAfter("RH:").split("+").firstOrNull() ?: "E4" }

        fun classifyPercLine(line: String): String {
            val l = line.lowercase()
            return when {
                l.startsWith("kick") || l.contains("4-on-floor") -> "kick"
                l.startsWith("snare") || l.startsWith("clap") || l.contains("snare/clap") -> "snare"
                l.contains("hi-hat") || l.contains("hi hat") || l.contains("shuffle") ||
                        l.contains("ride") || l.contains("8ths") || l.contains("16ths") -> "hihat"
                else -> "crash"
            }
        }
        val kickLines  = tracks.percussion.filter { classifyPercLine(it) == "kick" }
        val snareLines = tracks.percussion.filter { classifyPercLine(it) == "snare" }
        val hihatLines = tracks.percussion.filter { classifyPercLine(it) == "hihat" }
        val crashLines = tracks.percussion.filter { classifyPercLine(it) == "crash" }

        // 11 track value lists — 4 values each (one per chord slot)
        val allValues = listOf(
            tracks.bass,
            tracks.rhythmGuitar,
            lhNotes,
            rhNotes,
            tracks.pads,
            tracks.leadMelody,
            tracks.counterMelody,
            List(4) { kickLines.joinToString(", ").ifEmpty { "—" } },
            List(4) { snareLines.joinToString(", ").ifEmpty { "—" } },
            List(4) { hihatLines.joinToString(", ").ifEmpty { "—" } },
            List(4) { crashLines.joinToString(", ").ifEmpty { "—" } }
        )

        // Fill each track's 4 slot cards
        val tvList = listOf(tvBass, tvRhythmGuitar, tvPianoLH, tvPianoRH, tvPads,
            tvLead, tvCounter, tvKick, tvSnare, tvHiHat, tvCrash)
        tvList.forEachIndexed { i, tv ->
            val values = allValues.getOrElse(i) { emptyList() }
            @Suppress("UNCHECKED_CAST")
            val slots = tv.tag as? MutableList<TextView>
            slots?.forEachIndexed { s, slotTv ->
                slotTv.text  = values.getOrElse(s) { "—" }
                slotTv.alpha = 1f
            }
        }

        // Feed mini rolls (pitched tracks only)
        val allTrackNotes: List<List<String>> = listOf(
            tracks.bass, tracks.rhythmGuitar,
            lhNotes, rhNotes, tracks.pads,
            tracks.leadMelody, tracks.counterMelody,
            emptyList(), emptyList(), emptyList(), emptyList()
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
//  TrackMiniRollView — full App 1 style piano roll per instrument track
// ─────────────────────────────────────────────────────────────────────────────
class TrackMiniRollView(
    context: android.content.Context,
    private val accentColor: Int
) : android.view.View(context) {

    private var noteNames   : List<String> = emptyList()
    private var engine      : ChordEngine? = null
    private var activeChord : Int          = -1

    private val keyLabels  = listOf("C5","","B4","","A4","","G4","F4","","E4","","D4","","C4")
    private val keyIsBlack = listOf(false,true,false,true,false,true,false,false,true,false,true,false,true,false)
    private val midiToRow  = mapOf(
        60 to 0, 59 to 2, 58 to 3, 57 to 4, 56 to 5, 55 to 6,
        53 to 7, 52 to 9, 51 to 10, 50 to 11, 49 to 12, 48 to 13
    )

    private val KEY_W     = 44f
    private val ROWS      = 14

    private val bgPaint   = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val notePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.FILL }
    private val linePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE }
    private val txtPaint  = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#333352")
        textAlign = android.graphics.Paint.Align.LEFT
    }
    private val lblPaint  = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = android.graphics.Paint.Align.LEFT
    }
    private val phPaint   = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = C.ORANGE
    }

    fun setNotes(names: List<String>, eng: ChordEngine?) {
        noteNames = names; engine = eng; invalidate()
    }

    fun highlightChord(idx: Int) { activeChord = idx; invalidate() }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val w    = width.toFloat()
        val h    = height.toFloat()
        val gridW = w - KEY_W
        val rowH  = h / ROWS

        // Key label font size proportional to row height
        val fontSize = (rowH * 0.65f).coerceIn(10f, 18f)
        txtPaint.textSize = fontSize

        // ── Row backgrounds ───────────────────────────────────────────────────
        keyLabels.forEachIndexed { i, _ ->
            bgPaint.color = if (keyIsBlack[i]) android.graphics.Color.parseColor("#0D0D18")
            else android.graphics.Color.parseColor("#111120")
            canvas.drawRect(0f, i * rowH, w, (i+1) * rowH, bgPaint)
        }

        // ── Key / grid separator ──────────────────────────────────────────────
        linePaint.color = android.graphics.Color.parseColor("#363650")
        linePaint.strokeWidth = 1.5f
        canvas.drawLine(KEY_W, 0f, KEY_W, h, linePaint)

        // ── Column dividers + sub-beat lines ──────────────────────────────────
        if (noteNames.isNotEmpty()) {
            val slotW = gridW / noteNames.size
            for (ci in 0 until noteNames.size) {
                // Chord column border
                if (ci > 0) {
                    linePaint.color = android.graphics.Color.parseColor("#363650")
                    linePaint.strokeWidth = 1.5f
                    canvas.drawLine(KEY_W + ci * slotW, 0f, KEY_W + ci * slotW, h, linePaint)
                }
                // Sub-beat lines (4 per chord)
                linePaint.color = android.graphics.Color.parseColor("#1E1E2E")
                linePaint.strokeWidth = 0.5f
                for (b in 1..3) {
                    val x = KEY_W + ci * slotW + slotW * b / 4f
                    canvas.drawLine(x, 0f, x, h, linePaint)
                }
            }
        }

        // ── Row dividers on key side ──────────────────────────────────────────
        linePaint.color = android.graphics.Color.parseColor("#1A1A2A")
        linePaint.strokeWidth = 0.5f
        keyLabels.indices.forEach { i ->
            canvas.drawLine(0f, i * rowH, KEY_W, i * rowH, linePaint)
        }

        // ── Key labels ────────────────────────────────────────────────────────
        keyLabels.forEachIndexed { i, label ->
            if (label.isNotEmpty()) {
                canvas.drawText(label, 3f, i * rowH + rowH * 0.72f, txtPaint)
            }
        }

        // ── Chord name labels at top ──────────────────────────────────────────
        if (noteNames.isNotEmpty()) {
            val slotW = gridW / noteNames.size
            lblPaint.textSize = (fontSize * 0.85f).coerceIn(9f, 16f)
            lblPaint.color = accentColor
            noteNames.forEachIndexed { ci, name ->
                canvas.drawText(name, KEY_W + ci * slotW + 4f, rowH * 0.85f, lblPaint)
            }
        }

        // ── Notes ─────────────────────────────────────────────────────────────
        if (noteNames.isEmpty()) {
            // Ghost placeholder
            notePaint.color = android.graphics.Color.parseColor("#222238")
            notePaint.alpha = 80
            val pw = gridW / 4f * 0.82f
            listOf(9 to 0, 5 to 0, 13 to 1, 6 to 1, 7 to 2, 9 to 2, 4 to 3, 11 to 3).forEach { (row, slot) ->
                val x = KEY_W + slot * (gridW / 4f) + 4f
                val y = row * rowH + 1f
                canvas.drawRoundRect(x, y, x + pw, y + rowH - 2f, 3f, 3f, notePaint)
            }
            notePaint.alpha = 255
            return
        }

        val slotW = gridW / noteNames.size
        noteNames.forEachIndexed { ci, noteName ->
            val isActive = ci == activeChord
            val nx = KEY_W + ci * slotW + 3f
            val nw = slotW * 0.84f

            // Get MIDI notes — try chord engine first, then parse as note name
            val midiList: List<Int> = engine?.notesForChord(noteName)
                ?.filter { it in 48..60 }
                ?.takeIf { it.isNotEmpty() }
                ?: MidiExporter.noteNameToMidi(noteName.trim())
                    ?.takeIf { it in 48..60 }
                    ?.let { listOf(it) }
                ?: listOf(53) // fallback: F4

            val displayRows = midiList.mapNotNull { midiToRow[it] }
                .ifEmpty { listOf(6) }

            displayRows.forEachIndexed { ni, row ->
                notePaint.color = accentColor
                notePaint.alpha = when {
                    isActive && ni == 0 -> 255
                    isActive            -> 160
                    ni == 0             -> 220
                    else                -> 130
                }
                val y = row * rowH + 1f
                canvas.drawRoundRect(nx, y, nx + nw, y + rowH - 2f, 3f, 3f, notePaint)

                // Subtle highlight on top of note
                if (ni == 0) {
                    notePaint.color = android.graphics.Color.WHITE
                    notePaint.alpha = 40
                    canvas.drawRoundRect(nx, y, nx + nw, y + rowH * 0.3f, 3f, 3f, notePaint)
                }
            }
        }
        notePaint.alpha = 255

        // ── Playhead ──────────────────────────────────────────────────────────
        if (activeChord >= 0 && noteNames.isNotEmpty()) {
            val slotW2 = gridW / noteNames.size
            val px = KEY_W + activeChord * slotW2 + slotW2 * 0.5f
            phPaint.style = android.graphics.Paint.Style.STROKE
            phPaint.strokeWidth = 2.5f
            canvas.drawLine(px, 0f, px, h, phPaint)
            phPaint.style = android.graphics.Paint.Style.FILL
            canvas.drawPath(android.graphics.Path().apply {
                moveTo(px - 6f, 0f); lineTo(px + 6f, 0f); lineTo(px, 10f); close()
            }, phPaint)
        }
    }
}