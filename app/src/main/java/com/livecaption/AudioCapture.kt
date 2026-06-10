package com.livecaption

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Which physical source the capture pipeline is reading from. */
enum class AudioSourceType { MIC, DEVICE }

/**
 * AudioCapture — dual-source capture front-end for the Vosk pipeline.
 *
 *  - MIC    → plain AudioRecord on MediaRecorder.AudioSource.MIC (all API levels)
 *  - DEVICE → AudioPlaybackCapture via MediaProjection (API 29+ only).
 *             Captures playback from other apps that allow it. Apps that opt out
 *             (Netflix, Spotify, phone calls, DRM content) deliver silence —
 *             that is an OS-enforced privacy boundary, not a bug here.
 *
 * Both sources feed the identical downstream pipeline:
 *   read loop → AudioDSP gate/normalize → channel → drain loop → onChunk (Vosk)
 */
class AudioCapture(
    private val onChunk: (FloatArray) -> Unit,
    private val onLevel: (Float) -> Unit
) {
    companion object {
        const val SAMPLE_RATE  = 16000
        const val CHUNK_FRAMES = 1600         // 100ms chunks
        const val CHUNK_BYTES  = CHUNK_FRAMES * 2
        private const val TAG  = "LC_AudioCapture"
    }

    private var recorder:    AudioRecord? = null
    private var captureJob:  Job?         = null
    private var drainJob:    Job?         = null
    private val captureScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Which source is currently active. Valid only while running. */
    @Volatile var currentSource: AudioSourceType = AudioSourceType.MIC
        private set

    private var chunkChannel = newChannel()

    private fun newChannel() = Channel<FloatArray>(
        capacity         = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /** Start capturing from the device microphone. */
    fun startMic() {
        if (captureJob?.isActive == true) {
            Log.w(TAG, "startMic() called but already running — ignoring")
            return
        }
        Log.i(TAG, "startMic() — thread=${Thread.currentThread().name}")

        val rec = buildMicRecord() ?: return
        currentSource = AudioSourceType.MIC
        beginCapture(rec, "MIC")
    }

    /**
     * Start capturing other apps' playback audio via MediaProjection.
     * API 29+ only. The projection must already be granted (consent dialog done)
     * and the owning foreground service must have mediaProjection type active.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun startDevice(projection: MediaProjection) {
        if (captureJob?.isActive == true) {
            Log.w(TAG, "startDevice() called but already running — ignoring")
            return
        }
        Log.i(TAG, "startDevice() — thread=${Thread.currentThread().name}")

        val rec = buildPlaybackCaptureRecord(projection) ?: return
        currentSource = AudioSourceType.DEVICE
        beginCapture(rec, "DEVICE")
    }

    /** Stop capturing from whichever source is active. Idempotent. */
    fun stop() {
        Log.i(TAG, "stop() called (source=$currentSource)")
        captureJob?.cancel(); captureJob = null
        drainJob?.cancel();   drainJob   = null
        // Close + recreate the channel so a stale drain loop never consumes
        // chunks meant for the next session.
        chunkChannel.close()
        chunkChannel = newChannel()

        recorder?.let { rec ->
            try {
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "stop() error: ${e.message}")
            }
            rec.release()
        }
        recorder = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AudioRecord builders
    // ─────────────────────────────────────────────────────────────────────────

    private fun minBufOrNull(): Int? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        Log.i(TAG, "minBufferSize=$minBuf")
        if (minBuf <= 0) {
            Log.e(TAG, "getMinBufferSize failed: $minBuf")
            return null
        }
        return maxOf(minBuf, SAMPLE_RATE * 2 * 4)
    }

    @SuppressLint("MissingPermission")   // RECORD_AUDIO is checked by MainActivity gate
    private fun buildMicRecord(): AudioRecord? {
        val hwBuf = minBufOrNull() ?: return null
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            hwBuf
        )
        return validate(rec, "MIC")
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun buildPlaybackCaptureRecord(projection: MediaProjection): AudioRecord? {
        val hwBuf = minBufOrNull() ?: return null

        // Capture media/game/unknown usage from apps that allow it.
        // USAGE_VOICE_COMMUNICATION (calls) cannot be added — OS forbids it.
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        // NOTE: setAudioPlaybackCaptureConfig and setAudioSource are mutually
        // exclusive on AudioRecord.Builder — setting both throws.
        val rec = try {
            AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(hwBuf)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord.Builder for playback capture threw: ${e.message}", e)
            return null
        }
        return validate(rec, "DEVICE")
    }

    private fun validate(rec: AudioRecord, label: String): AudioRecord? {
        Log.i(TAG, "[$label] AudioRecord state=${rec.state} " +
                "(INITIALIZED=${AudioRecord.STATE_INITIALIZED})")
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "[$label] AudioRecord FAILED to initialize")
            rec.release()
            return null
        }
        return rec
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shared capture pipeline
    // ─────────────────────────────────────────────────────────────────────────

    private fun beginCapture(rec: AudioRecord, label: String) {
        rec.startRecording()
        Log.i(TAG, "[$label] startRecording() — recordingState=${rec.recordingState} " +
                "(RECORDING=${AudioRecord.RECORDSTATE_RECORDING})")

        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Log.e(TAG, "[$label] did NOT enter RECORDING state")
            rec.release()
            return
        }

        recorder = rec
        AudioDSP.reset()
        val channel = chunkChannel   // capture current channel instance

        // ── Read loop ─────────────────────────────────────────────────────────
        captureJob = captureScope.launch {
            val buf       = ByteArray(CHUNK_BYTES)
            var readCount    = 0
            var passedGate   = 0
            var droppedGate  = 0
            var lastReport   = System.currentTimeMillis()

            Log.i(TAG, "[$label] Read loop started on ${Thread.currentThread().name}")

            while (isActive) {
                val read = rec.read(buf, 0, CHUNK_BYTES)
                when {
                    read == CHUNK_BYTES -> {
                        readCount++
                        val samples = buf.toFloat32()
                        val rms     = AudioDSP.computeRMS(samples)
                        onLevel(rms)

                        val processed = AudioDSP.process(samples)

                        val now = System.currentTimeMillis()
                        if (now - lastReport > 5000) {
                            lastReport = now
                            Log.i(TAG, "[$label] Audio stats: reads=$readCount " +
                                    "passedGate=$passedGate droppedGate=$droppedGate " +
                                    "currentRMS=${"%.5f".format(rms)} " +
                                    "gateThreshold=${AudioDSP.NOISE_GATE_THRESHOLD}")
                        }

                        if (processed != null) {
                            passedGate++
                            channel.trySend(processed)
                        } else droppedGate++
                    }
                    read > 0 -> Log.w(TAG, "[$label] Partial read: $read bytes")
                    else     -> {
                        Log.e(TAG, "[$label] AudioRecord.read error: $read — stopping")
                        break
                    }
                }
            }
            Log.i(TAG, "[$label] Read loop ended")
        }

        // ── Inference drain loop ──────────────────────────────────────────────
        drainJob = captureScope.launch {
            Log.i(TAG, "[$label] Drain loop started on ${Thread.currentThread().name}")
            var n = 0
            for (chunk in channel) {
                n++
                if (n % 50 == 0) Log.i(TAG, "[$label] Drain: $n chunks processed")
                onChunk(chunk)
            }
            Log.i(TAG, "[$label] Drain loop ended after $n chunks")
        }
    }

    private fun ByteArray.toFloat32(): FloatArray {
        val bb = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(size / 2) { bb.short.toFloat() / Short.MAX_VALUE }
    }
}