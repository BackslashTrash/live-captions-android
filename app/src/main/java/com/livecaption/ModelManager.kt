package com.livecaption

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.vosk.Model
import org.vosk.Recognizer
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object ModelManager {

    private const val TAG = "LC_ModelManager"

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

    private const val SENTINEL = ".vosk_model_ok"

    private val modelMutex = Mutex()
    private var activeModel:      Model?      = null
    private var activeRecognizer: Recognizer? = null

    @Volatile private var recognizerSnapshot: Recognizer? = null

    var onProgress:   ((lang: String, percent: Int) -> Unit)? = null
    var onModelReady: ((lang: String) -> Unit)?               = null
    var onError:      ((msg: String) -> Unit)?                = null

    private var downloadJob: Job? = null

    fun getRecognizer(): Recognizer? = recognizerSnapshot

    fun switchLanguage(context: Context, lang: String, scope: CoroutineScope) {
        Log.i(TAG, "switchLanguage($lang) — launching download/load job")
        downloadJob?.cancel()
        downloadJob = scope.launch(Dispatchers.IO) {
            loadOrDownload(context, lang)
        }
    }

    suspend fun withRecognizer(block: (Recognizer) -> Unit) {
        val rec = modelMutex.withLock { activeRecognizer } ?: return
        block(rec)
    }

    suspend fun createFileRecognizer(sampleRate: Float): Recognizer? =
        modelMutex.withLock { activeModel?.let { Recognizer(it, sampleRate) } }

    suspend fun shutdown() {
        Log.i(TAG, "shutdown()")
        modelMutex.withLock {
            recognizerSnapshot = null
            activeRecognizer?.close()
            activeModel?.close()
            activeRecognizer = null
            activeModel      = null
        }
    }

    private suspend fun loadOrDownload(context: Context, lang: String) {
        Log.i(TAG, "loadOrDownload($lang) start — thread=${Thread.currentThread().name}")

        val info = LANGUAGES[lang] ?: run {
            Log.e(TAG, "Unknown language: $lang")
            onError?.invoke("Unknown language: $lang"); return
        }

        val modelsDir = File(context.filesDir, "vosk_models").also {
            it.mkdirs()
            Log.i(TAG, "Models dir: ${it.absolutePath} exists=${it.exists()}")
        }
        val modelDir = File(modelsDir, info.folder)
        Log.i(TAG, "Expected model dir: ${modelDir.absolutePath} exists=${modelDir.exists()}")

        if (modelDir.exists()) {
            val sentinel = File(modelDir, SENTINEL)
            Log.i(TAG, "Model dir exists — sentinel=${sentinel.exists()}")
            if (sentinel.exists()) {
                val contents = modelDir.listFiles()?.map { it.name } ?: emptyList()
                Log.i(TAG, "Model dir contents (${contents.size} files): $contents")
                loadModel(lang, modelDir)
                return
            } else {
                Log.w(TAG, "No sentinel — corrupt model dir, deleting for re-download")
                modelDir.deleteRecursively()
            }
        }

        // Clean stale artefacts
        File(modelsDir, "${info.folder}.tmp").also {
            if (it.exists()) { Log.i(TAG, "Deleting stale .tmp dir"); it.deleteRecursively() }
        }
        File(modelsDir, "${info.folder}.zip").also {
            if (it.exists()) { Log.i(TAG, "Deleting stale .zip"); it.delete() }
        }

        Log.i(TAG, "Starting download from ${info.url}")
        onProgress?.invoke(lang, 0)

        val zipFile = File(modelsDir, "${info.folder}.zip")
        try {
            downloadFile(lang, info.url, zipFile) { pct ->
                onProgress?.invoke(lang, pct)
            }
            Log.i(TAG, "Download complete — zip size=${zipFile.length()} bytes")
        } catch (e: CancellationException) {
            zipFile.delete(); throw e
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            zipFile.delete()
            onError?.invoke("Download failed: ${e.message}"); return
        }

        Log.i(TAG, "Starting extraction to ${modelsDir.absolutePath}")
        onProgress?.invoke(lang, 100)
        val tmpDir = File(modelsDir, "${info.folder}.tmp")
        try {
            extractZip(zipFile, tmpDir)
            Log.i(TAG, "Extraction complete — tmpDir contents: ${tmpDir.listFiles()?.map { it.name }}")
        } catch (e: CancellationException) {
            tmpDir.deleteRecursively(); zipFile.delete(); throw e
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed", e)
            tmpDir.deleteRecursively(); zipFile.delete()
            onError?.invoke("Extraction failed: ${e.message}"); return
        }

        zipFile.delete()

        val extractedRoot = tmpDir.listFiles()
            ?.firstOrNull { it.isDirectory && !it.name.startsWith(".") }
            ?: tmpDir
        Log.i(TAG, "Extracted root: ${extractedRoot.absolutePath}")
        Log.i(TAG, "Extracted root contents: ${extractedRoot.listFiles()?.map { it.name }}")

        File(extractedRoot, SENTINEL).createNewFile()

        val renamed = extractedRoot.renameTo(modelDir)
        Log.i(TAG, "renameTo modelDir: success=$renamed")
        if (!renamed) {
            Log.w(TAG, "renameTo failed — falling back to copyRecursively")
            extractedRoot.copyRecursively(modelDir, overwrite = true)
        }
        tmpDir.deleteRecursively()

        Log.i(TAG, "Final model dir: ${modelDir.absolutePath} exists=${modelDir.exists()}")
        Log.i(TAG, "Final model dir contents: ${modelDir.listFiles()?.map { it.name }}")

        loadModel(lang, modelDir)
    }

    private suspend fun loadModel(lang: String, modelDir: File) {
        Log.i(TAG, "loadModel($lang) from ${modelDir.absolutePath}")

        val contents = modelDir.listFiles()
        if (contents.isNullOrEmpty()) {
            Log.e(TAG, "Model dir is EMPTY — extraction may have failed silently")
            onError?.invoke("Model files missing. Please try again.")
            modelDir.deleteRecursively()
            return
        }
        Log.i(TAG, "Model dir has ${contents.size} entries: ${contents.map { it.name }}")

        // Vosk requires these key files/dirs to exist
        val required = listOf("am", "conf", "graph")
        val missing  = required.filter { name -> contents.none { it.name == name } }
        if (missing.isNotEmpty()) {
            Log.e(TAG, "Model dir missing required entries: $missing")
            Log.e(TAG, "This usually means the ZIP extracted into a wrong directory level.")
            // Try one level deeper
            val subDir = contents.firstOrNull { it.isDirectory && !it.name.startsWith(".") }
            if (subDir != null) {
                Log.i(TAG, "Trying sub-directory: ${subDir.absolutePath}")
                loadModel(lang, subDir)
                return
            }
            onError?.invoke("Model structure invalid. Please try again.")
            modelDir.deleteRecursively()
            return
        }

        Log.i(TAG, "Model structure looks valid — calling Model(${modelDir.absolutePath})")
        try {
            val newModel = withContext(Dispatchers.IO) {
                Log.i(TAG, "Model() constructor starting on ${Thread.currentThread().name}")
                val m = Model(modelDir.absolutePath)
                Log.i(TAG, "Model() constructor returned")
                m
            }
            Log.i(TAG, "Creating Recognizer at ${AudioCapture.SAMPLE_RATE}Hz")
            val newRec = Recognizer(newModel, AudioCapture.SAMPLE_RATE.toFloat())
            Log.i(TAG, "Recognizer created successfully")

            modelMutex.withLock {
                activeRecognizer?.close()
                activeModel?.close()
                activeRecognizer   = newRec
                activeModel        = newModel
                recognizerSnapshot = newRec
            }

            Log.i(TAG, "=== MODEL READY: $lang ===")
            onModelReady?.invoke(lang)

        } catch (e: Exception) {
            Log.e(TAG, "Model() or Recognizer() constructor threw: ${e.message}", e)
            modelDir.deleteRecursively()
            onError?.invoke("Failed to load model: ${e.message}")
        }
    }

    private fun downloadFile(lang: String, urlStr: String, dest: File, onPercent: (Int) -> Unit) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout    = 60_000
            connect()
        }
        Log.i(TAG, "HTTP response: ${conn.responseCode} for $urlStr")
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            conn.disconnect()
            throw IOException("HTTP ${conn.responseCode} for $lang model")
        }
        val total      = conn.contentLengthLong
        var downloaded = 0L
        var lastPct    = -1
        conn.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    downloaded += n
                    if (total > 0) {
                        val pct = ((downloaded * 99) / total).toInt()
                        if (pct != lastPct) { lastPct = pct; onPercent(pct) }
                    }
                    if (Thread.currentThread().isInterrupted)
                        throw CancellationException("Download cancelled")
                }
            }
        }
        conn.disconnect()
    }

    private fun extractZip(zipFile: File, destDir: File) {
        destDir.mkdirs()
        var entryCount = 0
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entryCount++
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out ->
                        val buf = ByteArray(8192)
                        while (true) { val n = zis.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
                if (Thread.currentThread().isInterrupted)
                    throw CancellationException("Extraction cancelled")
            }
        }
        Log.i(TAG, "Extracted $entryCount ZIP entries to ${destDir.absolutePath}")
    }
}