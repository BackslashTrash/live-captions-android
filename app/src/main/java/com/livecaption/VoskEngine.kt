package com.livecaption

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.vosk.Recognizer

data class CaptionResult(val text: String, val isFinal: Boolean)

/**
 * VoskEngine manages the STT inference loop.
 *
 * Responsibilities:
 *  - Delegates model download/load/switch to [ModelManager].
 *  - Accepts PCM float32 chunks from [AudioCapture] and runs them through
 *    the active Vosk recognizer on [Dispatchers.Default].
 *  - Fires [onResult] for partial and final transcriptions.
 *
 * Threading:
 *  - [acceptChunk] is called from AudioCapture's [Dispatchers.IO] coroutine.
 *    It suspends briefly to acquire the ModelManager mutex, which is cheap
 *    because inference is the only long operation inside the lock.
 *  - [onResult] and [onModelReady] are invoked from [Dispatchers.Default];
 *    callers (ViewModel) must marshal to Main themselves.
 */
class VoskEngine(private val appContext: Context) {

    private val TAG = "VoskEngine"

    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ── Callbacks ─────────────────────────────────────────────────────────────
    var onResult:      ((CaptionResult) -> Unit)? = null
    var onModelReady:  ((lang: String) -> Unit)?  = null
    var onDownloadProgress: ((lang: String, pct: Int) -> Unit)? = null
    var onError:       ((String) -> Unit)?        = null

    init {
        // Wire ModelManager callbacks into VoskEngine callbacks.
        // ModelManager is a singleton; wiring once here is sufficient.
        ModelManager.onModelReady  = { lang -> onModelReady?.invoke(lang) }
        ModelManager.onError       = { msg  -> onError?.invoke(msg) }
        ModelManager.onProgress    = { lang, pct -> onDownloadProgress?.invoke(lang, pct) }
    }

    // ── Model lifecycle ───────────────────────────────────────────────────────

    /**
     * Load the default English model on first launch.
     * Equivalent to the startup goroutine in main.go.
     */
    fun loadDefaultModel() {
        ModelManager.switchLanguage(appContext, "English", engineScope)
    }

    /**
     * Switch to a different language model.
     * Cancels any in-flight download. Mirrors switch_language event in main.go.
     */
    fun switchLanguage(lang: String) {
        ModelManager.switchLanguage(appContext, lang, engineScope)
    }

    // ── Inference ─────────────────────────────────────────────────────────────

    /**
     * Feed one DSP-processed chunk into the recognizer.
     * Suspends to acquire the model mutex; returns immediately if no model is loaded.
     * Called from AudioCapture's IO coroutine — must not block the audio thread.
     */
    suspend fun acceptChunk(samples: FloatArray) {
        ModelManager.withRecognizer { rec ->
            val bytes   = samples.toS16LE()
            val isFinal = rec.acceptWaveForm(bytes, bytes.size)
            if (isFinal) {
                val text = parseField(rec.result, "text")
                if (text.isNotBlank()) {
                    onResult?.invoke(CaptionResult(text, true))
                }
            } else {
                val text = parseField(rec.partialResult, "partial")
                if (text.isNotBlank()) {
                    onResult?.invoke(CaptionResult(text, false))
                }
            }
        }
    }

    /**
     * Create an independent recognizer for file transcription.
     * Mirrors vosk.NewRecognizer(activeModel, sampleRate) in main.go.
     * Returns null if no model is currently loaded.
     */
    suspend fun createFileRecognizer(sampleRate: Float = AudioCapture.SAMPLE_RATE.toFloat()): Recognizer? =
        ModelManager.createFileRecognizer(sampleRate)

    fun shutdown() {
        engineScope.cancel()
        engineScope.launch { ModelManager.shutdown() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parse a JSON field from Vosk result strings.
     *
     * Vosk returns compact JSON like:
     *   {"text": "hello world"}
     *   {"partial": "hel"}
     *
     * The previous regex had a trailing space inside the string literal that
     * caused it to fail on most Vosk output. Fixed to use a simple, correct pattern.
     */
    private fun parseField(json: String, field: String): String {
        // Matches:  "field" : "value"  with any whitespace around the colon
        val pattern = Regex(""""$field"\s*:\s*"([^"]*)"""")
        return pattern.find(json)?.groupValues?.getOrNull(1)?.trim() ?: ""
    }

    // float32 [-1.0, 1.0] → S16LE bytes
    // Inverse of AudioCapture.toFloat32(); mirrors int16SliceToFloat32 in capture.go
    private fun FloatArray.toS16LE(): ByteArray {
        val out = ByteArray(size * 2)
        for (i in indices) {
            val s = (this[i] * Short.MAX_VALUE)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            out[i * 2]     = (s.toInt() and 0xFF).toByte()
            out[i * 2 + 1] = (s.toInt() ushr 8 and 0xFF).toByte()
        }
        return out
    }
}