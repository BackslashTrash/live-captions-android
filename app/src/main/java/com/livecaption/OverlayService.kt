package com.livecaption

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.*
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.compositionContext
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.*

/**
 * OverlayService — three independent WindowManager windows:
 *
 *  1. CAPTION WINDOW   — draggable card showing live transcription text.
 *                        WRAP_CONTENT size, initially at bottom-center.
 *                        Drag anywhere on screen vertically.
 *
 *  2. PILL WINDOW      — small hamburger tab docked to the right edge.
 *                        Draggable vertically, always snaps back to right edge.
 *                        Tap opens/closes the settings window.
 *
 *  3. SETTINGS WINDOW  — full-height drawer anchored to the RIGHT edge of the
 *                        screen. Slides in/out. Completely separate window so
 *                        it always covers the full screen height correctly,
 *                        independent of where the caption window is positioned.
 *
 * Why three windows instead of one:
 *   A single WRAP_CONTENT window cannot simultaneously be "as small as the
 *   caption bar" AND "full-screen height for the settings drawer". Mixing them
 *   in one window caused the settings panel to appear in the wrong position
 *   (relative to the small window, not the screen edge). Separate windows each
 *   have their own gravity/position and measure correctly.
 */
class OverlayService : Service(),
    ViewModelStoreOwner,
    LifecycleOwner,
    SavedStateRegistryOwner {

    companion object {
        const val NOTIF_CHANNEL_ID = "livecaption_overlay"
        const val NOTIF_ID = 1
        private const val TAG = "LC_OverlayService"

        @Volatile var activeViewModel: CaptionViewModel? = null
            private set

        // Instance reference so trampoline activities can reach the running service.
        @Volatile private var instance: OverlayService? = null

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else
                context.startService(intent)
        }

        fun stop(context: Context) =
            context.stopService(Intent(context, OverlayService::class.java))

        /**
         * Called by MediaProjectionActivity with the consent dialog result.
         * Forwards to the running service instance (no-op if service is gone).
         */
        fun onProjectionResult(resultCode: Int, data: Intent?) {
            instance?.handleProjectionResult(resultCode, data)
                ?: Log.w(TAG, "onProjectionResult but service not running")
        }
    }

    // ── LifecycleOwner / ViewModelStoreOwner / SavedStateRegistryOwner ────────
    private val lifecycleRegistry     = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val vmStore = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = vmStore

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    // ── Service state ─────────────────────────────────────────────────────────
    private var wm: WindowManager? = null
    private var captionView:  ComposeView? = null
    private var pillView:     ComposeView? = null
    private var settingsView: ComposeView? = null

    private lateinit var captionParams:  WindowManager.LayoutParams
    private lateinit var pillParams:     WindowManager.LayoutParams
    private lateinit var settingsParams: WindowManager.LayoutParams

    private lateinit var viewModel: CaptionViewModel
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Shared state: is settings panel open?
    // Written from the pill window touch, read by the settings window.
    @Volatile private var settingsVisible = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        viewModel = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )[CaptionViewModel::class.java]
        activeViewModel = viewModel

        // Wire the projection request: the toggle button asks the ViewModel,
        // the ViewModel asks us, we launch the consent trampoline.
        viewModel.onRequestProjection = { MediaProjectionActivity.launch(this) }

        createNotificationChannel()
        startForegroundCompat(withProjection = false)

        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        // Initialise all three windows
        showCaptionWindow()
        showPillWindow()
        showSettingsWindow()

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        activeViewModel = null
        instance = null
        projection?.stop()
        projection = null
        removeAllWindows()
        vmStore.clear()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────────────────────────────────────
    // MediaProjection (device audio capture)
    // ─────────────────────────────────────────────────────────────────────────

    private var projection: MediaProjection? = null

    /**
     * Receives the consent result from MediaProjectionActivity.
     *
     * Order matters on API 29+ (strictly enforced on 34+):
     *   1. startForeground with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION active
     *   2. THEN getMediaProjection(resultCode, data)
     *   3. THEN registerCallback (mandatory on 34+ before capture starts)
     * Doing these out of order throws SecurityException / IllegalStateException.
     */
    private fun handleProjectionResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.i(TAG, "Projection consent denied / cancelled")
            // Stay on mic — no state change needed, just inform the user.
            activeViewModel?.let { /* mic remains active; nothing to undo */ }
            return
        }

        try {
            // 1. Upgrade foreground type BEFORE getMediaProjection
            startForegroundCompat(withProjection = true)

            // 2. Obtain the projection from the one-time consent token
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(resultCode, data)
            if (proj == null) {
                Log.e(TAG, "getMediaProjection returned null")
                return
            }

            // 3. Register callback (required on API 34+ before starting capture).
            //    onStop fires when the user revokes via the status bar chip or
            //    the system kills the projection — fall back to mic cleanly.
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection stopped by system/user — reverting to mic")
                    projection = null
                    viewModel.switchToMic()
                    startForegroundCompat(withProjection = false)
                }
            }, Handler(Looper.getMainLooper()))

            projection?.stop()   // release any previous session
            projection = proj
            viewModel.switchToDevice(proj)
            Log.i(TAG, "Projection granted — device audio capture starting")

        } catch (e: Exception) {
            Log.e(TAG, "Projection setup failed: ${e.message}", e)
            startForegroundCompat(withProjection = false)
        }
    }

    /**
     * startForeground with explicit service types on API 29+.
     * The mediaProjection type may only be active while a projection session
     * is in use; we drop back to microphone-only when it ends.
     */
    private fun startForegroundCompat(withProjection: Boolean) {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (withProjection) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(NOTIF_ID, notification, types)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Window 1: Caption bar — draggable, WRAP_CONTENT, bottom-center initially
    // ─────────────────────────────────────────────────────────────────────────

    private fun showCaptionWindow() {
        captionParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE: don't steal keyboard focus from apps below.
            // NOT_TOUCH_MODAL: touches outside the window bounds go to the app below.
            // LAYOUT_IN_SCREEN: measure against full screen not the safe area.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 80
        }

        captionView = makeComposeView {
            CaptionBar(
                viewModel = viewModel,
                onDrag    = { dy -> moveCaptionWindow(dy) }
            )
        }
        wm?.addView(captionView, captionParams)
    }

    /** Called from the drag gesture on the caption bar. dy is pixels. */
    fun moveCaptionWindow(dy: Float) {
        val wm = wm ?: return
        // We use Gravity.BOTTOM so positive y = distance from bottom.
        // Dragging up (negative dy in screen coords) means increasing y.
        captionParams.y = (captionParams.y - dy.toInt()).coerceAtLeast(0)
        wm.updateViewLayout(captionView, captionParams)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Window 2: Settings pill — draggable vertically, docked to right edge
    // ─────────────────────────────────────────────────────────────────────────

    private fun showPillWindow() {
        pillParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            y = 400   // initial vertical position from top
        }

        pillView = makeComposeView {
            SettingsPill(
                isOpen  = settingsVisible,
                onTap   = { toggleSettings() },
                onDragY = { dy -> movePillWindow(dy) }
            )
        }
        wm?.addView(pillView, pillParams)
    }

    fun movePillWindow(dy: Float) {
        val wm = wm ?: return
        pillParams.y = (pillParams.y + dy.toInt()).coerceAtLeast(0)
        wm.updateViewLayout(pillView, pillParams)
    }

    private fun toggleSettings() {
        settingsVisible = !settingsVisible
        settingsStateHolder.value = settingsVisible

        // KEY FIX: when closed, set FLAG_NOT_TOUCHABLE so the full-screen window
        // passes all touches through to apps underneath.
        // When open, remove FLAG_NOT_TOUCHABLE so the backdrop and drawer intercept touches.
        if (settingsVisible) {
            settingsParams.flags = settingsParams.flags and
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            settingsParams.flags = settingsParams.flags or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        wm?.updateViewLayout(settingsView, settingsParams)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Window 3: Settings drawer — full right-side panel, independent window
    // ─────────────────────────────────────────────────────────────────────────

    // MutableState shared between the pill (writer) and settings window (reader).
    // Using androidx.compose.runtime.mutableStateOf at service level so both
    // ComposeViews share the same state object across their compositions.
    private val settingsStateHolder = mutableStateOf(false)

    private fun showSettingsWindow() {
        settingsParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Start NOT_TOUCHABLE — toggleSettings() removes this flag when opened.
            // This is the key fix: when closed the window is full-screen but passes
            // every touch straight through. FLAG_NOT_TOUCH_MODAL alone is not enough
            // because the window bounds equal the screen, so all touches land inside.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        settingsView = makeComposeView {
            SettingsPanel(
                visibleState = settingsStateHolder,
                viewModel    = viewModel,
                onPickFile   = { FilePickerActivity.launch(this@OverlayService) },
                onClose      = {
                    // Route through toggleSettings so FLAG_NOT_TOUCHABLE is always
                    // restored — don't just set the state, also update the window flags.
                    if (settingsVisible) toggleSettings()
                }
            )
        }
        wm?.addView(settingsView, settingsParams)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun makeComposeView(content: @Composable () -> Unit): ComposeView {
        return ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeViewModelStoreOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            val recomposer = Recomposer(AndroidUiDispatcher.Main)
            compositionContext = recomposer
            serviceScope.launch(AndroidUiDispatcher.Main) {
                recomposer.runRecomposeAndApplyChanges()
            }
            setContent {
                androidx.compose.material3.MaterialTheme {
                    content()
                }
            }
        }
    }

    private fun removeAllWindows() {
        listOf(captionView, pillView, settingsView).forEach { view ->
            view?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        }
        captionView  = null
        pillView     = null
        settingsView = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Foreground notification
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID, "Live Captions Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows while Live Captions overlay is active"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Live Captions")
            .setContentText("Overlay active — tap to open settings")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true).setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}