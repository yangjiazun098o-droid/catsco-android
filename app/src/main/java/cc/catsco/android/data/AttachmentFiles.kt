package cc.catsco.android.data

import android.webkit.MimeTypeMap
import cc.catsco.core.Attachment
import cc.catsco.core.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

internal class AttachmentFiles(private val root: File, private val client: OkHttpClient) {
    private companion object { val lock = Mutex() }

    private fun digest(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun account(session: Session) = File(root, digest("${session.server.value}/${session.userId}"))

    suspend fun clear(session: Session) = withContext(Dispatchers.IO) {
        lock.withLock {
            account(session).deleteRecursively()
            // Previous releases wrote UUID-named files directly under the shared root.
            root.listFiles()?.filter(File::isFile)?.forEach(File::delete)
        }
    }

    private fun trim(now: Long, keep: File? = null) {
        val files = root.walkTopDown().maxDepth(2).filter { it.isFile && it != keep }.sortedByDescending(File::lastModified)
        var used = keep?.length() ?: 0L
        for (file in files) {
            if (file.extension == "part" || now - file.lastModified() > 86_400_000L || used + file.length() > 200L * 1024 * 1024) file.delete()
            else used += file.length()
        }
    }

    suspend fun read(session: Session, attachment: Attachment): Pair<File, String> = withContext(Dispatchers.IO) {
        lock.withLock {
            val url = mediaUrl(session.server, attachment.url) ?: error("无效的文件链接。")
            val directory = account(session).apply { mkdirs() }
            val key = digest(url.toString() + "\n" + attachment.name + "\n" + attachment.mime)
            val now = System.currentTimeMillis()
            val cached = directory.listFiles()?.firstOrNull { file ->
                file.isFile && file.name.startsWith("$key-") &&
                    (file.nameWithoutExtension.substringAfterLast('-').toLongOrNull() ?: 0L) > now
            }
            val mimeTypes = MimeTypeMap.getSingleton()
            val needsDetectedMime = attachment.mime == "application/octet-stream" || attachment.mime == "image/*"
            if (cached != null) {
                if (now - cached.lastModified() > 60_000L) cached.setLastModified(now)
                val mime = if (needsDetectedMime) mimeTypes.getMimeTypeFromExtension(cached.extension) ?: attachment.mime else attachment.mime
                return@withLock cached to mime
            }
            val origin = session.server.value.toHttpUrl()
            val request = Request.Builder().url(url)
            if (origin.scheme == url.scheme && origin.host == url.host && origin.port == url.port) {
                request.header("Authorization", "Bearer ${session.token}")
            }
            client.newCall(request.build()).execute().use { response ->
                if (response.code in setOf(401, 403)) error("此文件的访问权限已失效，请重新获取文件。")
                if (!response.isSuccessful) error("无法获取文件（${response.code}），请稍后重试。")
                val receivedAt = System.currentTimeMillis()
                val body = response.body ?: error("文件内容为空。")
                val limit = 100L * 1024 * 1024
                require(body.contentLength() <= limit) { "文件超过 100 MB，请在网页版打开。" }
                val detectedMime = body.contentType()?.toString()?.substringBefore(';')
                val mime = when {
                    attachment.mime == "application/octet-stream" -> detectedMime ?: attachment.mime
                    attachment.mime == "image/*" && detectedMime?.startsWith("image/") == true -> detectedMime
                    else -> attachment.mime
                }
                val extension = attachment.name.substringAfterLast('.', "").lowercase().takeIf { it.length in 1..16 && it.all(Char::isLetterOrDigit) }
                    ?: mimeTypes.getExtensionFromMimeType(mime) ?: "bin"
                val tmp = File.createTempFile("attachment-", ".part", directory)
                try {
                    body.byteStream().use { input -> tmp.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= limit) { "文件超过 100 MB。" }
                            output.write(buffer, 0, count)
                        }
                        check(body.contentLength() < 0 || total == body.contentLength()) { "文件下载不完整，请重试。" }
                    } }
                    val cache = response.cacheControl
                    val ageSeconds = response.header("Age")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                    val lifetime = when {
                        cache.noStore || cache.noCache -> 0L
                        cache.maxAgeSeconds >= 0 -> (cache.maxAgeSeconds.toLong() - ageSeconds).coerceIn(0L, 600L)
                        else -> 600L
                    }
                    val storedAt = System.currentTimeMillis()
                    val target = File(directory, "$key-${tmp.nameWithoutExtension}-${receivedAt + lifetime * 1000}.$extension")
                    check(tmp.renameTo(target)) { "无法保存附件，请检查可用空间。" }
                    trim(storedAt, target)
                    target to mime
                } catch (error: java.io.IOException) {
                    if (error.message.orEmpty().contains("ENOSPC", ignoreCase = true)) {
                        throw IllegalStateException("手机存储空间不足，请清理后重试。", error)
                    }
                    throw error
                } finally {
                    tmp.delete()
                }
            }
        }
    }
}
