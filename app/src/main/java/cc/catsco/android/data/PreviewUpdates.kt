package cc.catsco.android.data

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

private const val repository = "yangjiazun098o-droid/catsco-android"
private const val apkName = "CatsCo-Android-Preview.apk"
private const val maxApkBytes = 150L * 1024 * 1024
private val previewTag = Regex("v0\\.1\\.0-preview\\.([1-9][0-9]{0,8})")
private val sha256Pattern = Regex("sha256:[a-f0-9]{64}")

internal data class PreviewUpdate(val number: Int, val url: String, val bytes: Long, val digest: String)

internal fun findPreviewUpdate(releases: JSONArray, installed: Long): PreviewUpdate? {
    return (0 until releases.length()).mapNotNull { index ->
        val release = releases.optJSONObject(index) ?: return@mapNotNull null
        if (release.optBoolean("draft", true) || !release.optBoolean("prerelease")) return@mapNotNull null
        val tag = release.optString("tag_name")
        val version = previewTag.matchEntire(tag)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
        if (version <= installed) return@mapNotNull null
        val assets = release.optJSONArray("assets") ?: return@mapNotNull null
        val url = "https://github.com/$repository/releases/download/$tag/$apkName"
        (0 until assets.length()).firstNotNullOfOrNull { assetIndex ->
            val asset = assets.optJSONObject(assetIndex) ?: return@firstNotNullOfOrNull null
            val bytes = asset.optLong("size")
            val digest = asset.optString("digest")
            if (asset.optString("name") == apkName && asset.optString("state") == "uploaded" &&
                asset.optString("browser_download_url") == url && bytes in 1..maxApkBytes &&
                sha256Pattern.matches(digest)
            ) PreviewUpdate(version, url, bytes, digest.removePrefix("sha256:")) else null
        }
    }.maxByOrNull { it.number }
}

private val client by lazy {
    val hosts = setOf("raw.githubusercontent.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    OkHttpClient.Builder()
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val url = chain.request().url
            if (!url.isHttps || url.host !in hosts) throw IOException("Unexpected update host")
            chain.proceed(chain.request())
        }
        .build()
}

@Suppress("DEPRECATION")
internal suspend fun checkPreviewUpdate(context: Context): PreviewUpdate? = withContext(Dispatchers.IO) {
    val installed = PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
    val request = Request.Builder()
        .url("https://raw.githubusercontent.com/$repository/preview-updates/update.json")
        .header("Cache-Control", "no-cache")
        .build()
    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Update check returned ${response.code}")
        val source = response.body?.source() ?: throw IOException("Missing update metadata")
        if (source.request(64L * 1024 + 1)) throw IOException("Update metadata too large")
        findPreviewUpdate(JSONArray(source.readUtf8()), installed)
    }
}

@Suppress("DEPRECATION")
internal suspend fun downloadPreviewUpdate(context: Context, update: PreviewUpdate, onProgress: suspend (Int) -> Unit): File =
    withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "preview-updates")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create update directory")
        val target = File(directory, "preview-${update.number}.apk")
        val temporary = File.createTempFile("download-", ".apk", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val request = Request.Builder().url(update.url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download returned ${response.code}")
                val body = response.body ?: throw IOException("Missing update package")
                var length = 0L
                var progress = -1
                body.byteStream().use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            length += read
                            if (length > update.bytes) throw IOException("Update package too large")
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            val percent = (length * 100 / update.bytes).toInt()
                            if (percent != progress) {
                                progress = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (length != update.bytes || hash != update.digest) throw IOException("Update package checksum mismatch")
            }
            val packageInfo = context.packageManager.getPackageArchiveInfo(temporary.absolutePath, 0)
            if (packageInfo == null || packageInfo.packageName != context.packageName ||
                PackageInfoCompat.getLongVersionCode(packageInfo) != update.number.toLong()
            ) throw IOException("Unexpected update package or version")
            ensureActive()
            if (!temporary.renameTo(target)) throw IOException("Cannot save update package")
            directory.listFiles()?.filter { it != target && it.name.startsWith("preview-") }?.forEach { it.delete() }
            target
        } finally {
            temporary.delete()
        }
    }
