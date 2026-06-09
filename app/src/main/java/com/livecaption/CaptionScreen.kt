package com.livecaption

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ── Tokens ────────────────────────────────────────────────────────────────────
private val SurfaceGlass = Color(0xF2F5F5F5)
private val SurfaceDark  = Color(0xF0121212)
private val AccentBlue   = Color(0xFF0078D4)
private val AccentGreen  = Color(0xFF00C853)
private val AccentRed    = Color(0xFFFF4444)
private val TextPrimary  = Color(0xFF111111)
private val TextMuted    = Color(0xFF888888)
private val PillBg       = Color(0xCC1E1E1E)
private val DividerColor = Color(0xFF2A2A2A)

private val LANGUAGES = listOf(
    "English","Spanish","French","German","Italian",
    "Portuguese","Russian","Chinese","Japanese","Hindi"
)

// ─────────────────────────────────────────────────────────────────────────────
// 1. CaptionBar — lives in its own WindowManager window (Window 1)
//
//    Drag handle at top: vertical drag moves the entire WindowManager window
//    by calling onDrag(dy) which updates WindowManager.LayoutParams.y.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun CaptionBar(
    viewModel: CaptionViewModel,
    onDrag:    (dy: Float) -> Unit
) {
    val caption     by viewModel.caption.collectAsState()
    val audioLevel  by viewModel.audioLevel.collectAsState()
    val modelReady  by viewModel.modelReady.collectAsState()
    val statusMsg   by viewModel.statusMsg.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val currentLang by viewModel.currentLang.collectAsState()
    val dlProgress  by viewModel.downloadProgress.collectAsState()
    val historyLines by viewModel.historyLines.collectAsState()
    val toastEvent  by viewModel.toastEvent.collectAsState()
    val fileState   by viewModel.fileTranscribeState.collectAsState()

    var historyOpen by remember { mutableStateOf(false) }

    // Toast
    var toastText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toastEvent) {
        val event = toastEvent ?: return@LaunchedEffect
        toastText = event.message
        viewModel.consumeToast()
        delay(3500)
        toastText = null
    }

    // Root Column — window height = sum of children
    Column(modifier = Modifier.fillMaxWidth()) {

        // History panel — slides above the caption card
        AnimatedVisibility(
            visible = historyOpen,
            enter   = slideInVertically(initialOffsetY = { it }, animationSpec = tween(200)),
            exit    = slideOutVertically(targetOffsetY  = { it }, animationSpec = tween(160))
        ) {
            HistoryPanel(
                lines    = historyLines,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        // Toast
        AnimatedVisibility(
            visible = toastText != null,
            enter   = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit    = fadeOut()
        ) {
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    toastText ?: "",
                    fontSize = 12.sp, color = Color.White,
                    modifier = Modifier
                        .background(Color(0xEE1E1E1E), RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        // Caption card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(SurfaceGlass),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // ── Drag handle strip ──────────────────────────────────────────────
            // Captures vertical drag and forwards dy to OverlayService which
            // calls WindowManager.updateViewLayout() to move the window.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { _, dragAmount ->
                            onDrag(dragAmount.y)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                // Visual drag indicator — three dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(3) {
                        Box(
                            Modifier.size(4.dp).clip(CircleShape)
                                .background(TextMuted.copy(alpha = 0.4f))
                        )
                    }
                }
            }

            // ── Content ────────────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Status row
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val dotAlpha by rememberInfiniteTransition(label = "dot")
                        .animateFloat(
                            initialValue  = 0.3f, targetValue = 1f,
                            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
                            label         = "da"
                        )
                    Box(
                        Modifier.size(6.dp).clip(CircleShape).background(
                            if (modelReady) AccentGreen.copy(alpha = dotAlpha)
                            else TextMuted.copy(alpha = dotAlpha)
                        )
                    )
                    Text(
                        text = when {
                            dlProgress in 0..99 -> "Downloading… $dlProgress%"
                            dlProgress == 100   -> "Extracting…"
                            modelReady          -> "Live · $currentLang"
                            else                -> statusMsg
                        },
                        fontSize      = 11.sp, fontWeight = FontWeight.SemiBold,
                        color         = TextMuted, letterSpacing = 0.3.sp,
                        maxLines      = 1, overflow = TextOverflow.Ellipsis,
                        modifier      = Modifier.weight(1f)
                    )
                    if (modelReady) LevelMeter(rms = audioLevel)
                    // History button
                    SmallIconButton(
                        contentDescription = if (historyOpen) "Hide history" else "Show history",
                        tint  = if (historyOpen) AccentBlue else TextMuted,
                        onClick = { historyOpen = !historyOpen }
                    ) { HistoryIcon() }
                    // Record button
                    SmallIconButton(
                        contentDescription = if (isRecording) "Stop" else "Record",
                        tint  = if (isRecording) AccentRed else TextMuted,
                        onClick = {
                            if (isRecording) viewModel.stopRecordingAndSave()
                            else viewModel.startRecording()
                        }
                    ) { RecordIcon(active = isRecording) }
                }

                // Download bar
                if (dlProgress in 0..100) {
                    LinearProgressIndicator(
                        progress   = { if (dlProgress >= 100) 1f else dlProgress / 100f },
                        modifier   = Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)),
                        color      = AccentBlue,
                        trackColor = AccentBlue.copy(alpha = 0.15f)
                    )
                }

                // Caption text
                when {
                    !modelReady && dlProgress < 0 -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = AccentBlue)
                        Text(statusMsg, fontSize = 18.sp, color = TextPrimary.copy(alpha = 0.6f))
                    }
                    modelReady -> Text(
                        text       = caption.ifBlank { "Listening…" },
                        fontSize   = 22.sp, fontWeight = FontWeight.Medium,
                        color      = TextPrimary, lineHeight = 31.sp,
                        maxLines   = 2, overflow = TextOverflow.Ellipsis
                    )
                }

                // File transcription status
                when (val fs = fileState) {
                    is FileTranscribeState.Running -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = AccentBlue)
                        val pct = if (fs.total > 0) " ${fs.done * 100 / fs.total}%" else ""
                        Text("Transcribing…$pct", fontSize = 12.sp, color = AccentBlue)
                    }
                    is FileTranscribeState.Done -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("✓", fontSize = 12.sp, color = AccentGreen)
                        Text("Saved: ${fs.file.name}", fontSize = 12.sp, color = AccentGreen,
                            modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis, maxLines = 1)
                        Text("✕", fontSize = 12.sp, color = TextMuted,
                            modifier = Modifier.clickable { viewModel.dismissFileTranscribeState() })
                    }
                    is FileTranscribeState.Error -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("✕", fontSize = 12.sp, color = AccentRed)
                        Text(fs.message, fontSize = 12.sp, color = AccentRed,
                            modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis, maxLines = 1)
                        Text("✕", fontSize = 12.sp, color = TextMuted,
                            modifier = Modifier.clickable { viewModel.dismissFileTranscribeState() })
                    }
                    else -> {}
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. SettingsPill — lives in its own WindowManager window (Window 2)
//
//    Always docked to the right edge. Draggable vertically.
//    Tap opens/closes the settings panel (Window 3).
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SettingsPill(
    isOpen:  Boolean,
    onTap:   () -> Unit,
    onDragY: (dy: Float) -> Unit
) {
    Box(
        modifier = Modifier
            .width(36.dp)
            .clip(RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp))
            .background(if (isOpen) AccentBlue.copy(alpha = 0.9f) else PillBg)
            .pointerInput(Unit) {
                // Single pointerInput block handles both tap and drag.
                // detectDragGestures alone swallows taps (it consumes ACTION_DOWN).
                // awaitEachGesture lets us decide: if total movement < threshold
                // treat it as a tap; otherwise stream drag deltas.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()

                    var totalDy   = 0f
                    var isDragging = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break

                        if (!change.pressed) {
                            // Finger lifted — if we never dragged, it's a tap
                            if (!isDragging) onTap()
                            break
                        }

                        val dy = change.position.y - change.previousPosition.y
                        totalDy += kotlin.math.abs(dy)

                        if (totalDy > 8f || isDragging) {
                            isDragging = true
                            onDragY(dy)
                            change.consume()
                        }
                    }
                }
            }
            .padding(vertical = 20.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            repeat(3) {
                Box(
                    Modifier.width(14.dp).height(1.5.dp)
                        .background(Color.White, RoundedCornerShape(1.dp))
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. SettingsPanel — lives in its own WindowManager window (Window 3)
//
//    MATCH_PARENT height, RIGHT gravity → always covers the full right side
//    of the screen correctly, regardless of where the caption window is.
//    AnimatedVisibility slides it in/out based on settingsStateHolder.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SettingsPanel(
    visibleState: androidx.compose.runtime.MutableState<Boolean>,
    viewModel:    CaptionViewModel,
    onPickFile:   () -> Unit,
    onClose:      () -> Unit
) {
    val isVisible  by visibleState
    val currentLang by viewModel.currentLang.collectAsState()

    // Tap-away backdrop — only present when open
    Box(modifier = Modifier.fillMaxSize()) {
        if (isVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x55000000))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onClose() }
            )
        }

        // Drawer panel — slides in from the right
        AnimatedVisibility(
            visible  = isVisible,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter    = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec  = tween(220, easing = FastOutSlowInEasing)
            ),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(180, easing = FastOutLinearInEasing)
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(300.dp)
                    .clip(RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                    .background(SurfaceDark)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Header
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Text("Settings", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Box(
                            modifier = Modifier.size(28.dp).clip(CircleShape)
                                .background(Color(0xFF2A2A2A)).clickable { onClose() },
                            contentAlignment = Alignment.Center
                        ) { Text("✕", fontSize = 12.sp, color = TextMuted) }
                    }

                    HorizontalDivider(color = DividerColor)

                    // Language
                    SettingSection("LANGUAGE") {
                        LANGUAGES.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pair.forEach { lang ->
                                    SettingChip(
                                        label    = lang,
                                        selected = lang == currentLang,
                                        onClick  = {
                                            viewModel.switchLanguage(lang)
                                            onClose()
                                        }
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = DividerColor)

                    // File transcription
                    SettingSection("TRANSCRIBE AUDIO FILE") {
                        Text(
                            "Pick an audio file to transcribe offline.",
                            fontSize = 12.sp, color = Color(0xFF666666), lineHeight = 18.sp
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp)).background(AccentBlue)
                                .clickable { onPickFile(); onClose() }
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Text("Upload Audio File", fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold, color = Color.White)
                        }
                    }

                    HorizontalDivider(color = DividerColor)

                    SettingSection("ABOUT") {
                        Text(
                            "Live Captions\nOffline speech-to-text.\nNothing leaves your device.",
                            fontSize = 12.sp, color = Color(0xFF555555), lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared sub-composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun HistoryPanel(lines: List<String>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }
    Box(modifier = modifier.heightIn(max = 240.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xF0181818))) {
        if (lines.isEmpty()) {
            Text("History will appear here once speech is detected.",
                fontSize = 12.sp, color = TextMuted, modifier = Modifier.padding(14.dp))
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(lines) { line ->
                    Text(line, fontSize = 13.sp, lineHeight = 19.sp, color = Color(0xCCFFFFFF))
                }
            }
        }
    }
}

