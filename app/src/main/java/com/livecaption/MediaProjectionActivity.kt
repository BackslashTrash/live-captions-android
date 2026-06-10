package com.livecaption

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * MediaProjectionActivity — transparent trampoline for the MediaProjection
 * consent dialog ("Start recording or casting?").
 *
 * Why it exists (same reason as FilePickerActivity):
 *   MediaProjectionManager.createScreenCaptureIntent() must be launched with
 *   startActivityForResult from an Activity. OverlayService has no Activity,
 *   so this no-UI Activity hosts the launcher, forwards the grant result to
 *   the service, and finishes immediately.
 *
 * Android requires this consent dialog EVERY session — the grant cannot be
 * remembered across uses. That's an OS rule, not something we can skip.
 */
class MediaProjectionActivity : ComponentActivity() {

    companion object {
        private const val TAG = "LC_MediaProjActivity"

        fun launch(context: Context) {
            val intent = Intent(context, MediaProjectionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.i(TAG, "Projection consent result: code=${result.resultCode} data=${result.data != null}")
        // Forward to the running service (RESULT_OK = granted; anything else = denied)
        OverlayService.onProjectionResult(result.resultCode, result.data)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        consentLauncher.launch(mpm.createScreenCaptureIntent())
    }
}