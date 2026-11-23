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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

private const val PreviewQuietPeriodMillis = 80L
private const val PreviewRefreshIntervalMillis = 150L
private enum class BackGesture { Touch, System }

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
    contentReady: Boolean = true,
    inSettings: Boolean = false,
    homeTrayExpanded: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val view = LocalView.current
    val window = remember(view) {
        generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()?.window
    }
    val canCapture by rememberUpdatedState(captureEnabled)
    val ready by rememberUpdatedState(contentReady)
    val currentPage by rememberUpdatedState(page)
    val destination by rememberUpdatedState(previousPage)
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
    var gestureOwner by remember(page) { mutableStateOf<BackGesture?>(null) }
    var dragPreview by remember(page) { mutableStateOf<ImageBitmap?>(null) }
    var settling by remember(page) { mutableStateOf<Job?>(null) }
    var captureJob by remember(page) { mutableStateOf<Job?>(null) }
    val previewTiming = remember(page) { PagePreviewTiming() }
    var landingPreview by remember { mutableStateOf<Pair<String, ImageBitmap>?>(null) }
    val landingOpacity = remember(landingPreview) { Animatable(1f) }
    val history = remember { PageHistory(page, previousPage, inSettings) }
    val navigationDirection = remember(page) {
        when {
            history.page == page -> 0
            history.gestureDestination == page -> if (landingPreview?.first == page) 0 else -1
            !inSettings && !history.inSettings -> 0
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
            gestureOwner = null
            dragPreview = null
        }
    }

    fun capturePage(settledTray: Boolean = false) {
        if (window == null || sourceRect == null || !canCapture || !ready || dragging || offset != 0f || navigating || landingPreview != null) return
        previewTiming.drawRevision++
        previewTiming.lastDrawAt = SystemClock.uptimeMillis()
        if (captureJob?.isActive == true) return
        captureJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            while (canCapture && ready && currentPage == page && !dragging && offset == 0f && !navigating && landingPreview == null) {
                val now = SystemClock.uptimeMillis()
                // The first frame provides a return preview. Later frames wait for idle,
                // including on home while the caret, welcome animation or IME are moving.
                val captureImmediately = previewTiming.lastCopyAt == 0L
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
                if (captureImmediately || settledTray) withTimeoutOrNull(80) { view.awaitCommittedFrame() }
                else withFrameNanos { }
                if (!canCapture || !ready || currentPage != page || dragging || offset != 0f || navigating || landingPreview != null) return@launch
                if (!captureImmediately && previewTiming.drawRevision != revision) continue
                val region = sourceRect ?: return@launch
                previewTiming.lastCopyAt = SystemClock.uptimeMillis()
                val preview = copyPageWindow(window, region) ?: return@launch
                if (canCapture && ready && currentPage == page && !dragging && offset == 0f && !navigating && landingPreview == null && region == sourceRect &&
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

    LaunchedEffect(page, homeTrayExpanded, captureEnabled, landingPreview != null) {
        if (page == "home" && captureEnabled && landingPreview == null) {
            // A settled tray supersedes an in-flight or throttled copy of its old layout.
            captureJob?.cancel()
            previewTiming.lastCopyAt = 0L
            capturePage(settledTray = true)
        }
    }

    LaunchedEffect(page, hasDrawn, sourceRect, captureEnabled, contentReady, navigating) {
        if (hasDrawn && captureEnabled && !navigating) capturePage()
    }
    DisposableEffect(page) {
        onDispose {
            settling?.cancel()
            captureJob?.cancel()
        }
    }

    LaunchedEffect(page, hasDrawn, landingPreview) {
        val landing = landingPreview ?: return@LaunchedEffect
        if (landing.first != page) {
            landingPreview = null
            return@LaunchedEffect
        }
        if (!hasDrawn) return@LaunchedEffect
        withTimeoutOrNull(220) { snapshotFlow { ready }.first { it } }
        withTimeoutOrNull(80) { view.awaitCommittedFrame() }
        landingOpacity.animateTo(0f, tween(110))
        landingPreview = null
    }

    fun beginDrag(owner: BackGesture): Boolean {
        if (owner == BackGesture.Touch && gestureOwner == BackGesture.System) return false
        settling?.cancel()
        if (!dragging) {
            landingPreview = null
            dragPreview = snapshots[destination]
        }
        gestureOwner = owner
        dragging = true
        return true
    }

    fun settle(owner: BackGesture, complete: Boolean, velocity: Float = 0f) {
        if (gestureOwner != owner) return
        settling?.cancel()
        settling = scope.launch {
            val preview = dragPreview
            val target = if (complete) viewport.width.toFloat() else 0f
            val remaining = abs(target - offset) / viewport.width.coerceAtLeast(1)
            if (remaining > .001f) {
                val duration = if (preview == null) (90 + remaining * 65).toInt() else (100 + remaining * 130).toInt()
                animate(offset, target, initialVelocity = velocity,
                    animationSpec = tween(duration, easing = FastOutSlowInEasing)) { value, _ -> offset = value }
            }
            if (gestureOwner != owner || currentPage != page) return@launch
            if (complete) {
                history.gestureDestination = destination
                landingPreview = preview?.let { destination to it }
                gestureOwner = null
                back()
            } else {
                offset = 0f
                dragging = false
                dragPreview = null
                gestureOwner = null
            }
        }
    }

    PredictiveBackHandler(enabled = enabled) { progress ->
        val initial = offset
        beginDrag(BackGesture.System)
        try {
            progress.collect { event -> offset = initial + (viewport.width - initial) * event.progress }
            settle(BackGesture.System, true)
        } catch (_: CancellationException) {
            settle(BackGesture.System, false)
        }
    }

    val gesture = Modifier.pointerInput(page, enabled) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!enabled) return@awaitEachGesture
            val velocity = VelocityTracker()
            velocity.addPosition(down.uptimeMillis, down.position)
            var initialDrag = 0f
            val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                if (overSlop > 0f && gestureOwner != BackGesture.System) {
                    change.consume()
                    initialDrag = overSlop
                }
            } ?: return@awaitEachGesture
            if (!beginDrag(BackGesture.Touch)) return@awaitEachGesture
            offset = (offset + initialDrag).coerceIn(0f, viewport.width.toFloat())
            velocity.addPosition(start.uptimeMillis, start.position)
            val released = horizontalDrag(start.id) { change ->
                if (gestureOwner != BackGesture.Touch) return@horizontalDrag
                velocity.addPosition(change.uptimeMillis, change.position)
                offset = (offset + change.position.x - change.previousPosition.x)
                    .coerceIn(0f, viewport.width.toFloat())
                change.consume()
            }
            val speed = velocity.calculateVelocity().x
            val complete = released && (offset > viewport.width * .32f ||
                (offset > 24.dp.toPx() && speed > 700.dp.toPx())) && speed > -500.dp.toPx()
            settle(BackGesture.Touch, complete, speed)
        }
    }

    Box(Modifier.fillMaxSize().clipToBounds().background(background).onSizeChanged {
        if (viewport != it) {
            if (viewport.width != it.width) {
                snapshots = emptyMap()
                settling?.cancel()
                offset = 0f
                dragging = false
                dragPreview = null
                gestureOwner = null
            }
            viewport = it
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
            // Keep the current page mostly in view while its destination has no preview.
            val visibleOffset = if (dragPreview != null) offset else offset * .07f
            translationX = visibleOffset + 18.dp.toPx() * navigationDirection * (1f - progress)
            shadowElevation = if (visibleOffset > 0f) 12.dp.toPx() else 0f
            shape = RoundedCornerShape(if (visibleOffset > 0f) 16.dp else 0.dp)
            clip = true
        } else Modifier).drawWithContent {
            drawContent()
            if (!hasDrawn) hasDrawn = true
        }, content = content)
        landingPreview?.takeIf { it.first == page }?.let { (_, preview) ->
            Image(preview, null, Modifier.fillMaxSize().graphicsLayer { alpha = landingOpacity.value }.background(background),
                contentScale = ContentScale.FillWidth, alignment = androidx.compose.ui.Alignment.TopStart)
        }
    }
}

/** PixelCopy must read the submitted window frame after the composer finishes changing height. */
private suspend fun android.view.View.awaitCommittedFrame() = suspendCancellableCoroutine<Unit> { continuation ->
    val observer = viewTreeObserver
    val committed = Runnable { if (continuation.isActive) continuation.resume(Unit) }
    if (android.os.Build.VERSION.SDK_INT >= 29 && isHardwareAccelerated && observer.isAlive) {
        observer.registerFrameCommitCallback(committed)
        continuation.invokeOnCancellation { if (observer.isAlive) observer.unregisterFrameCommitCallback(committed) }
        postInvalidateOnAnimation()
    } else {
        postOnAnimation { post(committed) }
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
