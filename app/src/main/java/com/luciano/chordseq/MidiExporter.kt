package com.luciano.chordseq

// ═════════════════════════════════════════════════════════════════════════════
//  MidiExporter.kt — DAW Migration: MIDI File Export
//  ChordsPro · Luciano Muratore
//
//  Generates a standard MIDI file (Format 1) from a ChordDeriver.TrackResult.
//  No external libraries — pure Kotlin byte array construction.
//
//  Structure:
//    Track 0  — Tempo + time signature (meta track)
//    Track 1  — Bass
//    Track 2  — Rhythm Guitar
//    Track 3  — Piano (left hand + right hand combined)
//    Track 4  — Pads / Strings
//    Track 5  — Lead Melody
//    Track 6  — Countermelody
//    Track 7  — Percussion (channel 10, GM standard)
//
//  User selects bars per chord: 1, 2, or 4.
//  BPM is taken from the app's current tempo.
//
//  Usage:
//    val midi = MidiExporter.export(
//        tracks       = chordDeriverResult,
//        chords       = listOf("Cmaj7","Am7","Dm7","G7"),
//        bpm          = 120,
//        barsPerChord = 2
//    )
//    File(path, "progression.mid").writeBytes(midi)
// ═════════════════════════════════════════════════════════════════════════════

import java.io.ByteArrayOutputStream

object MidiExporter {

    // ── MIDI constants ────────────────────────────────────────────────────────
    private const val TICKS_PER_BEAT = 480          // standard resolution
    private const val BEATS_PER_BAR  = 4

    // GM channel assignments
    private const val CH_BASS    = 1
    private const val CH_GUITAR  = 2
    private const val CH_PIANO   = 3
    private const val CH_PADS    = 4
    private const val CH_LEAD    = 5
    private const val CH_COUNTER = 6
    private const val CH_PERC    = 9   // channel 10 (0-indexed = 9), GM drums

    // GM program numbers (0-indexed)
    private const val PROG_BASS    = 32   // Acoustic Bass
    private const val PROG_GUITAR  = 25   // Acoustic Guitar (steel)
    private const val PROG_PIANO   = 0    // Acoustic Grand Piano
    private const val PROG_PADS    = 89   // Pad 2 (warm)
    private const val PROG_LEAD    = 73   // Flute (neutral lead)
    private const val PROG_COUNTER = 40   // Violin

    // GM drum notes
    private const val KICK   = 36
    private const val SNARE  = 38
    private const val HIHAT  = 42
    private const val CRASH  = 49
    private const val CLAP   = 39
    private const val RIM    = 37

    private val NOTE_NAMES = listOf("C","C#","D","D#","E","F","F#","G","G#","A","A#","B")

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Export a full 7-track MIDI file as a ByteArray.
     *
     * @param tracks       ChordDeriver output
     * @param chords       original 4 chord names (for note lookup)
     * @param bpm          tempo in beats per minute
     * @param barsPerChord how many bars each chord lasts (1, 2, or 4)
     */
    fun export(
        tracks       : ChordDeriver.TrackResult,
        chords       : List<String>,
        bpm          : Int = 120,
        barsPerChord : Int = 2
    ): ByteArray {
        val ticksPerChord = TICKS_PER_BEAT * BEATS_PER_BAR * barsPerChord
        val out = ByteArrayOutputStream()

        // MIDI header — Format 1, 8 tracks (1 meta + 7 instrument)
        val numTracks = 8
        out.write(midiHeader(numTracks, TICKS_PER_BEAT))

        // Track 0 — Tempo & time signature
        out.write(buildMetaTrack(bpm))

        // Track 1 — Bass
        out.write(buildNoteTrack(
            name      = "Bass",
            channel   = CH_BASS,
            program   = PROG_BASS,
            noteNames = tracks.bass,
            ticksPerChord = ticksPerChord,
            velocity  = 90,
            noteLen   = (ticksPerChord * 0.9).toInt()
        ))

        // Track 2 — Rhythm Guitar (chord voicing → arpeggiate slightly)
        out.write(buildChordTrack(
            name      = "Rhythm Guitar",
            channel   = CH_GUITAR,
            program   = PROG_GUITAR,
            chordNames = tracks.rhythmGuitar,
            ticksPerChord = ticksPerChord,
            velocity  = 80,
            strum     = true
        ))

        // Track 3 — Piano (LH + RH parsed from "LH:X3+Y3  RH:A4+B4")
        out.write(buildPianoTrack(
            pianoStrings  = tracks.piano,
            ticksPerChord = ticksPerChord
        ))

        // Track 4 — Pads (sustained whole chord)
        out.write(buildChordTrack(
            name      = "Pads",
            channel   = CH_PADS,
            program   = PROG_PADS,
            chordNames = tracks.pads,
            ticksPerChord = ticksPerChord,
            velocity  = 65,
            strum     = false
        ))

        // Track 5 — Lead Melody
        out.write(buildNoteTrack(
            name      = "Lead Melody",
            channel   = CH_LEAD,
            program   = PROG_LEAD,
            noteNames = tracks.leadMelody,
            ticksPerChord = ticksPerChord,
            velocity  = 85,
            noteLen   = (ticksPerChord * 0.85).toInt()
        ))

        // Track 6 — Countermelody
        out.write(buildNoteTrack(
            name      = "Countermelody",
            channel   = CH_COUNTER,
            program   = PROG_COUNTER,
            noteNames = tracks.counterMelody,
            ticksPerChord = ticksPerChord,
            velocity  = 75,
            noteLen   = (ticksPerChord * 0.8).toInt()
        ))

        // Track 7 — Percussion
        out.write(buildPercussionTrack(
            patterns      = tracks.percussion,
            chordCount    = chords.size,
            ticksPerChord = ticksPerChord
        ))

        return out.toByteArray()
    }

