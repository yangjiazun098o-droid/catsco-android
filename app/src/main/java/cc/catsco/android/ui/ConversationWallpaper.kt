package cc.catsco.android.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

private val wallpaperShape = RoundedCornerShape(20.dp)
private val centeredCrop = Offset(.5f, .5f)

internal data class WallpaperContrast(val headerDarkInk: Boolean, val centerDarkInk: Boolean, val bottomDarkInk: Boolean)

@Composable
internal fun rememberWallpaperContrast(store: ConversationWallpaperStore): WallpaperContrast? {
    val revision = store.revision ?: return null
    val config = LocalConfiguration.current
    val aspect = config.screenWidthDp.toFloat() / config.screenHeightDp.coerceAtLeast(1)
    return key(revision, aspect) {
        produceState<WallpaperContrast?>(null) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = 16 }
                val bitmap = android.graphics.BitmapFactory.decodeFile(store.file.path, options) ?: return@withContext null
                try {
                    val crop = wallpaperCrop(IntSize(bitmap.width, bitmap.height), aspect, centeredCrop, 1f)
                    fun useDarkInk(centerY: Float): Boolean {
                        var total = 0.0
                        for (y in -1..1) for (x in 1..9) {
                            val px = (crop.offset.x + crop.size.width * x / 10).coerceIn(0, bitmap.width - 1)
                            val py = (crop.offset.y + crop.size.height * (centerY + y * .025f)).roundToInt().coerceIn(0, bitmap.height - 1)
                            total += androidx.core.graphics.ColorUtils.calculateLuminance(bitmap.getPixel(px, py))
                        }
                        return total / 27 >= .18
                    }
                    WallpaperContrast(useDarkInk(.065f), useDarkInk(.46f), useDarkInk(.98f))
                } finally { bitmap.recycle() }
            }
        }.value
    }
}

