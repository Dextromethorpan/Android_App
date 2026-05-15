package com.luciano.chordseq

// ═════════════════════════════════════════════════════════════════════════════
//  SoundFontPlayer.kt — SF2-based Instrument Playback
//  ChordsPro · Luciano Muratore
//
//  Plays MIDI notes using SoundFont (.sf2) files stored in assets/.
//  Falls back to PianoSynth (Karplus-Strong) for tracks with no SF2.
//
//  SF2 → Track mapping:
//    Bass           → Slap Bass.sf2
//    Rhythm Guitar  → FluidSynth GM fallback (no guitar SF2 available)
//    Piano LH       → Electric Piano.sf2
//    Piano RH       → Electric Piano.sf2
//    Pads/Strings   → Mellopad.sf2
//    Lead Melody    → Tenor Saxophone.sf2
//    Countermelody  → Trombone.sf2
//    Kick           → Mystic Bass Synth.sf2
//    Snare          → Sawtooth Pad.sf2
//    Hi-Hat         → Theremin.sf2
//    Crash          → Oohs.sf2
//
//  Playback: polyphonic — all chord notes play simultaneously.
//
//  Architecture:
//    - SF2Parser extracts PCM samples for each note from the .sf2 file
//    - SoundFontPlayer mixes all notes and plays via AudioTrack
//    - Falls back to PianoSynth if SF2 loading fails
//
//  Usage:
//    SoundFontPlayer.init(context)
//    SoundFontPlayer.playTrack(trackIndex, midiNotes, durationMs)
// ═════════════════════════════════════════════════════════════════════════════

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

object SoundFontPlayer {

    private const val TAG         = "SoundFontPlayer"
    private const val SAMPLE_RATE = 44100
    private const val DURATION_MS = 1500

    // Track index → SF2 asset filename (null = FluidSynth/PianoSynth fallback)
    private val TRACK_SF2 = mapOf(
        0  to "Slap Bass.sf2",
        1  to "Filtered Synth Bass.sf2", // Rhythm Guitar
        2  to "Electric Piano.sf2",      // Piano LH
        3  to "Electric Piano.sf2",      // Piano RH
        4  to "Mellopad.sf2",
        5  to "Tenor Saxophone.sf2",
        6  to "Trombone.sf2",
        7  to "Mystic Bass Synth.sf2",   // Kick
        8  to "Sawtooth Pad.sf2",        // Snare
        9  to "Theremin.sf2",            // Hi-Hat
        10 to "Oohs.sf2"                 // Crash
    )

