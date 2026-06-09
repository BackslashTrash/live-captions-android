package com.livecaption

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AudioCapture wraps Android's AudioRecord into a coroutine-based stream.
 *
 * Fixes vs original:
 *  - [stop] is idempotent — safe to call multiple times without NPE.
 *  - AudioRecord read errors (negative return values) are logged, not silently skipped.
 *  - Recorder reference is captured locally inside the coroutine so a concurrent
 *    [stop] call cannot null it under the coroutine mid-read.
 *  - Chunk delivery: [onChunk] is called ONLY after [AudioDSP.process] returns
 *    non-null (i.e. after the noise gate passes). [onLevel] fires on every chunk
 *    for the visual meter, mirrors Go's separate level emission.
 */
class AudioCapture(
    private val onChunk: suspend (FloatArray) -> Unit,
    private val onLevel: (Float) -> Unit
) {
    companion object {
        const val SAMPLE_RATE   = 16000
        // 20ms chunks at 16 kHz — matches the Go desktop pipeline
        const val CHUNK_FRAMES  = 320
        const val CHUNK_BYTES   = CHUNK_FRAMES * 2  // S16LE = 2 bytes per frame

        private const val TAG = "AudioCapture"
    }

    private var recorder:    AudioRecord? = null
    private var captureJob:  Job?         = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Start capturing. No-op if already running. */
    fun start() {
        if (captureJob?.isActive == true) return     // already running

        val minBuf  = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "getMinBufferSize failed: $minBuf")
            return
        }
        val bufSize = maxOf(minBuf, CHUNK_BYTES * 4)

        val rec = AudioRecord(
            // VOICE_RECOGNITION applies platform noise suppression — best for STT
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            rec.release()
            return
        }

        AudioDSP.reset()
        rec.startRecording()
        recorder = rec

        captureJob = scope.launch {
            val buf = ByteArray(CHUNK_BYTES)
            // Capture rec locally so a concurrent stop() setting recorder=null
            // cannot affect us mid-read inside the coroutine.
            val localRec = rec

            while (isActive) {
                val read = localRec.read(buf, 0, CHUNK_BYTES)
                when {
                    read == CHUNK_BYTES -> {
                        val samples   = buf.toFloat32()
                        val rms       = AudioDSP.computeRMS(samples)
                        onLevel(rms)                           // always fire for meter

                        val processed = AudioDSP.process(samples) ?: continue
                        onChunk(processed)                     // suspend: waits for STT
                    }
                    read > 0 -> {
                        // Partial read — shouldn't happen with blocking mode, but handle it
                        Log.w(TAG, "Partial read: $read bytes (expected $CHUNK_BYTES)")
                    }
                    else -> {
                        // AudioRecord.ERROR (-1) or ERROR_INVALID_OPERATION (-3)
                        Log.e(TAG, "AudioRecord.read error: $read — stopping capture")
                        break
                    }
                }
            }
        }
    }

    /** Stop capturing. Idempotent — safe to call multiple times. */
    fun stop() {
        captureJob?.cancel()
        captureJob = null
        recorder?.let { rec ->
            try {
                if (rec.state == AudioRecord.STATE_INITIALIZED) rec.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "stop() on non-recording AudioRecord: ${e.message}")
            }
            rec.release()
        }
        recorder = null
    }

    // S16LE byte array → float32 [-1.0, 1.0]
    // Direct port of Go's int16SliceToFloat32 in capture.go
    private fun ByteArray.toFloat32(): FloatArray {
        val bbuf = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(size / 2) { bbuf.short.toFloat() / Short.MAX_VALUE }
    }
}