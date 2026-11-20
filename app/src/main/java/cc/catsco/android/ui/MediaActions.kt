package cc.catsco.android.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import cc.catsco.android.BuildConfig
import cc.catsco.android.WorkspaceState
import cc.catsco.android.WorkspaceViewModel
import java.io.File
import java.util.UUID

data class MediaActions(val camera: () -> Unit, val photo: () -> Unit, val file: () -> Unit, val voice: (String) -> Boolean)

@Composable
fun rememberMediaActions(state: WorkspaceState, model: WorkspaceViewModel): MediaActions {
    val context = LocalContext.current
    var cameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::attach) }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(model::attach) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) cameraUri?.let(model::attach)
        cameraUri = null
    }
    val voicePreferences = remember(context) { context.getSharedPreferences("voice_input", Context.MODE_PRIVATE) }
    val consentKey = "cloud_transcription_accepted:${state.session?.server?.value.orEmpty()}"
    val voiceScope = listOf(state.session?.server?.value, state.session?.userId, state.page, state.employeeId.takeIf { state.page == "home" })
    var showVoiceConsent by remember(voiceScope) { mutableStateOf(false) }
    var awaitingVoicePermission by remember(voiceScope) { mutableStateOf(false) }
    val voicePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (awaitingVoicePermission) {
            awaitingVoicePermission = false
            if (granted) model.notice("按住麦克风说话。")
            else model.notice("麦克风权限未开启。可在系统设置中允许 CatsCo 使用麦克风。")
        }
    }
    val requestVoicePermission: () -> Unit = {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            model.notice("按住麦克风说话。")
        } else if (!awaitingVoicePermission) {
            awaitingVoicePermission = true
            voicePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    if (showVoiceConsent) CatsCoAlertDialog(
        onDismissRequest = { showVoiceConsent = false },
        title = { Text("语音输入") },
        text = { Text("录音会发送到当前 CatsCo 服务器转写。授权后，按住麦克风即可说话。") },
        confirmButton = {
            CatsCoButton(onClick = {
                showVoiceConsent = false
                voicePreferences.edit().putBoolean(consentKey, true).apply()
                requestVoicePermission()
            }) { Text("启用麦克风") }
        },
        dismissButton = { CatsCoSecondaryButton(onClick = { showVoiceConsent = false }) { Text("取消") } },
    )
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
        voice = { taskTitle ->
            when {
                !voicePreferences.getBoolean(consentKey, false) -> {
                    showVoiceConsent = true
                    false
                }
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> {
                    requestVoicePermission()
                    false
                }
                else -> model.startVoiceInput(taskTitle)
            }
        },
    )
}