    // ── Track builders ────────────────────────────────────────────────────────

    /** Meta track: tempo + time signature */
    private fun buildMetaTrack(bpm: Int): ByteArray {
        val events = ByteArrayOutputStream()
        // Time signature: 4/4
        events.write(varLen(0))
        events.write(byteArrayOf(0xFF.toByte(), 0x58, 0x04, 0x04, 0x02, 0x18, 0x08))
        // Tempo
        val usPerBeat = 60_000_000 / bpm
        events.write(varLen(0))
        events.write(byteArrayOf(0xFF.toByte(), 0x51, 0x03))
        events.write(byteArrayOf(
            ((usPerBeat shr 16) and 0xFF).toByte(),
            ((usPerBeat shr 8)  and 0xFF).toByte(),
            (usPerBeat          and 0xFF).toByte()
        ))
        // Track name
        events.write(metaText(0, 0x03, "ChordsPro"))
        // End of track
        events.write(varLen(0)); events.write(byteArrayOf(0xFF.toByte(), 0x2F, 0x00))
        return wrapTrack(events.toByteArray())
    }

    /** Single-note track (bass, lead, counter) */
    private fun buildNoteTrack(
        name          : String,
        channel       : Int,
        program       : Int,
        noteNames     : List<String>,
        ticksPerChord : Int,
        velocity      : Int,
        noteLen       : Int
    ): ByteArray {
        val events = ByteArrayOutputStream()
        events.write(metaText(0, 0x03, name))
        events.write(programChange(0, channel, program))

        var tick = 0
        noteNames.forEach { noteName ->
            val midi = noteNameToMidi(noteName) ?: 60
            events.write(noteOn(tick, channel, midi, velocity))
            events.write(noteOff(noteLen, channel, midi))
            tick = ticksPerChord - noteLen   // delta to next chord start
        }
        // pad remaining ticks if tick > 0
        if (tick > 0) events.write(varLen(tick))
        events.write(varLen(0)); events.write(byteArrayOf(0xFF.toByte(), 0x2F, 0x00))
        return wrapTrack(events.toByteArray())
    }

    /** Chord track (guitar, pads) — multiple notes simultaneously or strummed */
    private fun buildChordTrack(
        name          : String,
        channel       : Int,
        program       : Int,
        chordNames    : List<String>,
        ticksPerChord : Int,
        velocity      : Int,
        strum         : Boolean
    ): ByteArray {
        val events = ByteArrayOutputStream()
        events.write(metaText(0, 0x03, name))
        events.write(programChange(0, channel, program))

        val noteLen = (ticksPerChord * 0.88).toInt()
        val strumDelay = if (strum) 20 else 0

        chordNames.forEach { chordName ->
            val notes = chordNameToMidiNotes(chordName, octave = 4)
            var firstDelta = 0
            notes.forEachIndexed { i, midi ->
                val delta = if (i == 0) firstDelta else strumDelay
                events.write(noteOn(delta, channel, midi, velocity - i * 3))
                firstDelta = 0
            }
            // Note offs
            notes.forEachIndexed { i, midi ->
                val delta = if (i == 0) noteLen else strumDelay
                events.write(noteOff(delta, channel, midi))
            }
            // Gap to next chord
            val gap = ticksPerChord - noteLen - strumDelay * notes.size
            if (gap > 0) { events.write(varLen(gap)); events.write(byteArrayOf(0x00)) }
        }
        events.write(varLen(0)); events.write(byteArrayOf(0xFF.toByte(), 0x2F, 0x00))
        return wrapTrack(events.toByteArray())
    }

