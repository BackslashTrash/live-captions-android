package com.livecaption

import android.app.Application
import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.vosk.Recognizer
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * CaptionViewModel — full feature parity with the desktop Go application.
 *
 * Features mirrored from main.go + desktop JS frontend:
 *  - Live captions with permanent + partial word merging (identical algorithm)
 *  - Transcript history (up to 200 lines)
 *  - Recording start/stop with 60-second auto-save to a .tmp file
 *  - Save transcript to Documents/Transcriptions/
 *  - Language switching via ModelManager
 *  - File transcription (audio file → transcript file)
 *  - Audio level meter emissions (~20 Hz)
 *  - Graceful shutdown (mirrors OnShutdown in main.go)
 */
class CaptionViewModel(app: Application) : AndroidViewModel(app) {

    private val TAG = "CaptionViewModel"

    // ── UI state ──────────────────────────────────────────────────────────────
    private val _caption         = MutableStateFlow("")
    private val _audioLevel      = MutableStateFlow(0f)
    private val _modelReady      = MutableStateFlow(false)
    private val _statusMsg       = MutableStateFlow("Loading language model…")
    private val _isRecording     = MutableStateFlow(false)
    private val _currentLang     = MutableStateFlow("English")
    private val _downloadProgress= MutableStateFlow(-1)   // -1 = not downloading
    private val _historyLines    = MutableStateFlow<List<String>>(emptyList())
    private val _toastEvent      = MutableStateFlow<ToastEvent?>(null)
    private val _fileTranscribeState = MutableStateFlow<FileTranscribeState>(FileTranscribeState.Idle)

    val caption:          StateFlow<String>             = _caption.asStateFlow()
    val audioLevel:       StateFlow<Float>              = _audioLevel.asStateFlow()
    val modelReady:       StateFlow<Boolean>            = _modelReady.asStateFlow()
    val statusMsg:        StateFlow<String>             = _statusMsg.asStateFlow()
    val isRecording:      StateFlow<Boolean>            = _isRecording.asStateFlow()
    val currentLang:      StateFlow<String>             = _currentLang.asStateFlow()
    val downloadProgress: StateFlow<Int>                = _downloadProgress.asStateFlow()
    val historyLines:     StateFlow<List<String>>       = _historyLines.asStateFlow()
    val toastEvent:       StateFlow<ToastEvent?>        = _toastEvent.asStateFlow()
    val fileTranscribeState: StateFlow<FileTranscribeState> = _fileTranscribeState.asStateFlow()

    // ── Caption word state — mirrors permanentWords + hiddenWordCount in JS ──
    private val permanentWords = ArrayDeque<String>()
    private val historyLinesMutable = ArrayDeque<String>()

    // ── Recording state — mirrors recMu / recBuffer / recTicker in main.go ──
    private val recMutex   = kotlinx.coroutines.sync.Mutex()
    private val recBuffer  = StringBuilder()
    private var recTmpPath: File? = null
    private var autoSaveJob: Job? = null

    // ── STT engine & audio ────────────────────────────────────────────────────
    // audioCapture is lateinit so it can be constructed in init{} after voskEngine
    // is fully initialized, breaking the circular class-body inference that caused:
    //   "Type checking has run into a recursive problem" and
    //   "Unresolved reference: acceptChunk"
    private val voskEngine = VoskEngine(app)
    private lateinit var audioCapture: AudioCapture

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    init {
        // ── Wire voskEngine callbacks ────────────────────────────────────────
        // Done here (not in an apply{} block on the class-level val) so that
        // audioCapture is guaranteed constructed before any callback fires.
        voskEngine.onModelReady = { lang ->
            _modelReady.value       = true
            _currentLang.value      = lang
            _downloadProgress.value = -1
            _statusMsg.value        = "Listening in $lang…"
            _caption.value          = ""

            // Reset word state on language switch — mirrors model_ready in JS
            permanentWords.clear()
            historyLinesMutable.clear()
            _historyLines.value = emptyList()

            // Start audio only after model is ready — audioCapture is always
            // initialized before this callback can fire because loadDefaultModel()
            // is called at the bottom of this init{} block.
            audioCapture.start()
        }
        voskEngine.onDownloadProgress = { lang, pct ->
            _downloadProgress.value = pct
            _statusMsg.value = if (pct >= 100)
                "Extracting $lang model…"
            else
                "Downloading $lang model… $pct%"
        }
        voskEngine.onError = { msg ->
            _statusMsg.value = msg
            _downloadProgress.value = -1
            showToast(msg, isError = true)
        }
        voskEngine.onResult = { result -> handleCaptionResult(result) }

        // ── Construct audioCapture ───────────────────────────────────────────
        // Explicit parameter type annotations on the lambdas resolve the
        // inference recursion that produced:
        //   "Type checking has run into a recursive problem"
        //   "Unresolved reference: acceptChunk"
        audioCapture = AudioCapture(
            onChunk = { samples: FloatArray ->
                voskEngine.acceptChunk(samples)
            },
            onLevel = { rms: Float ->
                _audioLevel.value = rms
            }
        )

        // ── Kick off default model load ──────────────────────────────────────
        // Mirrors the startup goroutine in main.go.
        voskEngine.loadDefaultModel()
    }

