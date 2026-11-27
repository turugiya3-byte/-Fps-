package com.example.simplevideoplayer

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class GestureHudType {
    BRIGHTNESS, VOLUME, SEEK, ZOOM, PRESET, NOISE, UPCONVERT
}

private enum class DragMode {
    NONE, SEEK, BRIGHTNESS, VOLUME
}

private enum class ViewScaleMode(val label: String, val zoomScale: Float) {
    FIT("フィット", 1.0f),
    CROP("クロップ", 1.0f),
    STRETCH("ストレッチ", 1.0f),
    ZOOM_125("125%拡大", 1.25f),
    ZOOM_150("150%拡大", 1.5f);

    fun next(): ViewScaleMode {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MxStyleVideoPlayerScreen()
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun MxStyleVideoPlayerScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build()
    }

    val playlist = remember { mutableStateListOf<PlaylistItem>() }
    var currentIndex by remember { mutableIntStateOf(-1) }
    var autoPlayNext by remember { mutableStateOf(true) }

    var showNetworkDialog by remember { mutableStateOf(false) }
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var isScanningNetwork by remember { mutableStateOf(false) }
    val discoveredDevices = remember { mutableStateListOf<DiscoveredNetworkDevice>() }

    // エクスプローラ状態
    var activeDevice by remember { mutableStateOf<DiscoveredNetworkDevice?>(null) }
    val explorerEntries = remember { mutableStateListOf<ExplorerEntry>() }
    var isBrowsingExplorer by remember { mutableStateOf(false) }
    var explorerError by remember { mutableStateOf<String?>(null) }
    val dlnaBackStack = remember { mutableStateListOf<Pair<String, String>>() }
    var currentDlnaId by remember { mutableStateOf("0") }
    var currentDlnaLabel by remember { mutableStateOf("ルート") }
    var currentSmbSubPath by remember { mutableStateOf("") }
    var smbCredentials by remember { mutableStateOf(SmbCredentials()) }

    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var videoTitle by remember { mutableStateOf("動画を選択してください") }

    var controlsVisible by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var viewScaleMode by remember { mutableStateOf(ViewScaleMode.FIT) }
    var pinchScale by remember { mutableFloatStateOf(1.0f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var interactionTick by remember { mutableIntStateOf(0) }

    var selectedPresetLabel by remember { mutableStateOf(FilterPreset.STANDARD.label) }
    var saturation by remember { mutableFloatStateOf(FilterPreset.STANDARD.saturation) }
    var contrast by remember { mutableFloatStateOf(FilterPreset.STANDARD.contrast) }
    var sharpness by remember { mutableFloatStateOf(FilterPreset.STANDARD.sharpness) }
    var clarity by remember { mutableFloatStateOf(FilterPreset.STANDARD.clarity) }
    var blackLift by remember { mutableFloatStateOf(FilterPreset.STANDARD.blackLift) }
    var noiseLevel by remember { mutableStateOf(NoiseReductionLevel.OFF) }
    var upconvertMode by remember { mutableStateOf(UpconvertMode.OFF) }

    var activeHud by remember { mutableStateOf<GestureHudType?>(null) }
    var brightnessLevel by remember {
        mutableFloatStateOf(
            activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
        )
    }
    val maxVolume = remember {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }
    var volumeLevel by remember {
        mutableFloatStateOf(
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume.toFloat()
        )
    }
    var seekPreviewMs by remember { mutableLongStateOf(0L) }
    var seekDeltaSec by remember { mutableIntStateOf(0) }

    val playItemAtIndex: (Int) -> Unit = { idx ->
        if (idx in playlist.indices) {
            currentIndex = idx
            val item = playlist[idx]
            videoTitle = item.title
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(item.uriString)))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            controlsVisible = true
            interactionTick++
        }
    }

    val addAndPlayUri: (Uri, String, String) -> Unit = { uri, title, sourceType ->
        val existingIdx = playlist.indexOfFirst { it.uriString == uri.toString() }
        val targetIdx = if (existingIdx >= 0) {
            existingIdx
        } else {
            playlist.add(
                PlaylistItem(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    uriString = uri.toString(),
                    sourceType = sourceType
                )
            )
            playlist.lastIndex
        }
        playItemAtIndex(targetIdx)
    }