    /** Piano track — parses "LH:C3+G3  RH:E4+B4" strings */
    private fun buildPianoTrack(
        pianoStrings  : List<String>,
        ticksPerChord : Int
    ): ByteArray {
        val events = ByteArrayOutputStream()
        events.write(metaText(0, 0x03, "Piano"))
        events.write(programChange(0, CH_PIANO, PROG_PIANO))

        val noteLen = (ticksPerChord * 0.92).toInt()

        pianoStrings.forEach { str ->
            // Parse "LH:C3+G3  RH:E4+B4"
            val lhPart = Regex("LH:([\\w+]+)").find(str)?.groupValues?.get(1) ?: ""
            val rhPart = Regex("RH:([\\w+]+)").find(str)?.groupValues?.get(1) ?: ""
            val lhNotes = lhPart.split("+").mapNotNull { noteNameToMidi(it.trim()) }
            val rhNotes = rhPart.split("+").mapNotNull { noteNameToMidi(it.trim()) }
            val allNotes = lhNotes + rhNotes

            // Note ons at delta 0
            allNotes.forEachIndexed { i, midi ->
                events.write(noteOn(if (i == 0) 0 else 0, CH_PIANO, midi, if (midi < 60) 85 else 75))
            }
            // Note offs
            allNotes.forEachIndexed { i, midi ->
                events.write(noteOff(if (i == 0) noteLen else 0, CH_PIANO, midi))
            }
            val gap = ticksPerChord - noteLen
            if (gap > 0) { events.write(varLen(gap)); events.write(byteArrayOf(0x00)) }
        }
        events.write(varLen(0)); events.write(byteArrayOf(0xFF.toByte(), 0x2F, 0x00))
        return wrapTrack(events.toByteArray())
    }

    /** Percussion track — maps pattern descriptions to GM drum hits */
    private fun buildPercussionTrack(
        patterns      : List<String>,
        chordCount    : Int,
        ticksPerChord : Int
    ): ByteArray {
        val events = ByteArrayOutputStream()
        events.write(metaText(0, 0x03, "Percussion"))

        val beatTick  = TICKS_PER_BEAT
        val sixteenth = TICKS_PER_BEAT / 4

        // Parse pattern descriptions into a simple beat map
        val hasKick16  = patterns.any { it.contains("1 & 3") }
        val hasSnare24 = patterns.any { it.contains("2 & 4") }
        val hasHat16th = patterns.any { it.contains("16ths") }
        val hasHat8th  = patterns.any { it.contains("8ths") }
        val hasShuffle = patterns.any { it.contains("shuffle", ignoreCase = true) }
        val hasClap    = patterns.any { it.contains("Clap") }
        val hasRide    = patterns.any { it.contains("Ride") }
        val is4Floor   = patterns.any { it.contains("4-on-floor") }

        // Build one bar of drum events, repeat for each chord × bars
        val barEvents = mutableListOf<Pair<Int, Int>>() // (tick, note)

        if (is4Floor) {
            listOf(0, beatTick, beatTick*2, beatTick*3).forEach { barEvents.add(it to KICK) }
        } else if (hasKick16) {
            listOf(0, beatTick*2).forEach { barEvents.add(it to KICK) }
        }

        if (hasSnare24) {
            listOf(beatTick, beatTick*3).forEach {
                barEvents.add(it to if (hasClap) CLAP else SNARE)
            }
        }

        if (hasHat16th) {
            (0 until 16).forEach { barEvents.add(it * sixteenth to HIHAT) }
        } else if (hasHat8th || hasShuffle) {
            (0 until 8).forEach { barEvents.add(it * sixteenth * 2 to HIHAT) }
        } else if (hasRide) {
            (0 until 4).forEach { barEvents.add(it * beatTick to 51) } // ride cymbal
        }

        // Write repeated bar for each chord
        var globalTick = 0
        repeat(chordCount) {
            val barsThisChord = ticksPerChord / (TICKS_PER_BEAT * BEATS_PER_BAR)
            repeat(barsThisChord) {
                val sorted = barEvents.sortedBy { it.first }
                var prevTick = 0
                sorted.forEach { (tick, note) ->
                    val delta = tick - prevTick
                    events.write(noteOn(delta, CH_PERC, note, 90))
                    events.write(noteOff(sixteenth / 2, CH_PERC, note))
                    prevTick = tick + sixteenth / 2
                }
                globalTick += TICKS_PER_BEAT * BEATS_PER_BAR
            }
        }

        events.write(varLen(0)); events.write(byteArrayOf(0xFF.toByte(), 0x2F, 0x00))
        return wrapTrack(events.toByteArray())
    }

