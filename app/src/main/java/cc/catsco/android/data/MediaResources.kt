package cc.catsco.android.data

import cc.catsco.core.ServerAddress
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.Locale

private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif", "svg")

internal fun isImageAttachment(mime: String, name: String, url: String = ""): Boolean {
    val type = mime.substringBefore(';').trim().lowercase(Locale.ROOT)
    if (type.startsWith("image/")) return true
    if (type.isNotEmpty() && type != "application/octet-stream") return false
    return sequenceOf(name, url.substringBefore('?').substringBefore('#').substringAfterLast('/'))
        .any { it.substringAfterLast('.', "").lowercase(Locale.ROOT) in imageExtensions }
}

/** Match the desktop media origins while retaining custom-server and absolute URLs. */
internal fun mediaUrl(server: ServerAddress, path: String): HttpUrl? {
    if (path.isBlank()) return null
    val origin = server.value.toHttpUrl()
    val mediaOrigin = if (origin.port == 443) when (origin.host) {
        "catsco.cc", "www.catsco.cc", "app.catsco.cc" -> "https://i.catsco.cc"
        "catsco.cn", "www.catsco.cn", "app.catsco.cn" -> "https://i.catsco.cn"
        else -> null
    } else null
    val base = if (path.startsWith("/uploads/") && mediaOrigin != null) mediaOrigin.toHttpUrl() else origin
    return base.resolve(path)?.takeIf { it.scheme == "https" && it.username.isEmpty() && it.password.isEmpty() }
}