    val loadExplorerDirectory: (DiscoveredNetworkDevice, String, String, SmbCredentials) -> Unit =
        { dev, targetIdOrPath, label, creds ->
            scope.launch {
                activeDevice = dev
                isBrowsingExplorer = true
                explorerError = null
                explorerEntries.clear()

                when (dev.protocol) {
                    NetworkProtocolType.DLNA -> {
                        currentDlnaId = targetIdOrPath
                        currentDlnaLabel = label
                        NetworkDiscoveryManager.browseDlna(dev, targetIdOrPath)
                            .onSuccess { list -> explorerEntries.addAll(list) }
                            .onFailure { err -> explorerError = err.message ?: "DLNA一覧の取得に失敗しました" }
                    }
                    NetworkProtocolType.SMB -> {
                        currentSmbSubPath = targetIdOrPath
                        NetworkDiscoveryManager.browseSmb(dev.host, creds, targetIdOrPath)
                            .onSuccess { list -> explorerEntries.addAll(list) }
                            .onFailure { err -> explorerError = err.message ?: "SMB一覧の取得に失敗しました" }
                    }
                }
                isBrowsingExplorer = false
            }
        }

    val applyPreset: (FilterPreset) -> Unit = { preset ->
        selectedPresetLabel = preset.label
        saturation = preset.saturation
        contrast = preset.contrast
        sharpness = preset.sharpness
        clarity = preset.clarity
        blackLift = preset.blackLift
        activeHud = GestureHudType.PRESET
        interactionTick++
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val firstNewIndex = playlist.size
            uris.forEach { uri ->
                if (playlist.none { it.uriString == uri.toString() }) {
                    playlist.add(
                        PlaylistItem(
                            id = UUID.randomUUID().toString(),
                            title = resolveTitle(context, uri),
                            uriString = uri.toString(),
                            sourceType = "ローカル"
                        )
                    )
                }
            }
            val targetIdx = playlist.indexOfFirst { it.uriString == uris.first().toString() }
                .takeIf { it >= 0 } ?: firstNewIndex
            playItemAtIndex(targetIdx)
        }
    }

    val runNetworkScan: () -> Unit = {
        scope.launch {
            isScanningNetwork = true
            discoveredDevices.clear()
            val devices = NetworkDiscoveryManager.discoverDevices(context)
            discoveredDevices.addAll(devices)
            isScanningNetwork = false
        }
    }

    LaunchedEffect(saturation, contrast, sharpness, clarity, blackLift, noiseLevel, upconvertMode) {
        val effects = buildVideoEffectsList(
            context = context,
            saturation = saturation,
            contrast = contrast,
            sharpness = sharpness,
            clarity = clarity,
            blackLift = blackLift,
            noiseLevel = noiseLevel,
            upconvertMode = upconvertMode
        )
        exoPlayer.setVideoEffects(effects)
    }

    LaunchedEffect(isFullscreen) {
        activity?.window?.let { win ->
            WindowCompat.setDecorFitsSystemWindows(win, !isFullscreen)
            val controller = WindowInsetsControllerCompat(win, win.decorView)
            if (isFullscreen) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                win.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
                win.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            }
        }
    }

    DisposableEffect(exoPlayer, autoPlayNext) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) interactionTick++
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    durationMs = exoPlayer.duration.coerceAtLeast(0L)
                } else if (playbackState == Player.STATE_ENDED && autoPlayNext) {
                    val nextIdx = currentIndex + 1
                    if (nextIdx in playlist.indices) {
                        playItemAtIndex(nextIdx)
                    }
                }
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            currentPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            durationMs = exoPlayer.duration.coerceAtLeast(0L)
            delay(250L)
        }
    }

    LaunchedEffect(controlsVisible, isPlaying, isLocked, showFilterSheet, showPlaylistDialog, showNetworkDialog, interactionTick) {
        if (controlsVisible && isPlaying && !isLocked && !showFilterSheet && !showPlaylistDialog && !showNetworkDialog) {
            delay(4000L)
            controlsVisible = false
        }
    }

    LaunchedEffect(
        activeHud,
        brightnessLevel,
        volumeLevel,
        seekPreviewMs,
        pinchScale,
        selectedPresetLabel,
        noiseLevel,
        upconvertMode
    ) {
        if (activeHud != null) {
            delay(1200L)
            activeHud = null
        }
    }

    val effectiveScale = (viewScaleMode.zoomScale * pinchScale).coerceIn(0.5f, 4.0f)
    val resizeModeValue = when (viewScaleMode) {
        ViewScaleMode.FIT, ViewScaleMode.ZOOM_125, ViewScaleMode.ZOOM_150 ->
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        ViewScaleMode.CROP ->
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        ViewScaleMode.STRETCH ->
            AspectRatioFrameLayout.RESIZE_MODE_FILL
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                controlsVisible = !controlsVisible
                interactionTick++
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = effectiveScale
                    scaleY = effectiveScale
                    translationX = panOffset.x
                    translationY = panOffset.y
                }
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        resizeMode = resizeModeValue
                    }
                },
                update = { view ->
                    view.resizeMode = resizeModeValue
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (!isLocked) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val halfWidthPx = constraints.maxWidth * 0.5f
                val dragHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f) * 0.65f
                val dragWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f) * 0.75f

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(isLocked, durationMs, maxVolume, pinchScale) {
                            awaitPointerEventScope {
                                var mode = DragMode.NONE
                                var startX = 0f
                                var startSeekMs = 0L
                                var totalDx = 0f
                                var totalDy = 0f
                                var isPinching = false
                                var prevCentroid = Offset.Zero
                                var prevSpan = 0f

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressedChanges = event.changes.filter { it.pressed }

                                    if (pressedChanges.isEmpty()) {
                                        if (mode == DragMode.SEEK && durationMs > 0L) {
                                            exoPlayer.seekTo(seekPreviewMs)
                                            currentPositionMs = seekPreviewMs
                                        }
                                        mode = DragMode.NONE
                                        isPinching = false
                                        totalDx = 0f
                                        totalDy = 0f
                                        continue
                                    }

                                    if (pressedChanges.size >= 2) {
                                        mode = DragMode.NONE
                                        val p1 = pressedChanges[0].position
                                        val p2 = pressedChanges[1].position
                                        val centroid = (p1 + p2) * 0.5f
                                        val span = (p1 - p2).getDistance().coerceAtLeast(1f)

                                        if (!isPinching) {
                                            isPinching = true
                                            prevCentroid = centroid
                                            prevSpan = span
                                        } else {
                                            val zoomFactor = span / prevSpan
                                            val nextZoom = (pinchScale * zoomFactor).coerceIn(1.0f, 3.0f)
                                            pinchScale = nextZoom
                                            if (nextZoom > 1.01f) {
                                                val panDelta = centroid - prevCentroid
                                                panOffset = Offset(
                                                    x = (panOffset.x + panDelta.x).coerceIn(-600f, 600f),
                                                    y = (panOffset.y + panDelta.y).coerceIn(-400f, 400f)
                                                )
                                            } else {
                                                panOffset = Offset.Zero
                                            }
                                            prevCentroid = centroid
                                            prevSpan = span
                                            activeHud = GestureHudType.ZOOM
                                        }

                                        pressedChanges.forEach { it.consume() }
                                        continue
                                    }

                                    isPinching = false
                                    val pointer = pressedChanges.first()
                                    val delta = pointer.position - pointer.previousPosition

                                    if (pointer.previousPressed.not()) {
                                        startX = pointer.position.x
                                        startSeekMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                                        seekPreviewMs = startSeekMs
                                        seekDeltaSec = 0
                                        totalDx = 0f
                                        totalDy = 0f
                                        mode = DragMode.NONE
                                    }

                                    if (delta != Offset.Zero) {
                                        if (pinchScale > 1.05f) {
                                            panOffset = Offset(
                                                x = (panOffset.x + delta.x).coerceIn(-600f, 600f),
                                                y = (panOffset.y + delta.y).coerceIn(-400f, 400f)
                                            )
                                            pointer.consume()
                                            continue
                                        }

                                        totalDx += delta.x
                                        totalDy += delta.y

                                        if (mode == DragMode.NONE) {
                                            val slop = 14f
                                            if (abs(totalDx) > slop || abs(totalDy) > slop) {
                                                mode = if (abs(totalDx) > abs(totalDy) * 1.2f) {
                                                    DragMode.SEEK
                                                } else if (startX < halfWidthPx) {
                                                    DragMode.BRIGHTNESS
                                                } else {
                                                    DragMode.VOLUME
                                                }
                                            }
                                        }

                                        when (mode) {
                                            DragMode.SEEK -> {
                                                if (durationMs > 0L) {
                                                    val seekDeltaMs = ((totalDx / dragWidthPx) * 60_000f).toLong()
                                                    val nextMs = (startSeekMs + seekDeltaMs).coerceIn(0L, durationMs)
                                                    seekPreviewMs = nextMs
                                                    seekDeltaSec = ((nextMs - startSeekMs) / 1000L).toInt()
                                                    activeHud = GestureHudType.SEEK
                                                }
                                                pointer.consume()
                                            }
                                            DragMode.BRIGHTNESS -> {
                                                val bDelta = -delta.y / dragHeightPx
                                                val next = (brightnessLevel + bDelta).coerceIn(0.05f, 1.0f)
                                                brightnessLevel = next
                                                activity?.window?.let { win ->
                                                    val params = win.attributes
                                                    params.screenBrightness = next
                                                    win.attributes = params
                                                }
                                                activeHud = GestureHudType.BRIGHTNESS
                                                pointer.consume()
                                            }
                                            DragMode.VOLUME -> {
                                                val vDelta = -delta.y / dragHeightPx
                                                val next = (volumeLevel + vDelta).coerceIn(0.0f, 1.0f)
                                                volumeLevel = next
                                                val targetVol = (next * maxVolume).roundToInt().coerceIn(0, maxVolume)
                                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                                                activeHud = GestureHudType.VOLUME
                                                pointer.consume()
                                            }
                                            DragMode.NONE -> Unit
                                        }
                                    }
                                }
                            }
                        }
                )
            }
        }

        AnimatedVisibility(
            visible = activeHud != null && !isLocked,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Row(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (activeHud) {
                    GestureHudType.BRIGHTNESS -> {
                        Icon(Icons.Default.Brightness6, contentDescription = null, tint = Color.White)
                        Text(
                            text = "輝度 ${(brightnessLevel * 100f).roundToInt()}%",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    GestureHudType.VOLUME -> {
                        val icon = if (volumeLevel <= 0.01f) Icons.Default.VolumeOff else Icons.Default.VolumeUp
                        Icon(icon, contentDescription = null, tint = Color.White)
                        Text(
                            text = "音量 ${(volumeLevel * 100f).roundToInt()}%",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    GestureHudType.SEEK -> {
                        Icon(Icons.Default.FastForward, contentDescription = null, tint = Color(0xFF38BDF8))
                        val sign = if (seekDeltaSec >= 0) "+" else ""
                        Text(
                            text = "${formatTime(seekPreviewMs)} ($sign${seekDeltaSec}秒)",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    GestureHudType.ZOOM -> {
                        Icon(Icons.Default.ZoomIn, contentDescription = null, tint = Color(0xFF4ADE80))
                        Text(
                            text = String.format(Locale.US, "拡大 %.2fx", effectiveScale),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    GestureHudType.PRESET -> {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = Color(0xFFFBBF24))
                        Text(
                            text = "プリセット: $selectedPresetLabel",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    GestureHudType.NOISE -> {
                        Icon(Icons.Default.BlurOn, contentDescription = null, tint = Color(0xFF60A5FA))
                        Text(
                            text = noiseLevel.label,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    GestureHudType.UPCONVERT -> {
                        Icon(Icons.Default.HighQuality, contentDescription = null, tint = Color(0xFF34D399))
                        Text(
                            text = upconvertMode.label,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    null -> Unit
                }
            }
        }

        if (isLocked) {
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 24.dp)
            ) {
                FilledTonalIconButton(
                    onClick = {
                        isLocked = false
                        controlsVisible = true
                        interactionTick++
                    },
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(Icons.Default.LockOpen, contentDescription = "ロック解除")
                }
            }
            return@Box
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.82f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.85f)
                            )
                        )
                    )
                    .then(if (!isFullscreen) Modifier.systemBarsPadding() else Modifier)
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconButton(
                            onClick = {
                                isLocked = true
                                controlsVisible = true
                                interactionTick++
                            }
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = "画面ロック", tint = Color.White)
                        }

                        Text(
                            text = videoTitle,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        FilledTonalButton(
                            onClick = {
                                showPlaylistDialog = true
                                interactionTick++
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.QueueMusic, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("リスト(${playlist.size})", fontSize = 12.sp)
                        }

                        FilledTonalButton(
                            onClick = {
                                activeDevice = null
                                explorerError = null
                                showNetworkDialog = true
                                if (discoveredDevices.isEmpty() && !isScanningNetwork) {
                                    runNetworkScan()
                                }
                                interactionTick++
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.FolderShared, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("SMB/DLNA", fontSize = 12.sp)
                        }

                        FilledTonalButton(
                            onClick = {
                                showFilterSheet = true
                                interactionTick++
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("微調整", fontSize = 12.sp)
                        }

                        FilledTonalButton(
                            onClick = {
                                viewScaleMode = viewScaleMode.next()
                                pinchScale = 1.0f
                                panOffset = Offset.Zero
                                interactionTick++
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.AspectRatio, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(viewScaleMode.label, fontSize = 12.sp)
                        }

                        IconButton(
                            onClick = {
                                isFullscreen = !isFullscreen
                                interactionTick++
                            }
                        ) {
                            Icon(
                                imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                contentDescription = "全画面切替",
                                tint = Color.White
                            )
                        }

                        FilledTonalButton(
                            onClick = { videoPickerLauncher.launch(arrayOf("video/*")) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("開く", fontSize = 12.sp)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(start = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterPreset.entries.forEach { preset ->
                            FilterChip(
                                selected = selectedPresetLabel == preset.label,
                                onClick = { applyPreset(preset) },
                                label = {
                                    Text(
                                        text = preset.label,
                                        fontSize = 12.sp,
                                        fontWeight = if (selectedPresetLabel == preset.label) {
                                            FontWeight.Bold
                                        } else {
                                            FontWeight.Normal
                                        }
                                    )
                                }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(start = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        UpconvertMode.entries.forEach { mode ->
                            FilterChip(
                                selected = upconvertMode == mode,
                                onClick = {
                                    upconvertMode = mode
                                    activeHud = GestureHudType.UPCONVERT
                                    interactionTick++
                                },
                                label = {
                                    Text(
                                        text = mode.label,
                                        fontSize = 12.sp,
                                        fontWeight = if (upconvertMode == mode) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            )
                        }

                        listOf(
                            NoiseReductionLevel.LARGE,
                            NoiseReductionLevel.MEDIUM,
                            NoiseReductionLevel.SMALL,
                            NoiseReductionLevel.OFF
                        ).forEach { level ->
                            FilterChip(
                                selected = noiseLevel == level,
                                onClick = {
                                    noiseLevel = level
                                    activeHud = GestureHudType.NOISE
                                    interactionTick++
                                },
                                label = {
                                    Text(
                                        text = level.label,
                                        fontSize = 12.sp,
                                        fontWeight = if (noiseLevel == level) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    IconButton(
                        onClick = {
                            if (currentIndex > 0) {
                                playItemAtIndex(currentIndex - 1)
                            }
                            interactionTick++
                        },
                        enabled = currentIndex > 0,
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "前の動画",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            val target = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                            exoPlayer.seekTo(target)
                            currentPositionMs = target
                            interactionTick++
                        },
                        modifier = Modifier
                            .size(52.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Replay10,
                            contentDescription = "10秒戻す",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    FilledIconButton(
                        onClick = {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            interactionTick++
                        },
                        modifier = Modifier.size(68.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "一時停止" else "再生",
                            modifier = Modifier.size(38.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            val maxPos = exoPlayer.duration.coerceAtLeast(0L)
                            val target = (exoPlayer.currentPosition + 10_000L).coerceAtMost(maxPos)
                            exoPlayer.seekTo(target)
                            currentPositionMs = target
                            interactionTick++
                        },
                        modifier = Modifier
                            .size(52.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Forward10,
                            contentDescription = "10秒送る",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            if (currentIndex + 1 in playlist.indices) {
                                playItemAtIndex(currentIndex + 1)
                            }
                            interactionTick++
                        },
                        enabled = currentIndex + 1 in playlist.indices,
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "次の動画",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = formatTime(currentPositionMs),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium
                    )

                    Slider(
                        value = if (durationMs > 0L) {
                            (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        },
                        onValueChange = { ratio ->
                            val targetMs = (ratio * durationMs).toLong()
                            exoPlayer.seekTo(targetMs)
                            currentPositionMs = targetMs
                            interactionTick++
                        },
                        modifier = Modifier.weight(1f)
                    )

                    Text(
                        text = formatTime(durationMs),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        if (showPlaylistDialog) {
            PlaylistDialog(
                playlist = playlist,
                currentIndex = currentIndex,
                autoPlayNext = autoPlayNext,
                onToggleAutoPlayNext = { autoPlayNext = !autoPlayNext },
                onSelectIndex = { idx ->
                    playItemAtIndex(idx)
                    showPlaylistDialog = false
                },
                onRemoveIndex = { idx ->
                    if (idx in playlist.indices) {
                        playlist.removeAt(idx)
                        if (idx == currentIndex) {
                            currentIndex = -1
                        } else if (idx < currentIndex) {
                            currentIndex--
                        }
                    }
                },
                onClearAll = {
                    playlist.clear()
                    currentIndex = -1
                },
                onDismiss = { showPlaylistDialog = false }
            )
        }

        if (showNetworkDialog) {
            NetworkExplorerDialog(
                devices = discoveredDevices,
                isScanning = isScanningNetwork,
                activeDevice = activeDevice,
                entries = explorerEntries,
                isBrowsing = isBrowsingExplorer,
                errorMessage = explorerError,
                currentDlnaLabel = currentDlnaLabel,
                currentSmbSubPath = currentSmbSubPath,
                smbCredentials = smbCredentials,
                onUpdateSmbCredentials = { smbCredentials = it },
                onRescan = runNetworkScan,
                onOpenDevice = { dev ->
                    dlnaBackStack.clear()
                    if (dev.protocol == NetworkProtocolType.DLNA) {
                        loadExplorerDirectory(dev, "0", "ルート", smbCredentials)
                    } else {
                        loadExplorerDirectory(dev, "", "", smbCredentials)
                    }
                },
                onNavigateUp = {
                    val dev = activeDevice ?: return@NetworkExplorerDialog
                    if (dev.protocol == NetworkProtocolType.DLNA) {
                        if (dlnaBackStack.isNotEmpty()) {
                            val prev = dlnaBackStack.removeAt(dlnaBackStack.lastIndex)
                            loadExplorerDirectory(dev, prev.first, prev.second, smbCredentials)
                        } else {
                            activeDevice = null
                        }
                    } else {
                        if (currentSmbSubPath.isNotBlank()) {
                            val parent = currentSmbSubPath.substringBeforeLast('/', "")
                            loadExplorerDirectory(dev, parent, parent.ifBlank { "/" }, smbCredentials)
                        } else {
                            activeDevice = null
                        }
                    }
                },
                onSelectEntry = { entry ->
                    val dev = activeDevice ?: return@NetworkExplorerDialog
                    if (entry.isDirectory) {
                        if (dev.protocol == NetworkProtocolType.DLNA) {
                            dlnaBackStack.add(currentDlnaId to currentDlnaLabel)
                            loadExplorerDirectory(dev, entry.objectIdOrPath, entry.name, smbCredentials)
                        } else {
                            loadExplorerDirectory(dev, entry.objectIdOrPath, entry.name, smbCredentials)
                        }
                    } else {
                        entry.streamUrl?.let { urlStr ->
                            addAndPlayUri(
                                NetworkDiscoveryManager.buildDirectUri(urlStr),
                                entry.name,
                                dev.protocol.label
                            )
                            showNetworkDialog = false
                        }
                    }
                },
                onDismiss = {
                    activeDevice = null
                    showNetworkDialog = false
                }
            )
        }

        if (showFilterSheet) {
            FilterPresetDialog(
                selectedPresetLabel = selectedPresetLabel,
                saturation = saturation,
                contrast = contrast,
                sharpness = sharpness,
                clarity = clarity,
                onSelectPreset = { preset ->
                    applyPreset(preset)
                },
                onCommitValues = { newSat, newCon, newShp, newClarity ->
                    saturation = newSat
                    contrast = newCon
                    sharpness = newShp
                    clarity = newClarity
                    selectedPresetLabel = "カスタム"
                    interactionTick++
                },
                onDismiss = {
                    showFilterSheet = false
                    interactionTick++
                }
            )
        }
    }
}

@Composable
private fun PlaylistDialog(
    playlist: List<PlaylistItem>,
    currentIndex: Int,
    autoPlayNext: Boolean,
    onToggleAutoPlayNext: () -> Unit,
    onSelectIndex: (Int) -> Unit,
    onRemoveIndex: (Int) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("プレイリスト (${playlist.size})")
                FilterChip(
                    selected = autoPlayNext,
                    onClick = onToggleAutoPlayNext,
                    label = { Text(if (autoPlayNext) "連続再生ON" else "連続再生OFF", fontSize = 11.sp) }
                )
            }
        },
        text = {
            if (playlist.isEmpty()) {
                Text("動画が登録されていません。「開く」で複数選択するか、SMB/DLNAエクスプローラから選んで追加してください。")
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(playlist, key = { _, item -> item.id }) { index, item ->
                        val isCurrent = index == currentIndex
                        Surface(
                            color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectIndex(index) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${index + 1}. ${item.title}",
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "[${item.sourceType}] ${item.uriString}",
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = { onRemoveIndex(index) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "削除")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (playlist.isNotEmpty()) {
                    TextButton(onClick = onClearAll) {
                        Text("全削除")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("閉じる")
                }
            }
        }
    )
}

@Composable
private fun NetworkExplorerDialog(
    devices: List<DiscoveredNetworkDevice>,
    isScanning: Boolean,
    activeDevice: DiscoveredNetworkDevice?,
    entries: List<ExplorerEntry>,
    isBrowsing: Boolean,
    errorMessage: String?,
    currentDlnaLabel: String,
    currentSmbSubPath: String,
    smbCredentials: SmbCredentials,
    onUpdateSmbCredentials: (SmbCredentials) -> Unit,
    onRescan: () -> Unit,
    onOpenDevice: (DiscoveredNetworkDevice) -> Unit,
    onNavigateUp: () -> Unit,
    onSelectEntry: (ExplorerEntry) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (activeDevice == null) {
                    "SMB / DLNA 機器検索"
                } else {
                    "${activeDevice.name} エクスプローラ"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            if (activeDevice == null) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isScanning) "LAN内のSMB/DLNAを検索中..." else "検出機器: ${devices.size}件（タップで開く）",
                            style = MaterialTheme.typography.bodySmall
                        )
                        FilledTonalButton(
                            onClick = onRescan,
                            enabled = !isScanning,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("再検索", fontSize = 12.sp)
                        }
                    }

                    if (isScanning) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    LazyColumn(
                        modifier = Modifier.heightIn(max = 260.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(devices) { _, dev ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenDevice(dev) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = if (dev.protocol == NetworkProtocolType.DLNA) {
                                            Icons.Default.CastConnected
                                        } else {
                                            Icons.Default.Dns
                                        },
                                        contentDescription = null
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "[${dev.protocol.label}] ${dev.name}",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = dev.locationOrShare,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                                }
                            }
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilledTonalButton(
                            onClick = onNavigateUp,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("上へ", fontSize = 12.sp)
                        }

                        Text(
                            text = if (activeDevice.protocol == NetworkProtocolType.DLNA) {
                                "場所: $currentDlnaLabel"
                            } else {
                                "共有(${smbCredentials.shareName}): /${currentSmbSubPath.trim('/')}"
                            },
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (activeDevice.protocol == NetworkProtocolType.SMB) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = smbCredentials.shareName,
                                    onValueChange = { onUpdateSmbCredentials(smbCredentials.copy(shareName = it)) },
                                    label = { Text("共有名", fontSize = 11.sp) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = smbCredentials.username,
                                    onValueChange = { onUpdateSmbCredentials(smbCredentials.copy(username = it)) },
                                    label = { Text("ユーザー", fontSize = 11.sp) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedTextField(
                                    value = smbCredentials.password,
                                    onValueChange = { onUpdateSmbCredentials(smbCredentials.copy(password = it)) },
                                    label = { Text("パスワード", fontSize = 11.sp) },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                FilledTonalButton(
                                    onClick = { onOpenDevice(activeDevice) }
                                ) {
                                    Text("接続", fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    if (isBrowsing) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    if (errorMessage != null) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp
                        )
                    }

                    LazyColumn(
                        modifier = Modifier.heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(entries) { _, entry ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectEntry(entry) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = if (entry.isDirectory) {
                                            Icons.Default.Folder
                                        } else {
                                            Icons.Default.Movie
                                        },
                                        contentDescription = null,
                                        tint = if (entry.isDirectory) Color(0xFFFBBF24) else Color(0xFF38BDF8)
                                    )
                                    Text(
                                        text = entry.name,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = if (entry.isDirectory) "開く" else "再生",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("閉じる")
            }
        }
    )
}

@Composable
private fun FilterPresetDialog(
    selectedPresetLabel: String,
    saturation: Float,
    contrast: Float,
    sharpness: Float,
    clarity: Float,
    onSelectPreset: (FilterPreset) -> Unit,
    onCommitValues: (saturation: Float, contrast: Float, sharpness: Float, clarity: Float) -> Unit,
    onDismiss: () -> Unit
) {
    var draftSaturation by remember(saturation) { mutableFloatStateOf(saturation) }
    var draftContrast by remember(contrast) { mutableFloatStateOf(contrast) }
    var draftSharpness by remember(sharpness) { mutableFloatStateOf(sharpness) }
    var draftClarity by remember(clarity) { mutableFloatStateOf(clarity) }

    AlertDialog(
        onDismissRequest = {
            onCommitValues(draftSaturation, draftContrast, draftSharpness, draftClarity)
            onDismiss()
        },
        title = { Text("画質プリセット・フィルター調整") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("プリセット", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = selectedPresetLabel == preset.label,
                            onClick = {
                                draftSaturation = preset.saturation
                                draftContrast = preset.contrast
                                draftSharpness = preset.sharpness
                                draftClarity = preset.clarity
                                onSelectPreset(preset)
                            },
                            label = { Text(preset.label, fontSize = 12.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                HorizontalDivider()

                Text(
                    text = String.format(Locale.US, "彩度: %.2f", draftSaturation),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )
                Slider(
                    value = draftSaturation,
                    onValueChange = { draftSaturation = it },
                    onValueChangeFinished = {
                        onCommitValues(draftSaturation, draftContrast, draftSharpness, draftClarity)
                    },
                    valueRange = 0.0f..2.0f
                )

                Text(
                    text = String.format(Locale.US, "コントラスト: %.2f", draftContrast),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )
                Slider(
                    value = draftContrast,
                    onValueChange = { draftContrast = it },
                    onValueChangeFinished = {
                        onCommitValues(draftSaturation, draftContrast, draftSharpness, draftClarity)
                    },
                    valueRange = 0.5f..1.8f
                )

                Text(
                    text = String.format(Locale.US, "シャープネス: %.2f", draftSharpness),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )
                Slider(
                    value = draftSharpness,
                    onValueChange = { draftSharpness = it },
                    onValueChangeFinished = {
                        onCommitValues(draftSaturation, draftContrast, draftSharpness, draftClarity)
                    },
                    valueRange = 0.0f..1.0f
                )

                Text(
                    text = String.format(Locale.US, "明瞭度: %.2f", draftClarity),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )
                Slider(
                    value = draftClarity,
                    onValueChange = { draftClarity = it },
                    onValueChangeFinished = {
                        onCommitValues(draftSaturation, draftContrast, draftSharpness, draftClarity)
                    },
                    valueRange = 0.0f..1.0f
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCommitValues(draftSaturation, draftContrast, draftSharpness, draftClarity)
                    onDismiss()
                }
            ) {
                Text("閉じる")
            }
        }
    )
}

private fun resolveTitle(context: Context, uri: Uri): String {
    if (uri.scheme == "content") {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            val name = cursor.getString(idx)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
        } catch (_: Exception) {
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "選択した動画"
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSec / 3600L
    val minutes = (totalSec % 3600L) / 60L
    val seconds = totalSec % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