    // ── MIDI byte helpers ─────────────────────────────────────────────────────

    private fun midiHeader(numTracks: Int, ticksPerBeat: Int): ByteArray {
        return byteArrayOf(
            0x4D, 0x54, 0x68, 0x64,               // "MThd"
            0x00, 0x00, 0x00, 0x06,               // chunk length = 6
            0x00, 0x01,                           // format 1
            ((numTracks shr 8) and 0xFF).toByte(),
            (numTracks and 0xFF).toByte(),
            ((ticksPerBeat shr 8) and 0xFF).toByte(),
            (ticksPerBeat and 0xFF).toByte()
        )
    }

    private fun wrapTrack(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x4D, 0x54, 0x72, 0x6B))  // "MTrk"
        val len = data.size
        out.write(byteArrayOf(
            ((len shr 24) and 0xFF).toByte(),
            ((len shr 16) and 0xFF).toByte(),
            ((len shr 8)  and 0xFF).toByte(),
            (len and 0xFF).toByte()
        ))
        out.write(data)
        return out.toByteArray()
    }

    private fun programChange(delta: Int, channel: Int, program: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(varLen(delta))
        out.write(byteArrayOf((0xC0 or (channel - 1)).toByte(), program.toByte()))
        return out.toByteArray()
    }

    private fun noteOn(delta: Int, channel: Int, note: Int, velocity: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(varLen(delta))
        out.write(byteArrayOf(
            (0x90 or (channel - 1)).toByte(),
            note.coerceIn(0, 127).toByte(),
            velocity.coerceIn(1, 127).toByte()
        ))
        return out.toByteArray()
    }

    private fun noteOff(delta: Int, channel: Int, note: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(varLen(delta))
        out.write(byteArrayOf(
            (0x80 or (channel - 1)).toByte(),
            note.coerceIn(0, 127).toByte(),
            0x00
        ))
        return out.toByteArray()
    }

    private fun metaText(delta: Int, type: Int, text: String): ByteArray {
        val bytes = text.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(varLen(delta))
        out.write(byteArrayOf(0xFF.toByte(), type.toByte()))
        out.write(varLen(bytes.size))
        out.write(bytes)
        return out.toByteArray()
    }

    /** MIDI variable-length encoding */
    private fun varLen(value: Int): ByteArray {
        if (value < 0x80) return byteArrayOf(value.toByte())
        val result = mutableListOf<Byte>()
        var v = value
        result.add(0, (v and 0x7F).toByte())
        v = v shr 7
        while (v > 0) {
            result.add(0, ((v and 0x7F) or 0x80).toByte())
            v = v shr 7
        }
        return result.toByteArray()
    }

    // ── Music theory helpers ──────────────────────────────────────────────────

    /** "A4" → 69,  "C3" → 48,  "F#5" → 78 */
    fun noteNameToMidi(name: String): Int? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val sharp = trimmed.length >= 2 && trimmed[1] == '#'
        val rootEnd = if (sharp) 2 else 1
        val root = trimmed.substring(0, rootEnd)
        val octaveStr = trimmed.substring(rootEnd)
        val octave = octaveStr.toIntOrNull() ?: return null
        val pc = NOTE_NAMES.indexOf(root)
        if (pc < 0) return null
        return (octave + 1) * 12 + pc
    }

    /** "Cmaj7" → [60, 64, 67, 71] in given octave */
    private fun chordNameToMidiNotes(chord: String, octave: Int): List<Int> {
        val root = if (chord.length >= 2 && chord[1] == '#') chord.substring(0, 2)
        else chord.substring(0, 1)
        val rootPc  = NOTE_NAMES.indexOf(root).takeIf { it >= 0 } ?: 0
        val quality = chord.removePrefix(root)
        val rootMidi = (octave + 1) * 12 + rootPc
        val intervals = when {
            quality.startsWith("maj7")  -> listOf(0, 4, 7, 11)
            quality.startsWith("m7b5")  -> listOf(0, 3, 6, 10)
            quality.startsWith("m7")    -> listOf(0, 3, 7, 10)
            quality.startsWith("m9")    -> listOf(0, 3, 7, 10, 14)
            quality.startsWith("maj9")  -> listOf(0, 4, 7, 11, 14)
            quality == "9"              -> listOf(0, 4, 7, 10, 14)
            quality.startsWith("7")     -> listOf(0, 4, 7, 10)
            quality.startsWith("m")     -> listOf(0, 3, 7)
            quality == "5"              -> listOf(0, 7)
            quality.startsWith("add9")  -> listOf(0, 4, 7, 14)
            else                        -> listOf(0, 4, 7)  // major triad
        }
        return intervals.map { rootMidi + it }
    }
}