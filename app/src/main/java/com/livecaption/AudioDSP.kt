package com.livecaption

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.sqrt

object AudioDSP {

    private const val TAG = "LC_AudioDSP"

    // Exposed so AudioCapture can log it
    const val NOISE_GATE_THRESHOLD = 0.008f

    private const val SILENCE_CHUNKS_BEFORE_STOP = 4
    private const val GAIN_CAP = 4.0f

    private val silentChunks = AtomicInteger(0)

    // Track the highest RMS seen — helps diagnose if mic is picking up anything
    @Volatile var peakRmsSeen = 0f
        private set

    fun reset() {
        silentChunks.set(0)
        peakRmsSeen = 0f
        Log.i(TAG, "AudioDSP reset. Gate threshold=$NOISE_GATE_THRESHOLD")
    }

    fun process(samples: FloatArray): FloatArray? {
        val rms = computeRMS(samples)
        if (rms > peakRmsSeen) peakRmsSeen = rms

        if (rms < NOISE_GATE_THRESHOLD) {
            val count = silentChunks.incrementAndGet()
            if (count > SILENCE_CHUNKS_BEFORE_STOP) return null
            return samples
        }
        silentChunks.set(0)
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
        for (s in samples) { val a = abs(s); if (a > peak) peak = a }
        if (peak < 0.001f) return samples
        var gain = 0.9f / peak
        if (gain > GAIN_CAP) gain = GAIN_CAP
        return FloatArray(samples.size) { i -> (samples[i] * gain).coerceIn(-1f, 1f) }
    }
}