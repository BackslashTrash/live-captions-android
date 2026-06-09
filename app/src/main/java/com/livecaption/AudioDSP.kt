package com.livecaption

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * AudioDSP — stateless DSP helpers + noise gate.
 *
 * Pipeline order mirrors the corrected Go capture.go:
 *   resample (done in AudioCapture) → noise gate → normalize
 *
 * The original file ran normalize → noise gate, which inflated ambient noise
 * above the threshold and caused the gate to never trigger.
 *
 * Thread-safety: [silentChunks] is now AtomicInteger so concurrent access
 * from the AudioCapture IO coroutine is race-detector-clean.
 */
object AudioDSP {

    private const val NOISE_GATE_THRESHOLD    = 0.01f
    private const val SILENCE_CHUNKS_BEFORE_STOP = 8
    private const val GAIN_CAP = 8.0f

    // AtomicInteger — safe for concurrent access from the audio IO coroutine.
    private val silentChunks = AtomicInteger(0)

    fun reset() {
        silentChunks.set(0)
    }

    /**
     * Process one chunk through the noise gate → normalize pipeline.
     * Returns null if the chunk is silent and should be dropped.
     *
     * Correct order:
     *   1. Gate on raw RMS — genuine silence is never amplified past the threshold.
     *   2. Normalize only chunks that passed the gate.
     */
    fun process(samples: FloatArray): FloatArray? {
        val rms = computeRMS(samples)
        if (rms < NOISE_GATE_THRESHOLD) {
            val count = silentChunks.incrementAndGet()
            if (count > SILENCE_CHUNKS_BEFORE_STOP) return null
            // Forward the last SILENCE_CHUNKS_BEFORE_STOP silent chunks so Vosk
            // can finalize the last word cleanly — mirrors Go behaviour.
        } else {
            silentChunks.set(0)
        }
        return normalizeWithCap(samples)
    }

    fun computeRMS(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s.toDouble()
        return sqrt(sum / samples.size).toFloat()
    }

    private fun normalizeWithCap(samples: FloatArray): FloatArray {
        var peak = 0f
        for (s in samples) {
            val a = abs(s)
            if (a > peak) peak = a
        }
        // If the chunk is nearly silent even after gate passed (e.g. the last
        // trailing chunks), return as-is to avoid massive gain on near-zero signal.
        if (peak < 0.001f) return samples
        var gain = 0.9f / peak
        if (gain > GAIN_CAP) gain = GAIN_CAP
        return FloatArray(samples.size) { i ->
            (samples[i] * gain).coerceIn(-1f, 1f)
        }
    }
}