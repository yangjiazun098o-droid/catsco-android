package cc.catsco.android.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import cc.catsco.android.data.VoiceCapture
import cc.catsco.android.data.VoiceCharacterLimit
import cc.catsco.android.data.VoiceDestination
import cc.catsco.android.data.voiceCharacterCount
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

internal class VoiceHoldCallbacks(
    val start: () -> Boolean,
    val move: (Offset) -> Unit,
    val release: (Offset) -> Unit,
    val cancel: () -> Unit,
)

@Composable
internal fun Modifier.holdToTalk(callbacks: VoiceHoldCallbacks, onTap: () -> Unit, enabled: Boolean = true): Modifier {
    val current by rememberUpdatedState(callbacks)
    val tap by rememberUpdatedState(onTap)
    val canStart by rememberUpdatedState(enabled)
    var origin by remember { mutableStateOf(Offset.Zero) }
    return onGloballyPositioned { origin = it.localToRoot(Offset.Zero) }.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Initial)
            if (!canStart) return@awaitEachGesture
            down.consume()
            val shortPress = withTimeoutOrNull(180L) {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                        ?: return@withTimeoutOrNull false
                    if (change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        return@withTimeoutOrNull false
                    }
                    change.consume()
                    if (!change.pressed) return@withTimeoutOrNull true
                }
                @Suppress("UNREACHABLE_CODE")
                false
            }
            if (shortPress != null) {
                if (shortPress) tap()
                return@awaitEachGesture
            }
            if (!current.start()) return@awaitEachGesture

            var released = false
            try {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed) break
                    change.consume()
                    val position = origin + change.position
                    if (!change.pressed) {
                        released = true
                        current.release(position)
                        break
                    }
                    current.move(position)
                }
            } finally {
                if (!released) current.cancel()
            }
        }
    }
}

internal class VoiceDropTargets {
    var edit: Rect? = null
    var send: Rect? = null
    var selected by mutableStateOf<VoiceDestination?>(null)
    var holding by mutableStateOf(false)

    fun hit(position: Offset): VoiceDestination? = when {
        send?.contains(position) == true -> VoiceDestination.Send
        edit?.contains(position) == true -> VoiceDestination.Edit
        else -> null
    }

    fun reset() {
        holding = false
        selected = null
        edit = null
        send = null
    }
}

