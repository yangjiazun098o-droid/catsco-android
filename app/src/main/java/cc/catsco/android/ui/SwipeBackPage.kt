package cc.catsco.android.ui

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.Window
import android.view.ViewTreeObserver
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

private const val PreviewQuietPeriodMillis = 80L
private const val PreviewRefreshIntervalMillis = 150L

private class PageHistory(var page: String, var back: String, var inSettings: Boolean) {
    var gestureDestination: String? = null
}

private class PagePreviewTiming {
    var drawRevision = 0L
    var lastDrawAt = 0L
    var lastCopyAt = 0L
}

/** Keeps visual back previews in memory without mounting another page or repeating its requests. */
@Composable
internal fun SwipeBackPage(
    page: String,
    previousPage: String,
    enabled: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    captureEnabled: Boolean = true,
    inSettings: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val view = LocalView.current
    val window = remember(view) {
        generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()?.window
    }
    val canCapture by rememberUpdatedState(captureEnabled)
    val currentPage by rememberUpdatedState(page)
    var sourceRect by remember { mutableStateOf<Rect?>(null) }
    val scope = rememberCoroutineScope()
    val background = MaterialTheme.colorScheme.background
    var snapshots by remember(background) { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val currentViewport by rememberUpdatedState(viewport)
    var hasDrawn by remember(page) { mutableStateOf(false) }
    val back by rememberUpdatedState(onBack)
    var offset by remember(page) { mutableFloatStateOf(0f) }
    var dragging by remember(page) { mutableStateOf(false) }
    var dragPreview by remember(page) { mutableStateOf<ImageBitmap?>(null) }
    var settling by remember(page) { mutableStateOf<Job?>(null) }
    var captureJob by remember(page) { mutableStateOf<Job?>(null) }
    val previewTiming = remember(page) { PagePreviewTiming() }
    var landingPreview by remember { mutableStateOf<Pair<String, ImageBitmap>?>(null) }
    val history = remember { PageHistory(page, previousPage, inSettings) }
    val navigationDirection = remember(page) {
        when {
            history.page == page || history.gestureDestination == page || (!inSettings && !history.inSettings) -> 0
            page == history.back || !inSettings -> -1
            else -> 1
        }
    }
    val navigationProgress = remember(page) { Animatable(if (navigationDirection == 0) 1f else 0f) }
    val navigating by remember(navigationProgress) { derivedStateOf { navigationProgress.value < 1f } }
    SideEffect {
        if (history.page != page) history.gestureDestination = null
        history.page = page
        history.back = previousPage
        history.inSettings = inSettings
    }
    LaunchedEffect(navigationProgress) {
        if (navigationDirection != 0) {
            navigationProgress.animateTo(1f, tween(165, easing = CubicBezierEasing(.23f, 1f, .32f, 1f)))
        }
    }

    LaunchedEffect(enabled) {
        if (!enabled) {
            settling?.cancel()
            offset = 0f
            dragging = false
            dragPreview = null
        }
    }

    fun capturePage() {
        if (window == null || sourceRect == null || !canCapture || dragging || offset != 0f || navigating || landingPreview != null) return
        previewTiming.drawRevision++
        previewTiming.lastDrawAt = SystemClock.uptimeMillis()
        if (captureJob?.isActive == true) return
        captureJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            while (canCapture && currentPage == page && !dragging && offset == 0f && !navigating && landingPreview == null) {
                val now = SystemClock.uptimeMillis()
                // Keep the home composer current for a quick return from settings.
                // For scrollable pages, wait for drawing to settle before the next copy.
                val captureImmediately = page == "home" || previewTiming.lastCopyAt == 0L
                val remaining = if (captureImmediately) 0L else maxOf(
                    PreviewQuietPeriodMillis - (now - previewTiming.lastDrawAt),
                    PreviewRefreshIntervalMillis - (now - previewTiming.lastCopyAt),
                )
                if (remaining > 0) {
                    delay(remaining)
                    continue
                }
                val revision = previewTiming.drawRevision
                // PixelCopy reads the presented window. Let the pending draw be submitted first.
                withFrameNanos { }
                if (!canCapture || currentPage != page || dragging || offset != 0f || navigating || landingPreview != null) return@launch
                if (!captureImmediately && previewTiming.drawRevision != revision) continue
                val region = sourceRect ?: return@launch
                previewTiming.lastCopyAt = SystemClock.uptimeMillis()
                val preview = copyPageWindow(window, region) ?: return@launch
                if (canCapture && currentPage == page && !dragging && offset == 0f && !navigating && landingPreview == null && region == sourceRect &&
                    region.width() == currentViewport.width && region.height() == currentViewport.height) {
                    val updated = LinkedHashMap(snapshots)
                    updated.remove(page)
                    updated[page] = preview.asImageBitmap()
                    // Full-resolution previews must match the live page's text rasterization.
                    // Bound their memory rather than reducing their resolution.
                    while (updated.size > 1 && (updated.size > 8 ||
                        updated.values.sumOf { it.asAndroidBitmap().allocationByteCount.toLong() } > 32L * 1024 * 1024)) {
                        updated.remove(updated.keys.first())
                    }
                    snapshots = updated
                } else preview.recycle()
                if (previewTiming.drawRevision == revision) return@launch
            }
        }
    }

    val captureLatest by rememberUpdatedState { capturePage() }
    DisposableEffect(view, page) {
        // Child graphics layers and native Markdown views can redraw without rerunning
        // the parent's Compose draw modifier. Observe the actual window drawing instead.
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnDrawListener { captureLatest() }
        observer.addOnDrawListener(listener)
        onDispose { if (observer.isAlive) observer.removeOnDrawListener(listener) }
    }

    LaunchedEffect(page, hasDrawn, sourceRect, captureEnabled, navigating) {
        if (hasDrawn && captureEnabled && !navigating) capturePage()
    }
    DisposableEffect(page) {
        onDispose {
            settling?.cancel()
            captureJob?.cancel()
        }
    }

    LaunchedEffect(page, hasDrawn, landingPreview?.first) {
        if (hasDrawn && landingPreview?.first == page) {
            // Scaffold and the restored composer's bottom inset settle on the next frames.
            // Keep the identical preview above them until the live layout has been drawn.
            repeat(2) { withFrameNanos { } }
            landingPreview = null
        }
    }

    fun beginDrag() {
        settling?.cancel()
        dragPreview = snapshots[previousPage]
        dragging = true
    }

    fun settle(complete: Boolean, velocity: Float = 0f) {
        settling?.cancel()
        settling = scope.launch {
            val preview = dragPreview
            // Without a captured destination, moving the foreground only exposes a blank frame.
            if (preview != null) {
                val target = if (complete) viewport.width.toFloat() else 0f
                val remaining = abs(target - offset) / viewport.width.coerceAtLeast(1)
                val duration = (100 + remaining * 130).toInt()
                animate(offset, target, initialVelocity = velocity,
                    animationSpec = tween(duration, easing = FastOutSlowInEasing)) { value, _ -> offset = value }
            } else offset = 0f
            if (complete) {
                history.gestureDestination = previousPage
                landingPreview = preview?.let { previousPage to it }
                back()
            } else {
                dragging = false
                dragPreview = null
            }
        }
    }

    PredictiveBackHandler(enabled = enabled) { progress ->
        val initial = offset
        beginDrag()
        try {
            progress.collect { event -> offset = initial + (viewport.width - initial) * event.progress }
            settle(true)
        } catch (_: CancellationException) {
            settle(false)
        }
    }

    val gesture = Modifier.pointerInput(page, enabled, viewport) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!enabled) return@awaitEachGesture
            val velocity = VelocityTracker()
            velocity.addPosition(down.uptimeMillis, down.position)
            var initialDrag = 0f
            val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                if (overSlop > 0f) {
                    change.consume()
                    initialDrag = overSlop
                }
            } ?: return@awaitEachGesture
            beginDrag()
            offset = (offset + initialDrag).coerceIn(0f, viewport.width.toFloat())
            velocity.addPosition(start.uptimeMillis, start.position)
            val released = horizontalDrag(start.id) { change ->
                velocity.addPosition(change.uptimeMillis, change.position)
                offset = (offset + change.position.x - change.previousPosition.x)
                    .coerceIn(0f, viewport.width.toFloat())
                change.consume()
            }
            val speed = velocity.calculateVelocity().x
            val complete = released && (offset > viewport.width * .32f ||
                (offset > 24.dp.toPx() && speed > 700.dp.toPx())) && speed > -500.dp.toPx()
            settle(complete, speed)
        }
    }

    Box(Modifier.fillMaxSize().clipToBounds().background(background).onSizeChanged {
        if (viewport != it) {
            if (viewport.width != it.width) snapshots = emptyMap()
            viewport = it
            settling?.cancel()
            offset = 0f
            dragging = false
            dragPreview = null
        }
    }.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        sourceRect = Rect(bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.right.roundToInt(), bounds.bottom.roundToInt())
    }.then(gesture)) {
        dragPreview?.takeIf { dragging }?.let { preview ->
            Image(preview, null, Modifier.fillMaxSize().graphicsLayer {
                translationX = -.16f * (viewport.width - offset)
            }, contentScale = ContentScale.FillWidth, alignment = androidx.compose.ui.Alignment.TopStart)
            Box(Modifier.fillMaxSize().graphicsLayer {
                alpha = .14f * (1f - offset / viewport.width.coerceAtLeast(1))
            }.background(Color.Black))
        }
        Box(modifier.fillMaxSize().testTag("swipe-back-page").then(if (enabled || dragging || navigating) Modifier.graphicsLayer {
            val progress = if (dragging) 1f else navigationProgress.value
            val visibleOffset = if (dragPreview != null) offset else 0f
            translationX = visibleOffset + 18.dp.toPx() * navigationDirection * (1f - progress)
            alpha = .92f + .08f * progress
            shadowElevation = if (visibleOffset > 0f) 12.dp.toPx() else 0f
            shape = RoundedCornerShape(if (visibleOffset > 0f) 16.dp else 0.dp)
            clip = true
        } else Modifier).drawWithContent {
            drawContent()
            if (!hasDrawn) hasDrawn = true
        }, content = content)
        landingPreview?.takeIf { it.first == page }?.let { (_, preview) ->
            Image(preview, null, Modifier.fillMaxSize().background(background),
                contentScale = ContentScale.FillWidth, alignment = androidx.compose.ui.Alignment.TopStart)
        }
    }
}

private suspend fun copyPageWindow(window: Window, region: Rect): Bitmap? {
    if (region.isEmpty) return null
    val bitmap = Bitmap.createBitmap(region.width(), region.height(), Bitmap.Config.ARGB_8888)
    return suspendCancellableCoroutine { continuation ->
        try {
            PixelCopy.request(window, region, bitmap, { result ->
                if (result == PixelCopy.SUCCESS && continuation.isActive) continuation.resume(bitmap)
                else {
                    bitmap.recycle()
                    if (continuation.isActive) continuation.resume(null)
                }
            }, Handler(Looper.getMainLooper()))
        } catch (_: IllegalArgumentException) {
            bitmap.recycle()
            if (continuation.isActive) continuation.resume(null)
        }
    }
}