@Composable
fun SettingSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            color = Color(0xFF555555), letterSpacing = 1.2.sp)
        content()
    }
}

@Composable
fun SettingChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) AccentBlue.copy(alpha = 0.18f) else Color(0xFF1E1E1E))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(label, fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) AccentBlue else Color(0xFF666666))
    }
}

@Composable
fun SmallIconButton(contentDescription: String, tint: Color = TextMuted, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onClick), Alignment.Center) {
        CompositionLocalProvider(LocalContentColor provides tint) { content() }
    }
}

@Composable
fun LevelMeter(rms: Float) {
    val animLevel by animateFloatAsState(rms, tween(80), label = "lvl")
    Row(
        modifier              = Modifier.height(12.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment     = Alignment.Bottom
    ) {
        listOf(0.05f to 5.dp, 0.20f to 9.dp, 0.45f to 12.dp).forEach { (thresh, litH) ->
            val lit = animLevel > thresh
            val h by animateDpAsState(if (lit) litH else 3.dp, tween(80), label = "bar")
            Box(Modifier.width(3.dp).height(h).clip(RoundedCornerShape(1.5.dp))
                .background(if (lit) AccentGreen else Color(0xFF333333)))
        }
    }
}

@Composable
private fun HistoryIcon() {
    Column(
        modifier            = Modifier.size(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.Start
    ) {
        repeat(3) { i ->
            Box(Modifier.width(if (i == 2) 10.dp else 14.dp).height(1.5.dp)
                .background(LocalContentColor.current, RoundedCornerShape(1.dp)))
        }
    }
}

@Composable
private fun RecordIcon(active: Boolean) {
    Box(Modifier.size(10.dp).clip(CircleShape)
        .background(if (active) AccentRed else LocalContentColor.current))
}