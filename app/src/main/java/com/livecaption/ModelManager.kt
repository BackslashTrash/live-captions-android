package com.livecaption

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.vosk.Model
import org.vosk.Recognizer
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * ModelManager mirrors the desktop main.go model acquisition flow:
 *
 *   1. Check if the model directory already exists on the internal filesystem.
 *      - Healthy → load directly.
 *      - Corrupt (sentinel file missing) → delete and re-download.
 *   2. Download the ZIP with progress callbacks.
 *   3. Extract atomically: write to a .tmp directory, rename to final on success.
 *   4. Verify integrity with a sentinel file written at the end of extraction.
 *   5. Instantiate org.vosk.Model + Recognizer and call onModelReady.
 *
 * Language registry mirrors voskModels in main.go.
 *
 * Thread-safety: all public functions are safe to call from any coroutine.
 * Internal state is protected by [modelMutex].
 */
object ModelManager {

    private const val TAG = "ModelManager"

    // ── Language registry — mirrors voskModels in main.go ────────────────────
    data class ModelInfo(val folder: String, val url: String)

    val LANGUAGES = mapOf(
        "English"    to ModelInfo("vosk-model-small-en-us-0.15",
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"),
        "Spanish"    to ModelInfo("vosk-model-small-es-0.42",
            "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"),
        "French"     to ModelInfo("vosk-model-small-fr-0.22",
            "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip"),
        "German"     to ModelInfo("vosk-model-small-de-0.15",
            "https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip"),
        "Italian"    to ModelInfo("vosk-model-small-it-0.22",
            "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip"),
        "Portuguese" to ModelInfo("vosk-model-small-pt-0.3",
            "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip"),
        "Russian"    to ModelInfo("vosk-model-small-ru-0.22",
            "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"),
        "Chinese"    to ModelInfo("vosk-model-small-cn-0.22",
            "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"),
        "Japanese"   to ModelInfo("vosk-model-small-ja-0.22",
            "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip"),
        "Hindi"      to ModelInfo("vosk-model-small-hi-0.22",
            "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip"),
    )

    // ── Sentinel filename written after successful extraction ─────────────────
    // Mirrors model.IsCorrupt() in Go: if this file is absent the dir is corrupt.
    private const val SENTINEL = ".vosk_model_ok"

    // ── Mutex protecting activeModel / activeRecognizer ───────────────────────
    private val modelMutex = kotlinx.coroutines.sync.Mutex()
    private var activeModel:      Model?      = null
    private var activeRecognizer: Recognizer? = null

    // ── Callbacks (set by VoskEngine) ─────────────────────────────────────────
    var onProgress:   ((lang: String, percent: Int) -> Unit)? = null
    var onModelReady: ((lang: String) -> Unit)?               = null
    var onError:      ((msg: String) -> Unit)?                = null

    // ── Coroutine scope for downloads (cancellable per switch) ────────────────
    private var downloadJob: Job? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Load or download the model for [lang]. Mirrors downloadModelWithProgress in main.go.
     * Cancels any in-flight download before starting a new one.
     */
    fun switchLanguage(context: Context, lang: String, scope: CoroutineScope) {
        downloadJob?.cancel()
        downloadJob = scope.launch(Dispatchers.IO) {
            loadOrDownload(context, lang)
        }
    }

    /**
     * Returns the currently active Recognizer, or null if no model is loaded.
     * Caller must NOT hold the recognizer across a language switch; always
     * call this per-chunk.
     */
    suspend fun withRecognizer(block: (Recognizer) -> Unit) {
        modelMutex.withLock {
            activeRecognizer?.let { block(it) }
        }
    }

    /**
     * Create a fresh Recognizer from the currently loaded model.
     * Used for file transcription (mirrors fileRec in main.go).
     * Returns null if no model is loaded.
     */
    suspend fun createFileRecognizer(sampleRate: Float): Recognizer? =
        modelMutex.withLock { activeModel?.let { Recognizer(it, sampleRate) } }

    /** Release active model + recognizer. */
    suspend fun shutdown() {
        modelMutex.withLock {
            activeRecognizer?.close()
            activeModel?.close()
            activeRecognizer = null
            activeModel = null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal — load or download
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun loadOrDownload(context: Context, lang: String) {
        val info = LANGUAGES[lang] ?: run {
            onError?.invoke("Unknown language: $lang")
            return
        }

        // Models are stored in the app's private files directory —
        // no storage permission needed, never cleared by the OS while app is installed.
        val modelsDir = File(context.filesDir, "vosk_models")
        modelsDir.mkdirs()

        val modelDir = File(modelsDir, info.folder)

        // ── Integrity check ───────────────────────────────────────────────────
        if (modelDir.exists()) {
            if (File(modelDir, SENTINEL).exists()) {
                Log.i(TAG, "Model $lang already on disk — loading")
                loadModel(lang, modelDir)
                return
            } else {
                Log.w(TAG, "Model dir exists but is corrupt — removing for re-download")
                modelDir.deleteRecursively()
            }
        }

        // ── Clean stale tmp artefacts ─────────────────────────────────────────
        File(modelsDir, "${info.folder}.tmp").deleteRecursively()
        File(modelsDir, "${info.folder}.zip").delete()

        // ── Download ──────────────────────────────────────────────────────────
        onProgress?.invoke(lang, 0)

        val zipFile = File(modelsDir, "${info.folder}.zip")
        try {
            downloadFile(lang, info.url, zipFile) { percent ->
                onProgress?.invoke(lang, percent)
            }
        } catch (e: CancellationException) {
            zipFile.delete()
            throw e     // propagate cancellation cleanly
        } catch (e: Exception) {
            zipFile.delete()
            onError?.invoke("Download failed: ${e.message}")
            return
        }

        // ── Extract ───────────────────────────────────────────────────────────
        onProgress?.invoke(lang, 100)   // signal "extracting"
        val tmpDir = File(modelsDir, "${info.folder}.tmp")
        try {
            extractZip(zipFile, tmpDir)
        } catch (e: CancellationException) {
            tmpDir.deleteRecursively()
            zipFile.delete()
            throw e
        } catch (e: Exception) {
            tmpDir.deleteRecursively()
            zipFile.delete()
            onError?.invoke("Extraction failed: ${e.message}")
            return
        }

        zipFile.delete()

        // The ZIP contains a single top-level directory (the model folder).
        // Find it and rename it to the final location.
        val extractedRoot = tmpDir.listFiles()?.firstOrNull { it.isDirectory }
            ?: tmpDir   // fallback: files were extracted directly into tmpDir

        // Write sentinel BEFORE rename so if rename succeeds, integrity is guaranteed.
        File(extractedRoot, SENTINEL).createNewFile()

        // Atomic rename
        if (!extractedRoot.renameTo(modelDir)) {
            // renameTo can fail across mount points (shouldn't happen in filesDir).
            // Fallback: copy then delete.
            extractedRoot.copyRecursively(modelDir, overwrite = true)
            tmpDir.deleteRecursively()
        } else {
            tmpDir.deleteRecursively()
        }

        loadModel(lang, modelDir)
    }

    private suspend fun loadModel(lang: String, modelDir: File) {
        Log.i(TAG, "Loading Vosk model from ${modelDir.absolutePath}")
        try {
            val newModel = withContext(Dispatchers.IO) { Model(modelDir.absolutePath) }
            val newRec   = Recognizer(newModel, AudioCapture.SAMPLE_RATE.toFloat())

            modelMutex.withLock {
                activeRecognizer?.close()
                activeModel?.close()
                activeRecognizer = newRec
                activeModel      = newModel
            }

            Log.i(TAG, "Model $lang ready")
            onModelReady?.invoke(lang)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            onError?.invoke("Failed to load model: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Download helper — reports byte-level progress
    // ─────────────────────────────────────────────────────────────────────────

    private fun downloadFile(
        lang: String,
        urlStr: String,
        dest: File,
        onPercent: (Int) -> Unit
    ) {
        val url  = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout    = 60_000
        conn.connect()

        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            conn.disconnect()
            throw IOException("HTTP ${conn.responseCode} downloading $lang model")
        }

        val total = conn.contentLengthLong
        var downloaded = 0L
        var lastPercent = -1

        conn.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    downloaded += n
                    if (total > 0) {
                        val pct = ((downloaded * 99) / total).toInt()   // cap at 99; 100 = extracting
                        if (pct != lastPercent) {
                            lastPercent = pct
                            onPercent(pct)
                        }
                    }
                    // Allow coroutine cancellation between chunks
                    if (Thread.currentThread().isInterrupted) throw CancellationException("Download cancelled")
                }
            }
        }

        conn.disconnect()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ZIP extraction helper
    // ─────────────────────────────────────────────────────────────────────────

    private fun extractZip(zipFile: File, destDir: File) {
        destDir.mkdirs()
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out ->
                        val buf = ByteArray(8 * 1024)
                        while (true) {
                            val n = zis.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry

                if (Thread.currentThread().isInterrupted) throw CancellationException("Extraction cancelled")
            }
        }
    }
}

// ── Convenience extension on Mutex (not in older coroutines stdlib) ───────────
private suspend fun <T> kotlinx.coroutines.sync.Mutex.withLock(block: suspend () -> T): T {
    lock()
    return try { block() } finally { unlock() }
}