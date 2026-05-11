package com.luciano.chordseq

// ═════════════════════════════════════════════════════════════════════════════
//  PitchDetector.kt — App 2: Real-time YIN Pitch Detection
//  ChordsPro · Luciano Muratore
//
//  Wraps AudioRecord at 44100Hz and runs YIN pitch detection on each buffer.
//  Reuses the same YINPitchDetector class already in AudioCapture.kt.
//
//  Usage:
//    val detector = PitchDetector(context)
//    detector.startRecording { note -> tvNotes.append("$note ") }
//    val notes = detector.stopRecording()   // returns deduplicated note list
// ═════════════════════════════════════════════════════════════════════════════

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.log2
import kotlin.math.roundToInt

class PitchDetector(private val context: Context) {

    companion object {
        private const val TAG        = "PitchDetector"
        private const val SAMPLE_RATE = 44100
        private const val CONFIDENCE  = 0.85f
    }

    private var audioRecord  : AudioRecord? = null
    private var recordThread : Thread?      = null
    @Volatile private var isRecording = false

    private val _detectedNotes  = mutableListOf<String>()
    private val mainHandler     = Handler(Looper.getMainLooper())

    // ── Public API ────────────────────────────────────────────────────────────

    fun hasPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * Start recording. Calls [onNoteDetected] on the main thread for each
     * detected note. Throws SecurityException if RECORD_AUDIO not granted.
     */
    fun startRecording(onNoteDetected: (String) -> Unit) {
        if (!hasPermission()) throw SecurityException("RECORD_AUDIO permission required")
        if (isRecording) return

        _detectedNotes.clear()
        isRecording = true

        if (AudioCapture.EMULATOR_TEST_MODE) {
            recordThread = Thread { emulatorLoop(onNoteDetected) }.also { it.start() }
            return
        }

        val minBuf  = AudioRecord.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = minBuf * 4

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, bufSize
        )
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            isRecording = false; return
        }
        audioRecord?.startRecording()
        recordThread = Thread { captureLoop(bufSize, onNoteDetected) }.also { it.start() }
        Log.d(TAG, "PitchDetector started")
    }

    /**
     * Stop recording. Returns deduplicated note list (no consecutive repeats).
     */
    fun stopRecording(): List<String> {
        isRecording = false
        audioRecord?.stop(); audioRecord?.release(); audioRecord = null
        recordThread?.join(500); recordThread = null
        Log.d(TAG, "PitchDetector stopped. Notes: $_detectedNotes")
        return deduplicate(_detectedNotes)
    }

    /**
     * Convert frequency to note name e.g. 440f → "A4".
     * Returns null if outside 60–1200 Hz.
     */
    fun freqToNote(freq: Float): String? {
        if (freq < 60f || freq > 1200f) return null
        val names  = listOf("C","C#","D","D#","E","F","F#","G","G#","A","A#","B")
        val midi   = (12.0 * log2(freq / 440.0) + 69.0).roundToInt().coerceIn(0, 127)
        val octave = (midi / 12) - 1
        return names[midi % 12] + octave
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun captureLoop(bufSize: Int, onNoteDetected: (String) -> Unit) {
        val buffer   = ShortArray(bufSize / 2)
        val detector = YINPitchDetector(SAMPLE_RATE, buffer.size)
        var lastNote = ""

        while (isRecording) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
            if (read <= 0) continue
            val result = detector.detect(buffer, read) ?: continue
            if (result.confidence < CONFIDENCE) continue
            val note = freqToNote(result.pitchHz) ?: continue
            if (note == lastNote) continue   // skip consecutive duplicates
            lastNote = note
            _detectedNotes.add(note)
            mainHandler.post { onNoteDetected(note) }
        }
    }

    private fun emulatorLoop(onNoteDetected: (String) -> Unit) {
        val testNotes = listOf("C4","E4","G4","A4","F4","E4","D4","C4")
        var idx = 0
        while (isRecording) {
            val note = testNotes[idx % testNotes.size]
            _detectedNotes.add(note)
            mainHandler.post { onNoteDetected(note) }
            idx++
            Thread.sleep(600)
        }
    }

    private fun deduplicate(notes: List<String>): List<String> {
        val result = mutableListOf<String>()
        var last   = ""
        for (n in notes) { if (n != last) { result.add(n); last = n } }
        return result
    }
}