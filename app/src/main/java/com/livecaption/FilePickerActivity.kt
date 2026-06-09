package com.livecaption

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import kotlinx.coroutines.*

/**
 * FilePickerActivity — a transparent, no-UI trampoline Activity whose only job
 * is to host rememberLauncherForActivityResult (which requires an Activity context)
 * on behalf of OverlayService.
 *
 * Why this exists:
 *   rememberLauncherForActivityResult / ActivityResultRegistry requires a
 *   LocalActivityResultRegistryOwner in the composition tree, which only exists
 *   inside an Activity. A Service-hosted ComposeView has no Activity, so calling
 *   rememberLauncherForActivityResult there crashes with:
 *     "No ActivityResultRegistryOwner was provided via LocalActivityResultRegistryOwner"
 *
 * Pattern:
 *   1. OverlayService calls FilePickerActivity.launch(context).
 *   2. FilePickerActivity opens the system file picker.
 *   3. On result, it calls viewModel.transcribeAudioFile() directly — the ViewModel
 *      is retrieved from OverlayService's static reference so no IPC is needed.
 *   4. FilePickerActivity finishes itself immediately (transparent, no visual flash).
 *
 * The activity theme must be Theme.Transparent (declared in AndroidManifest) so the
 * user never sees a white flash — only the system file picker appears.
 */
class FilePickerActivity : ComponentActivity() {

    companion object {
        fun launch(context: android.content.Context) {
            val intent = Intent(context, FilePickerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val picker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                handleUri(uri)
            }
        }
        // Always finish — whether the user picked a file or cancelled
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Launch the picker immediately — no UI shown by this activity itself
        picker.launch("audio/*")
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private suspend fun handleUri(uri: Uri) {
        val vm = OverlayService.activeViewModel ?: return
        // Copy the Uri content to a temp file — Vosk needs a real filesystem path,
        // not a content:// Uri. Mirrors the same pattern from the original CaptionScreen.
        val tmp = java.io.File(cacheDir, "transcribe_input_${System.currentTimeMillis()}.wav")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            vm.transcribeAudioFile(tmp)
        } catch (e: Exception) {
            android.util.Log.e("FilePickerActivity", "Failed to copy audio file", e)
        }
    }
}