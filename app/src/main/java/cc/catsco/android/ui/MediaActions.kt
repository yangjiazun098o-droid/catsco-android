package cc.catsco.android.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import cc.catsco.android.BuildConfig
import cc.catsco.android.WorkspaceViewModel
import java.io.File
import java.util.UUID

data class MediaActions(val camera: () -> Unit, val photo: () -> Unit, val file: () -> Unit, val voice: () -> Unit)

@Composable
fun rememberMediaActions(model: WorkspaceViewModel): MediaActions {
    val context = LocalContext.current
    var cameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::attach) }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(model::attach) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) cameraUri?.let(model::attach)
        cameraUri = null
    }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { spoken ->
                // The user still submits the composed message with the send button.
                val current = model.state.value.draft
                model.draft(current + if (current.isBlank()) spoken else "\n$spoken")
            }
        }
    }
    return MediaActions(
        camera = {
            try {
                val directory = File(context.cacheDir, "camera").apply { mkdirs() }
                cameraUri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", File(directory, "${UUID.randomUUID()}.jpg"))
                camera.launch(cameraUri!!)
            } catch (_: Exception) { model.notice("无法打开相机，请从照片或文件选择图片。") }
        },
        photo = { photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        file = { file.launch(arrayOf("*/*")) },
        voice = {
            try {
                voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN").putExtra(RecognizerIntent.EXTRA_PROMPT, "说出任务内容"))
            } catch (_: Exception) { model.notice("手机未安装语音识别服务，可以使用键盘的语音输入。") }
        },
    )
}