    // Cached SF2 sample data: filename → (rootNote → PCM floats)
    private val sampleCache = mutableMapOf<String, Map<Int, FloatArray>>()
    private var context: Context? = null
    private var isInitialised = false

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Pre-load all SF2 files from assets. Call once on a background thread.
     */
    fun init(ctx: Context) {
        if (isInitialised) return
        context = ctx.applicationContext
        Log.d(TAG, "Loading SoundFont files…")
        val t0 = System.currentTimeMillis()

        TRACK_SF2.values.filterNotNull().distinct().forEach { filename ->
            try {
                val samples = SF2Parser.load(ctx, filename)
                if (samples.isNotEmpty()) {
                    sampleCache[filename] = samples
                    Log.d(TAG, "Loaded $filename — ${samples.size} samples")
                } else {
                    Log.w(TAG, "$filename parsed but no samples found — will use fallback")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load $filename: ${e.message} — will use fallback")
            }
        }

        isInitialised = true
        Log.d(TAG, "SoundFont init done in ${System.currentTimeMillis() - t0}ms")
    }

    /**
     * Play a set of MIDI notes for a given track.
     * All notes play simultaneously (polyphonic).
     *
     * @param trackIndex  0–10 matching ChordDeriver track order
     * @param midiNotes   list of MIDI note numbers to play
     * @param durationMs  how long to sustain
     */
    fun playTrack(trackIndex: Int, midiNotes: List<Int>, durationMs: Int = DURATION_MS) {
        if (midiNotes.isEmpty()) return
        val filename = TRACK_SF2[trackIndex]

        Thread {
            if (filename != null && sampleCache.containsKey(filename)) {
                playSF2Notes(filename, midiNotes, durationMs)
            } else {
                // Fallback to PianoSynth
                PianoSynth.playChord(midiNotes, durationMs)
            }
        }.start()
    }

    // ── SF2 playback ──────────────────────────────────────────────────────────

    private fun playSF2Notes(filename: String, midiNotes: List<Int>, durationMs: Int) {
        try {
            val samples  = sampleCache[filename] ?: return
            val numSamples = (SAMPLE_RATE * durationMs / 1000).coerceAtMost(SAMPLE_RATE * 3)
            val mixed    = FloatArray(numSamples)
            val scale    = 1f / midiNotes.size.coerceAtLeast(1)

            for (midi in midiNotes) {
                // Find closest sample (SF2 may not have every MIDI note)
                val buf = findClosestSample(samples, midi, numSamples) ?: continue
                for (i in 0 until numSamples.coerceAtMost(buf.size)) {
                    mixed[i] += buf[i] * scale
                }
            }

            // Release envelope
            val relSamples = (SAMPLE_RATE * 0.08).toInt()
            for (i in 0 until relSamples.coerceAtMost(numSamples)) {
                val pos = numSamples - relSamples + i
                if (pos < numSamples) mixed[pos] *= 1f - i.toFloat() / relSamples
            }

            playPCM(mixed, numSamples, durationMs)
        } catch (e: Exception) {
            Log.e(TAG, "SF2 playback failed for $filename", e)
            PianoSynth.playChord(midiNotes, durationMs)
        }
    }

    private fun findClosestSample(
        samples: Map<Int, FloatArray>,
        targetMidi: Int,
        numSamples: Int
    ): FloatArray? {
        if (samples.isEmpty()) return null

        // Find closest root note
        val closest = samples.keys.minByOrNull { abs(it - targetMidi) } ?: return null
        val original = samples[closest] ?: return null

        // Pitch-shift by resampling if needed
        val semitones = targetMidi - closest
        if (semitones == 0) {
            return original.copyOf(numSamples.coerceAtMost(original.size))
        }

        // Simple pitch shift via playback rate
        val ratio = Math.pow(2.0, semitones / 12.0).toFloat()
        val result = FloatArray(numSamples)
        for (i in 0 until numSamples) {
            val srcIdx = (i * ratio).toInt()
            if (srcIdx < original.size) result[i] = original[srcIdx]
        }
        return result
    }

    private fun playPCM(mixed: FloatArray, numSamples: Int, durationMs: Int) {
        val pcm = ShortArray(numSamples) { i ->
            (mixed[i] * Short.MAX_VALUE)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }

        val minBuf  = AudioTrack.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufBytes = (pcm.size * 2).coerceAtLeast(minBuf)

        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
            .build()

        track.write(pcm, 0, pcm.size)
        track.play()
        Thread.sleep(durationMs.toLong() + 50)
        track.stop()
        track.release()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  SF2Parser — reads SoundFont 2.0 format and extracts PCM samples
//
//  SF2 structure:
//    RIFF chunk
//      INFO sub-chunk  (metadata)
//      sdta sub-chunk  (sample data)
//        smpl chunk    (16-bit PCM samples, interleaved)
//        sm24 chunk    (optional 24-bit extension)
//      pdta sub-chunk  (preset/instrument/sample headers)
//        phdr          (preset headers)
//        pbag          (preset index list)
//        pmod          (preset modulators)
//        pgen          (preset generators)
//        inst          (instrument headers)
//        ibag          (instrument index list)
//        imod          (instrument modulators)
//        igen          (instrument generators — contains keyRange, velRange, sampleID)
//        shdr          (sample headers — contains start, end, loopStart, loopEnd, pitch)
//
//  We parse shdr to find sample boundaries and root keys,
//  then extract PCM from the smpl chunk.
// ─────────────────────────────────────────────────────────────────────────────
object SF2Parser {

    private const val TAG = "SF2Parser"

    data class SampleHeader(
        val name      : String,
        val start     : Int,    // sample start offset (in samples, not bytes)
        val end       : Int,
        val loopStart : Int,
        val loopEnd   : Int,
        val sampleRate: Int,
        val rootKey   : Int,    // MIDI note number
        val type      : Int     // 1=mono, 2=right, 4=left, 8=linked, 32768=ROM
    )

    /**
     * Load an SF2 file from assets and return a map of rootKey → FloatArray (PCM).
     * Only loads mono samples to keep memory usage low.
     */
    fun load(context: Context, filename: String): Map<Int, FloatArray> {
        context.assets.open(filename).use { stream ->
            val bytes = stream.readBytes()
            return parse(bytes)
        }
    }

    private fun parse(data: ByteArray): Map<Int, FloatArray> {
        if (data.size < 12) return emptyMap()

        // Verify RIFF header
        val riff = String(data, 0, 4)
        val sfbk = String(data, 8, 4)
        if (riff != "RIFF" || sfbk != "sfbk") {
            Log.w(TAG, "Not a valid SF2 file")
            return emptyMap()
        }

        var pos = 12
        var smplData: ByteArray? = null
        val sampleHeaders = mutableListOf<SampleHeader>()

        // Iterate RIFF chunks
        while (pos < data.size - 8) {
            val chunkId   = String(data, pos, 4)
            val chunkSize = readInt32LE(data, pos + 4)
            val chunkStart = pos + 8

            when (chunkId) {
                "LIST" -> {
                    val listType = String(data, chunkStart, 4)
                    when (listType) {
                        "sdta" -> smplData = parseSmpl(data, chunkStart + 4, chunkSize - 4)
                        "pdta" -> parsePdta(data, chunkStart + 4, chunkSize - 4, sampleHeaders)
                    }
                }
            }
            pos = chunkStart + chunkSize
            if (pos % 2 != 0) pos++ // RIFF chunks are word-aligned
        }

        if (smplData == null || sampleHeaders.isEmpty()) return emptyMap()

        // Build rootKey → PCM map (mono samples only, skip terminal record)
        val result = mutableMapOf<Int, FloatArray>()
        for (hdr in sampleHeaders) {
            if (hdr.type and 0x8000 != 0) continue  // skip ROM samples
            if (hdr.type == 0) continue              // skip terminal
            if (hdr.end <= hdr.start) continue
            if (hdr.rootKey < 0 || hdr.rootKey > 127) continue
            if (result.containsKey(hdr.rootKey)) continue  // keep first

            val numSamples = (hdr.end - hdr.start).coerceAtMost(smplData.size / 2 - hdr.start)
            if (numSamples <= 0) continue

            val pcm = FloatArray(numSamples)
            for (i in 0 until numSamples) {
                val bytePos = (hdr.start + i) * 2
                if (bytePos + 1 >= smplData.size) break
                val sample = (smplData[bytePos + 1].toInt() shl 8) or
                        (smplData[bytePos].toInt() and 0xFF)
                pcm[i] = sample.toShort() / 32768f
            }
            result[hdr.rootKey] = pcm
        }

        Log.d(TAG, "SF2 parsed: ${result.size} samples extracted")
        return result
    }

    private fun parseSmpl(data: ByteArray, offset: Int, size: Int): ByteArray? {
        var pos = offset
        while (pos < offset + size - 8) {
            val id        = String(data, pos, 4)
            val chunkSize = readInt32LE(data, pos + 4)
            if (id == "smpl") {
                return data.copyOfRange(pos + 8, (pos + 8 + chunkSize).coerceAtMost(data.size))
            }
            pos += 8 + chunkSize
            if (pos % 2 != 0) pos++
        }
        return null
    }

    private fun parsePdta(
        data: ByteArray, offset: Int, size: Int,
        headers: MutableList<SampleHeader>
    ) {
        var pos = offset
        while (pos < offset + size - 8) {
            val id        = String(data, pos, 4)
            val chunkSize = readInt32LE(data, pos + 4)
            val dataStart = pos + 8

            if (id == "shdr") {
                // Each shdr record is 46 bytes
                val count = chunkSize / 46
                for (i in 0 until count) {
                    val base = dataStart + i * 46
                    if (base + 46 > data.size) break
                    val name       = String(data, base, 20).trimEnd('\u0000')
                    val start      = readInt32LE(data, base + 20)
                    val end        = readInt32LE(data, base + 24)
                    val loopStart  = readInt32LE(data, base + 28)
                    val loopEnd    = readInt32LE(data, base + 32)
                    val sampleRate = readInt32LE(data, base + 36)
                    val rootKey    = data[base + 40].toInt() and 0xFF
                    val type       = readInt16LE(data, base + 44)
                    headers.add(SampleHeader(name, start, end, loopStart, loopEnd,
                        sampleRate, rootKey, type))
                }
            }

            pos += 8 + chunkSize
            if (pos % 2 != 0) pos++
        }
    }

    private fun readInt32LE(data: ByteArray, offset: Int): Int {
        if (offset + 3 >= data.size) return 0
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readInt16LE(data: ByteArray, offset: Int): Int {
        if (offset + 1 >= data.size) return 0
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8)
    }
}