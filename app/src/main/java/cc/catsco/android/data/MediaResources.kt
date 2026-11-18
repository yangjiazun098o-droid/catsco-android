package cc.catsco.android.data

import cc.catsco.core.ServerAddress
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

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