@Composable
internal fun WallpaperSystemBars(contrast: WallpaperContrast?) {
    val view = androidx.compose.ui.platform.LocalView.current
    val context = LocalContext.current
    val darkTheme = MaterialTheme.colorScheme.background.let { androidx.core.graphics.ColorUtils.calculateLuminance(it.toArgb()) < .5 }
    DisposableEffect(view, contrast, darkTheme) {
        val window = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        if (contrast != null && controller != null) {
            controller.isAppearanceLightStatusBars = contrast.headerDarkInk
            controller.isAppearanceLightNavigationBars = contrast.bottomDarkInk
        }
        onDispose {
            if (contrast != null && controller != null) {
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
}

@Composable
internal fun ConversationWallpaperBackdrop(
    wallpaper: ConversationWallpaperStore,
    keyboardOpen: Boolean,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier.clipToBounds()) {
        var restingHeight by remember(maxWidth) { mutableStateOf(maxHeight) }
        SideEffect { if (!keyboardOpen) restingHeight = maxHeight }
        Box(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).height(restingHeight)) {
            StoredWallpaper(wallpaper)
        }
    }
}

@Composable
internal fun BoxScope.StoredWallpaper(wallpaper: ConversationWallpaperStore) {
    val context = LocalContext.current
    val revision = wallpaper.revision
    val request = remember(context, wallpaper.file, revision) {
        ImageRequest.Builder(context).data(wallpaper.file)
            .memoryCacheKey("conversation-wallpaper:$revision").build()
    }
    AsyncImage(request, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
}

@Composable
internal fun WallpaperSettings(wallpaper: ConversationWallpaperStore) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            error = null
            try {
                draft = wallpaper.load(uri)
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (invalid: IllegalArgumentException) {
                error = invalid.message
            } catch (_: Exception) {
                error = "无法读取这张图片，请重新选择。"
            } finally {
                busy = false
            }
        }
    }
    val custom = wallpaper.revision != null
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("壁纸用于新任务和所有会话，仅保存在这台手机上。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = wallpaperShape, color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.fillMaxWidth().height(148.dp), contentAlignment = Alignment.Center) {
                if (custom) StoredWallpaper(wallpaper)
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface) {
                    Text(if (custom) "当前壁纸" else "默认背景", Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        CatsCoButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        ) { Text(if (custom) "更换壁纸" else "从相册选择") }
        if (custom) CatsCoSecondaryButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            onClick = {
                scope.launch {
                    busy = true
                    error = null
                    try { wallpaper.reset() }
                    catch (canceled: CancellationException) { throw canceled }
                    catch (_: Exception) { error = "无法恢复默认壁纸，请重试。" }
                    finally { busy = false }
                }
            },
        ) { Text("恢复默认") }
        if (busy && draft == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("JPG、PNG 或 WebP，最大 20 MB。裁剪后至少 720 × 1280 像素。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    draft?.let { bitmap ->
        WallpaperCropDialog(
            image = bitmap,
            busy = busy,
            error = error,
            onDismiss = { draft = null; error = null },
            onSave = { crop ->
                scope.launch {
                    busy = true
                    error = null
                    try {
                        wallpaper.save(bitmap, crop)
                        draft = null
                    } catch (canceled: CancellationException) {
                        throw canceled
                    } catch (_: Exception) {
                        error = "无法保存壁纸，请重试。"
                    } finally {
                        busy = false
                    }
                }
            },
        )
    }
}

@Composable
private fun WallpaperCropDialog(
    image: Bitmap,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (WallpaperCrop) -> Unit,
) {
    val configuration = LocalConfiguration.current
    val aspect = (configuration.screenWidthDp.toFloat() / configuration.screenHeightDp.coerceAtLeast(1))
        .coerceIn(.3f, 3f)
    val bitmap = remember(image) { image.asImageBitmap() }
    val source = remember(image) { IntSize(image.width, image.height) }
    val base = remember(source, aspect) { wallpaperCrop(source, aspect, centeredCrop, 1f) }
    val maxZoom = min(
        min(base.size.width, base.size.height) / 720f,
        maxOf(base.size.width, base.size.height) / 1280f,
    ).coerceIn(1f, 4f)
    var previewConversation by remember { mutableStateOf(false) }
    var center by remember(image, aspect) { mutableStateOf(centeredCrop) }
    var zoom by remember(image, aspect) { mutableFloatStateOf(1f) }
    val crop = wallpaperCrop(source, aspect, center, zoom)
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("预览壁纸", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !previewConversation, onClick = { previewConversation = false }, label = { Text("新任务") })
                    FilterChip(selected = previewConversation, onClick = { previewConversation = true }, label = { Text("会话") })
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val previewWidth = minOf(maxWidth, maxHeight * aspect, 380.dp)
                    Box(
                        Modifier.width(previewWidth).height(previewWidth / aspect).clip(wallpaperShape)
                            .background(MaterialTheme.colorScheme.background)
                            .pointerInput(image, aspect, maxZoom, busy) {
                                if (!busy) detectTransformGestures { centroid, pan, factor, _ ->
                                    val old = wallpaperCrop(source, aspect, center, zoom)
                                    val nextZoom = (zoom * factor).coerceIn(1f, maxZoom)
                                    val next = wallpaperCrop(source, aspect, center, nextZoom)
                                    val anchor = Offset(
                                        (centroid.x / size.width - .5f),
                                        (centroid.y / size.height - .5f),
                                    )
                                    val desired = Offset(
                                        (old.offset.x + old.size.width / 2f + anchor.x * (old.size.width - next.size.width) -
                                            pan.x / size.width * next.size.width) / source.width,
                                        (old.offset.y + old.size.height / 2f + anchor.y * (old.size.height - next.size.height) -
                                            pan.y / size.height * next.size.height) / source.height,
                                    )
                                    val clamped = wallpaperCrop(source, aspect, desired, nextZoom)
                                    center = Offset(
                                        (clamped.offset.x + clamped.size.width / 2f) / source.width,
                                        (clamped.offset.y + clamped.size.height / 2f) / source.height,
                                    )
                                    zoom = nextZoom
                                }
                            },
                    ) {
                        Canvas(Modifier.matchParentSize()) {
                            drawImage(bitmap, srcOffset = crop.offset, srcSize = crop.size,
                                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                                filterQuality = FilterQuality.Medium)
                        }
                        WallpaperPreviewContent(previewConversation)
                    }
                }
                val message = error ?: if (crop.meetsMinimum) "拖动调整位置，双指缩放" else "裁剪区域至少需要 720 × 1280 像素"
                Text(message, color = if (error == null && crop.meetsMinimum) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CatsCoSecondaryButton(modifier = Modifier.weight(1f), enabled = !busy, onClick = onDismiss) { Text("取消") }
                    CatsCoButton(modifier = Modifier.weight(1f), enabled = !busy && crop.meetsMinimum, onClick = { onSave(crop) }) {
                        Text(if (busy) "正在保存" else "设为壁纸")
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.WallpaperPreviewContent(conversation: Boolean) {
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Text(if (conversation) "会话预览" else "新任务", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.weight(1f))
        if (conversation) {
            Surface(Modifier.align(Alignment.End), shape = wallpaperShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Text("消息预览", Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Surface(shape = wallpaperShape, color = MaterialTheme.colorScheme.surface) {
                Text("消息气泡在壁纸上的效果", Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Text("需要为您做什么？", Modifier.align(Alignment.CenterHorizontally),
                style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.weight(1f))
        Surface(Modifier.fillMaxWidth(), shape = wallpaperShape, color = MaterialTheme.colorScheme.surface) {
            Text(if (conversation) "发送消息" else "描述你的任务", Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}