    override fun onCleared() {
        super.onCleared()
        // Mirror OnShutdown in main.go:
        // 1. Stop audio device (no more callbacks)
        audioCapture.stop()
        // 2. Flush any in-progress recording
        viewModelScope.launch { autoSaveTranscript() }
        // 3. Shutdown engine (frees model + recognizer)
        voskEngine.shutdown()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public actions
    // ─────────────────────────────────────────────────────────────────────────

    /** Switch to a different language model. Mirrors switch_language event. */
    fun switchLanguage(lang: String) {
        if (lang == _currentLang.value && _modelReady.value) return
        audioCapture.stop()                    // stop feeding chunks during swap
        _modelReady.value    = false
        _downloadProgress.value = 0
        _statusMsg.value     = "Loading $lang model…"
        voskEngine.switchLanguage(lang)
    }

    /** Start recording — mirrors recording_started event in main.go. */
    fun startRecording(saveFolder: File? = null) {
        viewModelScope.launch {
            recMutex.withLock {
                recBuffer.clear()
                recTmpPath = buildTmpPath(saveFolder)
                recTmpPath?.parentFile?.mkdirs()
                _isRecording.value = true
            }

            // 60-second auto-save ticker — mirrors recTicker in main.go
            autoSaveJob?.cancel()
            autoSaveJob = viewModelScope.launch {
                while (isActive) {
                    delay(60_000)
                    autoSaveTranscript()
                }
            }

            showToast("Recording started — transcript is being saved automatically.")
        }
    }

    /** Stop recording and save transcript — mirrors recording_stopped + save_transcript. */
    fun stopRecordingAndSave(saveFolder: File? = null) {
        viewModelScope.launch {
            autoSaveJob?.cancel()
            autoSaveJob = null

            val (content, tmpPath) = recMutex.withLock {
                _isRecording.value = false
                val c = recBuffer.toString()
                val t = recTmpPath
                recBuffer.clear()
                recTmpPath = null
                c to t
            }

            if (content.isBlank()) {
                showToast("Nothing to save — no speech was recorded.")
                return@launch
            }

            val result = saveTranscript(content, saveFolder)
            if (result != null) {
                tmpPath?.delete()
                showToast("Transcript saved to: ${result.name}")
                Log.i(TAG, "Transcript saved: ${result.absolutePath}")
            }
        }
    }

    /** Transcribe an audio file — mirrors transcribe_audio_file event in main.go. */
    fun transcribeAudioFile(audioFile: File, saveFolder: File? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            _fileTranscribeState.value = FileTranscribeState.Running(0, 0)

            val fileRec: Recognizer? = voskEngine.createFileRecognizer()
            if (fileRec == null) {
                _fileTranscribeState.value =
                    FileTranscribeState.Error("Language model not loaded yet.")
                return@launch
            }

            try {
                val result = doFileTranscription(audioFile, fileRec) { done, total ->
                    _fileTranscribeState.value = FileTranscribeState.Running(done, total)
                }

                val saved = saveTranscript(result, saveFolder, prefix = "File_Transcription_")
                if (saved != null) {
                    _fileTranscribeState.value = FileTranscribeState.Done(saved)
                    showToast("File transcription saved to: ${saved.name}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "File transcription failed", e)
                _fileTranscribeState.value =
                    FileTranscribeState.Error("Transcription failed: ${e.message}")
            } finally {
                fileRec.close()
            }
        }
    }

    fun dismissFileTranscribeState() {
        _fileTranscribeState.value = FileTranscribeState.Idle
    }

    fun consumeToast() {
        _toastEvent.value = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Caption result handling — mirrors the JS caption event handler exactly
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleCaptionResult(result: CaptionResult) {
        val rawText = result.text.trim()
        if (rawText.isEmpty()) return

        val partialWords = rawText.split("\\s+".toRegex()).filter { it.isNotEmpty() }

        if (result.isFinal) {
            permanentWords.addAll(partialWords)

            // Cap at 500, trim to 200 — mirrors desktop JS logic exactly
            if (permanentWords.size > 500) {
                val trimCount = permanentWords.size - 200
                repeat(trimCount) { permanentWords.removeFirst() }
            }

            // Append to history — mirrors appendHistoryLine in JS
            val display = rawText.replaceFirstChar { it.uppercaseChar() }
            if (display.isNotBlank()) {
                historyLinesMutable.addLast(display)
                if (historyLinesMutable.size > 200) historyLinesMutable.removeFirst()
                _historyLines.value = historyLinesMutable.toList()
            }

            // Accumulate into recording buffer — mirrors recBuffer writes in Go STT loop
            if (_isRecording.value) {
                viewModelScope.launch {
                    recMutex.withLock {
                        if (recBuffer.isNotEmpty()) recBuffer.append('\n')
                        recBuffer.append(rawText)
                    }
                }
            }

            _caption.value = permanentWords.let { dq ->
                dq.drop(maxOf(0, dq.size - 40)).joinToString(" ")
            }
        } else {
            // Partial — combine permanent + partial, show last 40 words
            val combined: List<String> = (permanentWords.toList() + partialWords)
            _caption.value = combined.drop(maxOf(0, combined.size - 40)).joinToString(" ")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // File transcription — reads PCM from the audio file
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Transcribe a raw PCM or WAV file chunk-by-chunk.
     * For compressed formats (mp3, m4a, flac) the caller should decode to PCM first
     * using MediaCodec or ffmpeg; here we handle raw S16LE PCM and simple WAV.
     */
    private fun doFileTranscription(
        audioFile: File,
        rec: Recognizer,
        onProgress: (done: Int, total: Int) -> Unit
    ): String {
        val allBytes = audioFile.readBytes()

        // Skip WAV header if present (44 bytes)
        val startOffset = if (allBytes.size > 44 &&
            allBytes[0] == 'R'.code.toByte() &&
            allBytes[1] == 'I'.code.toByte()) 44 else 0

        val audioData   = allBytes.copyOfRange(startOffset, allBytes.size)
        val chunkSize   = 8000   // ~250ms at 16kHz S16LE
        val totalChunks = (audioData.size + chunkSize - 1) / chunkSize
        val transcript  = StringBuilder()

        for (chunkIdx in 0 until totalChunks) {
            val start = chunkIdx * chunkSize
            val end   = minOf(start + chunkSize, audioData.size)
            val chunk = audioData.copyOfRange(start, end)

            if (rec.acceptWaveForm(chunk, chunk.size)) {
                val json = rec.result
                val text = parseJsonField(json, "text")
                if (text.isNotBlank()) {
                    if (transcript.isNotEmpty()) transcript.append(' ')
                    transcript.append(text)
                }
            }
            onProgress(chunkIdx + 1, totalChunks)
        }

        // Flush remaining
        val finalJson = rec.finalResult
        val finalText = parseJsonField(finalJson, "text")
        if (finalText.isNotBlank()) {
            if (transcript.isNotEmpty()) transcript.append(' ')
            transcript.append(finalText)
        }

        return transcript.toString()
    }

    private fun parseJsonField(json: String, field: String): String {
        val pattern = Regex(""""$field"\s*:\s*"([^"]*)"""")
        return pattern.find(json)?.groupValues?.getOrNull(1)?.trim() ?: ""
    }

    // ─────────────────────────────────────────────────────────────────────────
    // File I/O — mirrors writeTranscript + autoSaveTranscript in main.go
    // ─────────────────────────────────────────────────────────────────────────

    private fun resolveFolder(override: File?): File {
        if (override != null && override.exists()) return override
        // Default: Documents/Transcriptions — mirrors Go's resolveFolder
        val docs = getApplication<Application>().getExternalFilesDir(
            Environment.DIRECTORY_DOCUMENTS
        ) ?: getApplication<Application>().filesDir
        return File(docs, "Transcriptions")
    }

    private fun buildTmpPath(saveFolder: File?): File {
        val dir = resolveFolder(saveFolder)
        dir.mkdirs()
        return File(dir, ".transcript_autosave.tmp")
    }

    private suspend fun autoSaveTranscript() {
        val (content, tmpPath) = recMutex.withLock {
            recBuffer.toString() to recTmpPath
        }
        if (content.isBlank() || tmpPath == null) return
        withContext(Dispatchers.IO) {
            try {
                tmpPath.parentFile?.mkdirs()
                tmpPath.writeText(content)
            } catch (e: IOException) {
                Log.w(TAG, "Auto-save failed: ${e.message}")
            }
        }
    }

    private suspend fun saveTranscript(
        content: String,
        saveFolder: File?,
        prefix: String = "Transcription_"
    ): File? = withContext(Dispatchers.IO) {
        val dir = resolveFolder(saveFolder)
        dir.mkdirs()
        val ts   = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val file = File(dir, "$prefix$ts.txt")
        try {
            file.writeText(content)
            file
        } catch (e: IOException) {
            Log.e(TAG, "Failed to save transcript: ${e.message}")
            withContext(Dispatchers.Main) {
                showToast("Could not save transcript: ${e.message}", isError = true)
            }
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Toast helper
    // ─────────────────────────────────────────────────────────────────────────

    private fun showToast(message: String, isError: Boolean = false) {
        _toastEvent.value = ToastEvent(message, isError)
    }
}

// ── Supporting types ──────────────────────────────────────────────────────────

data class ToastEvent(val message: String, val isError: Boolean = false)

sealed class FileTranscribeState {
    object Idle : FileTranscribeState()
    data class Running(val done: Int, val total: Int) : FileTranscribeState()
    data class Done(val file: File) : FileTranscribeState()
    data class Error(val message: String) : FileTranscribeState()
}

// ── Mutex extension ───────────────────────────────────────────────────────────
private suspend fun <T> kotlinx.coroutines.sync.Mutex.withLock(block: suspend () -> T): T {
    lock()
    return try { block() } finally { unlock() }
}