package cc.catsco.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

private const val MaxWallpaperBytes = 20L * 1024 * 1024
private const val WallpaperDecodeEdge = 4096
private const val WallpaperOutputEdge = 2560

internal data class WallpaperCrop(val offset: IntOffset, val size: IntSize) {
    val meetsMinimum: Boolean
        get() = min(size.width, size.height) >= 720 && maxOf(size.width, size.height) >= 1280
}

internal fun wallpaperCrop(source: IntSize, aspect: Float, center: Offset, zoom: Float): WallpaperCrop {
    val width = (min(source.width.toFloat(), source.height * aspect) / zoom)
        .roundToInt().coerceIn(1, source.width)
    val height = (width / aspect).roundToInt().coerceIn(1, source.height)
    val left = (source.width * center.x - width / 2f).roundToInt().coerceIn(0, source.width - width)
    val top = (source.height * center.y - height / 2f).roundToInt().coerceIn(0, source.height - height)
    return WallpaperCrop(IntOffset(left, top), IntSize(width, height))
}

internal class ConversationWallpaperStore(private val context: Context) {
    val file = File(context.noBackupFilesDir, "conversation-wallpaper.webp")
    private val storage = AtomicFile(file)
    var revision by mutableStateOf(file.takeIf(File::isFile)?.lastModified())
        private set

    suspend fun load(uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        val temporary = File.createTempFile("wallpaper-", ".image", context.cacheDir)
        try {
            requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取这张图片。" }.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var bytes = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        bytes += count
                        require(bytes <= MaxWallpaperBytes) { "请选择 20 MB 以内的图片。" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.path, bounds)
            require(bounds.outMimeType in setOf("image/jpeg", "image/png", "image/webp")) {
                "请选择 JPG、PNG 或 WebP 图片。"
            }
            require(min(bounds.outWidth, bounds.outHeight) >= 720 && maxOf(bounds.outWidth, bounds.outHeight) >= 1280) {
                "图片太小，裁剪后至少需要 720 × 1280 像素。"
            }
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize.coerceAtLeast(1) > WallpaperDecodeEdge) {
                    inSampleSize = inSampleSize.coerceAtLeast(1) * 2
                }
            }
            val image = requireNotNull(BitmapFactory.decodeFile(temporary.path, options)) { "无法读取这张图片。" }
            val orientation = try {
                ExifInterface(temporary.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } catch (_: IOException) {
                ExifInterface.ORIENTATION_NORMAL
            }
            val transform = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                }
            }
            if (transform.isIdentity) image else Bitmap.createBitmap(image, 0, 0, image.width, image.height, transform, true)
                .also { if (it !== image) image.recycle() }
        } finally {
            temporary.delete()
        }
    }

    @Suppress("DEPRECATION")
    suspend fun save(image: Bitmap, crop: WallpaperCrop) = withContext(NonCancellable) {
        require(crop.meetsMinimum) { "裁剪后至少需要 720 × 1280 像素，请缩小裁剪比例。" }
        withContext(Dispatchers.IO) {
            val clipped = Bitmap.createBitmap(image, crop.offset.x, crop.offset.y, crop.size.width, crop.size.height)
            val scale = min(1f, WallpaperOutputEdge.toFloat() / maxOf(clipped.width, clipped.height))
            val scaled = if (scale == 1f) clipped else Bitmap.createScaledBitmap(
                clipped, (clipped.width * scale).roundToInt(), (clipped.height * scale).roundToInt(), true,
            )
            try {
                val output = storage.startWrite()
                try {
                    val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                    if (!scaled.compress(format, 88, output)) throw IOException("无法保存壁纸。")
                    storage.finishWrite(output)
                } catch (error: Exception) {
                    storage.failWrite(output)
                    throw error
                }
            } finally {
                if (scaled !== clipped) scaled.recycle()
                if (clipped !== image) clipped.recycle()
            }
        }
        revision = System.nanoTime()
    }

    suspend fun reset() = withContext(NonCancellable) {
        withContext(Dispatchers.IO) {
            storage.delete()
            if (file.exists()) throw IOException("无法恢复默认壁纸。")
        }
        revision = null
    }
}