@Composable
internal fun VoiceInputOverlay(
    phase: VoiceCapture,
    preview: String,
    level: Float,
    limit: Int,
    warning: String?,
    canSend: Boolean,
    targets: VoiceDropTargets,
    onComplete: (VoiceDestination) -> Unit,
    onCancel: () -> Unit,
    startedAt: Long? = null,
    stoppedAt: Long? = null,
    backdrop: HazeState? = null,
    interactive: Boolean = true,
) {
    BackHandler(enabled = interactive, onBack = onCancel)
    val finishing = phase == VoiceCapture.Finalizing
    val status = warning ?: when (phase) {
        VoiceCapture.Connecting -> "正在连接语音服务…"
        VoiceCapture.Recording -> if (preview.isBlank()) "正在听…" else ""
        VoiceCapture.Finalizing -> "正在完成转写…"
        VoiceCapture.Ready -> if (preview.voiceCharacterCount() >= limit) "已达到 ${VoiceCharacterLimit} 字上限" else "已结束录音"
    }
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 500.dp
        if (backdrop != null) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(1f / 3f)
                .hazeEffect(state = backdrop, style = HazeStyle(
                    backgroundColor = colors.background,
                    tint = HazeTint(colors.background.copy(alpha = .42f)),
                    blurRadius = 18.dp,
                    fallbackTint = HazeTint(colors.background.copy(alpha = .56f)),
                )) {
                    mask = Brush.verticalGradient(0f to Color.Transparent, .18f to Color.Black)
                })
        }
        // Existing drag events stay with the composer. New touches hit this layer.
        if (interactive) Box(Modifier.matchParentSize().pointerInput(Unit) {
            awaitEachGesture { awaitFirstDown().consume() }
        })
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding()
                .padding(horizontal = 24.dp).padding(bottom = if (compact) 48.dp else 90.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().height(if (compact) 40.dp else 64.dp), contentAlignment = Alignment.Center) {
                if (preview.isNotBlank()) Text(
                    preview.takeLast(480),
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState(), enabled = interactive, reverseScrolling = true),
                    style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                ) else Text(status, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            VoiceWave(level, phase == VoiceCapture.Recording || phase == VoiceCapture.Connecting,
                height = if (compact) 36.dp else 56.dp)
            VoiceRecordingTimer(startedAt, stoppedAt, status.takeIf { preview.isNotBlank() })
            Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
            // Keep the wave and timer anchored while the actions fade during finalization.
            Column(Modifier.fillMaxWidth().height(62.dp)) {
                AnimatedVisibility(visible = !finishing,
                    enter = fadeIn(tween(115)), exit = fadeOut(tween(85))) {
                    val tappable = interactive && !finishing && !targets.holding
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        VoiceTarget("编辑", targets.selected == VoiceDestination.Edit, true, tappable,
                            Modifier.weight(1f).onGloballyPositioned { if (interactive && !finishing) targets.edit = it.boundsInRoot() }) {
                            onComplete(VoiceDestination.Edit)
                        }
                        VoiceTarget("发送", canSend && targets.selected == VoiceDestination.Send, canSend, tappable,
                            Modifier.weight(1f).onGloballyPositioned {
                                if (interactive && !finishing) targets.send = if (canSend) it.boundsInRoot() else null
                            }) { onComplete(VoiceDestination.Send) }
                    }
                }
            }
        }
        if (interactive && !finishing && !targets.holding) Text(
            "取消", Modifier.align(Alignment.BottomCenter).safeDrawingPadding()
                .padding(bottom = if (compact) 4.dp else 40.dp)
                .clickable(role = Role.Button, onClick = onCancel).padding(12.dp),
            color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun VoiceRecordingTimer(startedAt: Long?, stoppedAt: Long?, status: String?) {
    fun elapsed(at: Long) = if (startedAt == null) 0L else (at - startedAt).coerceAtLeast(0L)
    var seconds by remember(startedAt, stoppedAt) {
        mutableLongStateOf(elapsed(stoppedAt ?: SystemClock.elapsedRealtime()) / 1_000)
    }
    if (startedAt != null && stoppedAt == null) {
        LaunchedEffect(startedAt) {
            while (true) {
                val millis = elapsed(SystemClock.elapsedRealtime())
                seconds = millis / 1_000
                delay(1_000 - millis % 1_000)
            }
        }
    }
    val minutes = (seconds / 60 % 60).toString().padStart(2, '0')
    val remainingSeconds = (seconds % 60).toString().padStart(2, '0')
    val clock = if (seconds >= 3_600) "${seconds / 3_600}:$minutes:$remainingSeconds" else "$minutes:$remainingSeconds"
    val label = if (status.isNullOrBlank()) clock else "$status · $clock"
    Text(label, style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}

@Composable
private fun VoiceTarget(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    tappable: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val elevation by animateDpAsState(
        if (selected) 14.dp else if (enabled) 10.dp else 5.dp,
        animationSpec = tween(140), label = "voiceActionElevation",
    )
    val background = when {
        selected -> colors.onSurface
        !enabled -> colors.surfaceContainer
        else -> colors.surfaceContainerHigh
    }
    val foreground = when {
        !enabled -> colors.onSurfaceVariant
        selected -> colors.surface
        else -> colors.onSurface
    }
    Box(
        modifier.heightIn(min = 62.dp)
            .shadow(elevation, shape, ambientColor = Color.Black.copy(alpha = .52f), spotColor = Color.Black.copy(alpha = .8f))
            .clip(shape).background(background)
            .then(if (tappable) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = foreground, style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun VoiceWave(level: Float, listening: Boolean, height: Dp) {
    val gain = if (listening) (sqrt(level.coerceIn(0f, 1f)) * 1.42f).coerceAtMost(1f) else 0f
    val energy = animateFloatAsState(gain, tween(115), label = "voiceLevel")
    val phase = if (listening) rememberInfiniteTransition(label = "voiceWave").animateFloat(
        initialValue = 0f, targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1250, easing = LinearEasing)), label = "wavePhase",
    ) else null
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.width(248.dp).height(height)) {
        val count = 13
        val stride = size.width / count
        val time = phase?.value ?: 0f
        for (index in 0 until count) {
            val distance = abs(index - (count - 1) / 2f) / ((count - 1) / 2f)
            val crest = .5f + .5f * sin(time - index * .56f)
            val ripple = .5f + .5f * sin(2f * time + index * .39f)
            val swing = if (listening) .12f + .62f * crest * crest + .26f * ripple else 0f
            val envelope = 1f - .72f * distance * distance
            val height = (5 + (19 + 56 * energy.value) * envelope * swing).dp.toPx().coerceAtMost(size.height)
            val width = (5f + (if (listening) 1.4f * swing else 0f)).dp.toPx()
            drawRoundRect(
                color = color.copy(alpha = .48f + .28f * envelope + .22f * swing),
                topLeft = Offset(stride * (index + .5f) - width / 2, (size.height - height) / 2),
                size = Size(width, height), cornerRadius = CornerRadius(width / 2),
            )
        }
    }
}
