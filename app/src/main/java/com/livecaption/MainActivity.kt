package com.livecaption

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/**
 * MainActivity — permission gateway only.
 *
 * This activity:
 *  1. Checks / requests SYSTEM_ALERT_WINDOW (overlay permission — must send user to Settings).
 *  2. Checks / requests RECORD_AUDIO (mic).
 *  3. Checks / requests READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE (file picker).
 *  4. Once all critical permissions are granted, starts OverlayService and finishes itself.
 *
 * The actual UI (caption bar, settings drawer) lives in OverlayService which draws a
 * TYPE_APPLICATION_OVERLAY window — transparent over all other apps.
 *
 * MainActivity is re-launched when the user taps the persistent notification,
 * allowing them to re-check permissions or stop the overlay.
 */
class MainActivity : ComponentActivity() {

    // ── Permission launchers ──────────────────────────────────────────────────

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { tryLaunchOverlay() }

    private val requestStorage = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { tryLaunchOverlay() }

    // Overlay permission cannot be requested via the normal launcher —
    // Android requires sending the user to a dedicated Settings screen.
    private val overlaySettingsResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { tryLaunchOverlay() }

    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme {
                PermissionGatewayScreen(
                    onRequestOverlay    = { openOverlaySettings() },
                    onRequestMic        = { requestMic.launch(Manifest.permission.RECORD_AUDIO) },
                    onRequestStorage    = { requestStoragePermission() },
                    onStartOverlay      = { launchOverlayAndFinish() },
                    onStopOverlay       = { OverlayService.stop(this) },
                    hasOverlayPermission = ::hasOverlayPermission,
                    hasMicPermission    = ::hasMicPermission,
                    hasStoragePermission = ::hasStoragePermission,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-evaluate after returning from the overlay Settings screen.
        tryLaunchOverlay()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Permission checks
    // ─────────────────────────────────────────────────────────────────────────

    private fun hasOverlayPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            Settings.canDrawOverlays(this)
        else true

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasStoragePermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Actions
    // ─────────────────────────────────────────────────────────────────────────

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlaySettingsResult.launch(intent)
    }

    private fun requestStoragePermission() {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE
        requestStorage.launch(perm)
    }

    /**
     * If the two critical permissions are satisfied (overlay + mic), start the
     * service and close MainActivity. The overlay is now running independently.
     */
    private fun tryLaunchOverlay() {
        if (hasOverlayPermission() && hasMicPermission()) {
            launchOverlayAndFinish()
        }
        // else: let the UI (PermissionGatewayScreen) guide the user
    }

    private fun launchOverlayAndFinish() {
        OverlayService.start(this)
        finish()   // MainActivity is no longer needed — overlay runs in service
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Permission Gateway UI
// ─────────────────────────────────────────────────────────────────────────────

private val BgDark    = Color(0xFF0A0A0A)
private val AccentBlue = Color(0xFF0078D4)
private val TextWhite  = Color(0xFFFFFFFF)
private val TextMuted  = Color(0xFF888888)
private val CardBg     = Color(0xFF1A1A1A)
private val GreenOk    = Color(0xFF00C853)
private val RedMissing = Color(0xFFFF4444)

@Composable
private fun PermissionGatewayScreen(
    onRequestOverlay:     () -> Unit,
    onRequestMic:         () -> Unit,
    onRequestStorage:     () -> Unit,
    onStartOverlay:       () -> Unit,
    onStopOverlay:        () -> Unit,
    hasOverlayPermission: () -> Boolean,
    hasMicPermission:     () -> Boolean,
    hasStoragePermission: () -> Boolean,
) {
    // Re-check on every recompose (e.g. after returning from Settings).
    val overlayOk  = hasOverlayPermission()
    val micOk      = hasMicPermission()
    val storageOk  = hasStoragePermission()
    val allCritical = overlayOk && micOk

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))

        // Header
        Text(
            text       = "Live Captions",
            fontSize   = 26.sp,
            fontWeight = FontWeight.Bold,
            color      = TextWhite
        )
        Text(
            text      = "Grant the permissions below to start the caption overlay.",
            fontSize  = 14.sp,
            color     = TextMuted,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        // ── Permission cards ──────────────────────────────────────────────────

        PermissionCard(
            title       = "Display over other apps",
            description = "Required to draw the caption bar over your screen. " +
                    "Tap 'Grant' → enable 'Allow display over other apps'.",
            granted     = overlayOk,
            required    = true,
            onGrant     = onRequestOverlay
        )

        PermissionCard(
            title       = "Microphone",
            description = "Required to capture and transcribe your speech.",
            granted     = micOk,
            required    = true,
            onGrant     = onRequestMic
        )

        PermissionCard(
            title       = "Audio files (optional)",
            description = "Allows transcribing audio files from your device storage.",
            granted     = storageOk,
            required    = false,
            onGrant     = onRequestStorage
        )

        Spacer(Modifier.weight(1f))

        // ── Start / Stop buttons ──────────────────────────────────────────────

        if (allCritical) {
            Button(
                onClick = onStartOverlay,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
            ) {
                Text(
                    "Start Overlay",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextWhite
                )
            }
        } else {
            Text(
                text     = "Grant the required permissions above to continue.",
                fontSize = 13.sp,
                color    = TextMuted,
                textAlign = TextAlign.Center
            )
        }

        TextButton(onClick = onStopOverlay) {
            Text("Stop overlay if running", color = TextMuted, fontSize = 13.sp)
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PermissionCard(
    title:       String,
    description: String,
    granted:     Boolean,
    required:    Boolean,
    onGrant:     () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBg, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Status dot
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(
                    if (granted) GreenOk else if (required) RedMissing else TextMuted,
                    androidx.compose.foundation.shape.CircleShape
                )
        )

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextWhite)
                if (required) {
                    Text("Required", fontSize = 10.sp, color = RedMissing,
                        modifier = Modifier
                            .background(RedMissing.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp))
                }
            }
            Text(description, fontSize = 12.sp, color = TextMuted, lineHeight = 17.sp)
        }

        if (!granted) {
            TextButton(
                onClick = onGrant,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Grant", color = AccentBlue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Text("✓", color = GreenOk, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}