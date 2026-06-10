package com.livecaption

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.vosk.Recognizer

data class CaptionResult(val text: String, val isFinal: Boolean)

class VoskEngine(private val appContext: Context) {

    private val TAG = "LC_VoskEngine"
    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    var onResult:           ((CaptionResult) -> Unit)? = null
    var onModelReady:       ((lang: String) -> Unit)?  = null
    var onDownloadProgress: ((lang: String, pct: Int) -> Unit)? = null
    var onError:            ((String) -> Unit)?        = null

    private var chunkCount = 0
    private var lastLogTime = 0L

    init {
        Log.i(TAG, "VoskEngine created")
        ModelManager.onModelReady  = { lang ->
            Log.i(TAG, ">>> MODEL READY callback fired for lang=$lang")
            onModelReady?.invoke(lang)
        }
        ModelManager.onError       = { msg ->
            Log.e(TAG, ">>> MODEL ERROR: $msg")
            onError?.invoke(msg)
        }
        ModelManager.onProgress    = { lang, pct ->
            Log.i(TAG, ">>> DOWNLOAD PROGRESS $lang $pct%")
            onDownloadProgress?.invoke(lang, pct)
        }
    }

    fun loadDefaultModel() {
        Log.i(TAG, "loadDefaultModel() called")
        ModelManager.switchLanguage(appContext, "English", engineScope)
    }

    fun switchLanguage(lang: String) {
        Log.i(TAG, "switchLanguage($lang) called")
        ModelManager.switchLanguage(appContext, lang, engineScope)
    }

    fun acceptChunkSync(samples: FloatArray) {
        chunkCount++

        // Log every 50 chunks (~5 seconds at 100ms chunks) so we can confirm
        // audio is arriving without flooding Logcat
        val now = System.currentTimeMillis()
        if (now - lastLogTime > 5000) {
            lastLogTime = now
            val rec = ModelManager.getRecognizer()
            Log.i(TAG, "acceptChunkSync: chunk #$chunkCount, " +
                    "recognizer=${if (rec != null) "READY" else "NULL"}, " +
                    "samples=${samples.size}, " +
                    "rms=${"%.4f".format(AudioDSP.computeRMS(samples))}")
        }

        val rec = ModelManager.getRecognizer()
        if (rec == null) {
            if (chunkCount % 100 == 0) {
                Log.w(TAG, "acceptChunkSync: recognizer is NULL at chunk #$chunkCount — model not loaded yet")
            }
            return
        }

        try {
            val bytes   = samples.toS16LE()
            val isFinal = rec.acceptWaveForm(bytes, bytes.size)

            if (isFinal) {
                val raw  = rec.result
                val text = parseField(raw, "text")
                Log.i(TAG, "FINAL result raw='$raw' parsed='$text'")
                if (text.isNotBlank()) onResult?.invoke(CaptionResult(text, true))
            } else {
                val raw  = rec.partialResult
                val text = parseField(raw, "partial")
                if (text.isNotBlank()) {
                    Log.d(TAG, "PARTIAL raw='$raw' parsed='$text'")
                    onResult?.invoke(CaptionResult(text, false))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "acceptWaveForm threw an exception: ${e.message}", e)
        }
    }

    suspend fun createFileRecognizer(sampleRate: Float = AudioCapture.SAMPLE_RATE.toFloat()): Recognizer? =
        ModelManager.createFileRecognizer(sampleRate)

    fun shutdown() {
        Log.i(TAG, "shutdown() called")
        engineScope.cancel()
        engineScope.launch { ModelManager.shutdown() }
    }

    private fun parseField(json: String, field: String): String {
        val pattern = Regex(""""$field"\s*:\s*"([^"]*)"""")
        return pattern.find(json)?.groupValues?.getOrNull(1)?.trim() ?: ""
    }

    private fun FloatArray.toS16LE(): ByteArray {
        val out = ByteArray(size * 2)
        for (i in indices) {
            val s = (this[i] * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            out[i * 2]     = (s.toInt() and 0xFF).toByte()
            out[i * 2 + 1] = (s.toInt() ushr 8 and 0xFF).toByte()
        }
        return out
    }
}