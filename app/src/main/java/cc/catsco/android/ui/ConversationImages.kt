package cc.catsco.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cc.catsco.android.OpenLocalFile
import cc.catsco.android.R
import cc.catsco.android.data.isImageAttachment
import cc.catsco.android.data.mediaUrl
import cc.catsco.core.Attachment
import cc.catsco.core.Session
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.security.MessageDigest

internal class ConversationImages(
    val session: Session,
    val loader: ImageLoader,
    val onPreview: (Attachment) -> Unit,
    val previewEnabled: Boolean,
    val attachmentFailure: Pair<String, String>?,
)

internal val LocalConversationImages = staticCompositionLocalOf<ConversationImages?> { null }

@Composable
internal fun messageImageRequest(file: Attachment): ImageRequest? {
    val session = LocalConversationImages.current?.session ?: return null
    if (!isImageAttachment(file.mime, file.name, file.url)) return null
    val context = LocalContext.current
    return remember(context, session, file.url) {
        val url = mediaUrl(session.server, file.url) ?: return@remember null
        val origin = session.server.value.toHttpUrl()
        val cacheKey = MessageDigest.getInstance("SHA-256")
            .digest("message-image|${session.server.value}|${session.userId}|$url".toByteArray())
            .joinToString("") { "%02x".format(it) }
        ImageRequest.Builder(context)
            .data(url.toString())
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .apply {
                if (origin.scheme == url.scheme && origin.host == url.host && origin.port == url.port) {
                    addHeader("Authorization", "Bearer ${session.token}")
                }
            }
            .build()
    }
}

@Composable
internal fun MessageImageThumbnail(
    file: Attachment,
    request: ImageRequest,
    enabled: Boolean,
    onPreview: (Attachment) -> Unit,
    onDownload: ((Attachment) -> Unit)? = null,
    error: String? = null,
) {
    val images = LocalConversationImages.current ?: return
    var failed by remember(request) { mutableStateOf(false) }
    var loaded by remember(request) { mutableStateOf(false) }
    val aspect = remember(file.raw) {
        val metadata = runCatching { JSONObject(file.raw) }.getOrNull()
        val width = metadata?.optInt("width") ?: 0
        val height = metadata?.optInt("height") ?: 0
        if (width > 0 && height > 0) (width.toFloat() / height).coerceIn(.8f, 1.8f) else 4f / 3f
    }
    Column(Modifier.widthIn(max = 280.dp).fillMaxWidth().testTag("message-image-thumbnail")) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(aspect)
                .clip(CatsCoMobileSpec.contentCardShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = "查看大图") { onPreview(file) },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = request,
                contentDescription = file.name,
                imageLoader = images.loader,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                onSuccess = { failed = false; loaded = true },
            )
            if (failed || error != null) {
                Text(
                    error ?: "图片加载失败，点按重试",
                    Modifier.padding(12.dp),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (!loaded) {
                Icon(painterResource(R.drawable.e_image), null, Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onDownload != null) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(file.name, Modifier.weight(1f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = { onDownload(file) }, enabled = enabled) {
                    Icon(painterResource(R.drawable.e_download), "下载 ${file.name}", Modifier.size(CatsCoMobileSpec.actionIconSize))
                }
            }
        }
    }
}

@Composable
internal fun AttachmentImagePreview(file: OpenLocalFile, loader: ImageLoader, onDismiss: () -> Unit) {
    var failed by remember(file.uri) { mutableStateOf(false) }
    val context = LocalContext.current
    val request = remember(context, file.uri) { ImageRequest.Builder(context).data(file.uri).build() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().testTag("attachment-image-preview")) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(file.name, Modifier.weight(1f), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = onDismiss) {
                    Icon(painterResource(R.drawable.e_close), "关闭图片", tint = Color.White)
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.Center) {
                val width = with(LocalDensity.current) { maxWidth.toPx() }
                val height = with(LocalDensity.current) { maxHeight.toPx() }
                var scale by remember(file.uri) { mutableFloatStateOf(1f) }
                var offset by remember(file.uri) { mutableStateOf(Offset.Zero) }
                AsyncImage(
                    model = request,
                    contentDescription = file.name,
                    imageLoader = loader,
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(file.uri, width, height) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                val boundX = width * (scale - 1f) / 2f
                                val boundY = height * (scale - 1f) / 2f
                                offset = Offset(
                                    (offset.x + pan.x).coerceIn(-boundX, boundX),
                                    (offset.y + pan.y).coerceIn(-boundY, boundY),
                                )
                            }
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                    contentScale = ContentScale.Fit,
                    onError = { failed = true },
                    onSuccess = { failed = false },
                )
                if (failed) Text("无法预览这张图片", color = Color.White)
            }
        }
    }
}
