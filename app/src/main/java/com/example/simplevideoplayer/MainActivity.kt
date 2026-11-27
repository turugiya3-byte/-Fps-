package com.example.simplevideoplayer

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

private enum class GestureHudType {
    BRIGHTNESS, VOLUME, SEEK
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

@Composable
fun MxStyleVideoPlayerScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build()
    }

    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var videoTitle by remember { mutableStateOf("動画を選択してください") }

    var controlsVisible by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var interactionTick by remember { mutableIntStateOf(0) }

    // 輝度・音量・シークのポップアップHUD状態
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

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            videoTitle = resolveTitle(context, it)
            exoPlayer.setMediaItem(MediaItem.fromUri(it))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            controlsVisible = true
            interactionTick++
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) interactionTick++
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    durationMs = exoPlayer.duration.coerceAtLeast(0L)
                }
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // 再生位置の定期更新
    LaunchedEffect(exoPlayer) {
        while (true) {
            currentPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            durationMs = exoPlayer.duration.coerceAtLeast(0L)
            delay(250L)
        }
    }

    // 再生中は4秒操作がないと自動でコントロールを隠す
    LaunchedEffect(controlsVisible, isPlaying, isLocked, interactionTick) {
        if (controlsVisible && isPlaying && !isLocked) {
            delay(4000L)
            controlsVisible = false
        }
    }

    // ジェスチャーHUDを1.2秒後に自動で消す
    LaunchedEffect(activeHud, brightnessLevel, volumeLevel, seekPreviewMs) {
        if (activeHud != null) {
            delay(1200L)
            activeHud = null
        }
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
        // 1. 映像表示レイヤー
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. MX Player風スワイプ操作レイヤー（ロック中は無効）
        if (!isLocked) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val halfWidthPx = constraints.maxWidth * 0.5f
                val dragHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f) * 0.65f
                val dragWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f) * 0.75f
                var dragStartX by remember { mutableFloatStateOf(0f) }
                var dragStartPosMs by remember { mutableLongStateOf(0L) }

                // 上下スワイプ：左半分＝輝度、右半分＝音量
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(isLocked, maxVolume) {
                            detectVerticalDragGestures(
                                onDragStart = { offset ->
                                    dragStartX = offset.x
                                    activeHud = if (offset.x < halfWidthPx) {
                                        GestureHudType.BRIGHTNESS
                                    } else {
                                        GestureHudType.VOLUME
                                    }
                                },
                                onDragEnd = { },
                                onDragCancel = { },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    // 上スワイプ（dragAmount < 0）で増加
                                    val delta = -dragAmount / dragHeightPx
                                    if (dragStartX < halfWidthPx) {
                                        val next = (brightnessLevel + delta).coerceIn(0.05f, 1.0f)
                                        brightnessLevel = next
                                        activity?.window?.let { win ->
                                            val params = win.attributes
                                            params.screenBrightness = next
                                            win.attributes = params
                                        }
                                        activeHud = GestureHudType.BRIGHTNESS
                                    } else {
                                        val next = (volumeLevel + delta).coerceIn(0.0f, 1.0f)
                                        volumeLevel = next
                                        val targetVol = (next * maxVolume).roundToInt().coerceIn(0, maxVolume)
                                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                                        activeHud = GestureHudType.VOLUME
                                    }
                                }
                            )
                        }
                )

                // 左右スワイプ：シーク移動
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(isLocked, durationMs) {
                            var accumulatedDx = 0f
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    accumulatedDx = 0f
                                    dragStartPosMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                                    seekPreviewMs = dragStartPosMs
                                    seekDeltaSec = 0
                                    if (durationMs > 0L) activeHud = GestureHudType.SEEK
                                },
                                onDragEnd = {
                                    if
