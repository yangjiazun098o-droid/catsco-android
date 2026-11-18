package cc.catsco.android.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import cc.catsco.android.R
import cc.catsco.android.data.mediaUrl
import cc.catsco.core.Session
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest

/** Only same-origin API resources receive credentials. Public uploads also use the CDN. */
fun accountAvatarRequest(context: Context, session: Session): ImageRequest? {
    if (session.avatarUrl.isBlank()) return null
    val origin = session.server.value.toHttpUrl()
    val url = mediaUrl(session.server, session.avatarUrl) ?: return null
    if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    val identity = "${session.server.value}|${session.userId}|$url"
    val cacheKey = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
        .joinToString("") { "%02x".format(it) }
    return ImageRequest.Builder(context)
        .data(url.toString())
        .memoryCacheKey(cacheKey)
        .diskCacheKey(cacheKey)
        .apply {
            if (url.host == origin.host && url.port == origin.port && url.encodedPath.startsWith("/api/")) {
                addHeader("Authorization", "Bearer ${session.token}")
            }
        }
        .build()
}

@Composable
fun CatsCoAccountAvatar(
    session: Session?,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier.size(CatsCoMobileSpec.accountAvatarSize),
    shape: Shape = CircleShape,
    fallbackIcon: Int = R.drawable.e_account,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val request = remember(context, session) { session?.let { accountAvatarRequest(context, it) } }
    var loaded by remember(request?.memoryCacheKey) { mutableStateOf(false) }
    Surface(modifier = modifier, shape = shape, color = MaterialTheme.colorScheme.surfaceVariant) {
        Box(contentAlignment = Alignment.Center) {
            if (!loaded) Icon(painterResource(fallbackIcon), null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            if (request != null) key(request.memoryCacheKey) {
                AsyncImage(
                    model = request,
                    imageLoader = imageLoader,
                    contentDescription = null,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize(),
                    onLoading = { loaded = false },
                    onError = { loaded = false },
                    onSuccess = { loaded = true },
                )
            }
        }
    }
}
