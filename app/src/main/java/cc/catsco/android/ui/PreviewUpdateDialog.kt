package cc.catsco.android.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import cc.catsco.android.BuildConfig
import cc.catsco.android.data.PreviewUpdate
import cc.catsco.android.data.checkPreviewUpdate
import cc.catsco.android.data.downloadPreviewUpdate
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class UpdateDialog { Checking, Current, Available, Downloading, Ready, Permission, CheckFailed, DownloadFailed, InstallFailed }

private const val deferredPreviewVersion = "deferred_version"

@Composable
internal fun rememberPreviewUpdateAction(autoCheck: Boolean): () -> Unit {
    if (!BuildConfig.DEBUG) return {}
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("preview_updates", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var checkedAutomatically by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<UpdateDialog?>(null) }
    var update by remember { mutableStateOf<PreviewUpdate?>(null) }
    var downloadedName by rememberSaveable { mutableStateOf<String?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun check(manual: Boolean) {
        if (manual) dialog = UpdateDialog.Checking
        job?.cancel()
        job = scope.launch {
            try {
                val available = checkPreviewUpdate(context)
                update = available
                downloadedName = null
                dialog = when {
                    available == null -> if (manual) UpdateDialog.Current else null
                    manual || available.number > preferences.getInt(deferredPreviewVersion, 0) -> UpdateDialog.Available
                    else -> null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (manual) dialog = UpdateDialog.CheckFailed
            }
        }
    }

    fun install() {
        val file = downloadedName?.let { File(context.cacheDir, "preview-updates/$it") }
        if (file == null || !file.isFile) {
            dialog = UpdateDialog.DownloadFailed
            return
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            dialog = UpdateDialog.Permission
            return
        }
        try {
            val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", file)
            context.startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            dialog = UpdateDialog.Ready
        } catch (_: Exception) {
            dialog = UpdateDialog.InstallFailed
        }
    }

    val permissionSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) install()
    }

    fun download() {
        val release = update ?: return
        job?.cancel()
        progress = 0
        dialog = UpdateDialog.Downloading
        job = scope.launch {
            try {
                downloadedName = downloadPreviewUpdate(context, release) { value ->
                    withContext(Dispatchers.Main) { progress = value }
                }.name
                install()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                dialog = UpdateDialog.DownloadFailed
            }
        }
    }

    LaunchedEffect(autoCheck) {
        if (autoCheck && !checkedAutomatically) {
            delay(1600)
            checkedAutomatically = true
            if (dialog == null && job?.isActive != true) check(false)
        }
    }

    dialog?.let { current ->
        val version = update?.number
        val title = when (current) {
            UpdateDialog.Checking -> "正在检查更新"
            UpdateDialog.Current -> "已是最新测试版"
            UpdateDialog.Available -> "发现新版本"
            UpdateDialog.Downloading -> "正在下载更新"
            UpdateDialog.Ready -> "更新已下载"
            UpdateDialog.Permission -> "允许安装更新"
            UpdateDialog.CheckFailed -> "无法检查更新"
            UpdateDialog.DownloadFailed -> "无法下载更新"
            UpdateDialog.InstallFailed -> "无法打开安装程序"
        }
        val body = when (current) {
            UpdateDialog.Checking -> "正在连接 GitHub…"
            UpdateDialog.Current -> "当前版本 ${BuildConfig.VERSION_NAME}"
            UpdateDialog.Available -> "CatsCo 测试版 Preview $version"
            UpdateDialog.Downloading -> "$progress%"
            UpdateDialog.Ready -> "在安卓系统弹窗中确认安装。"
            UpdateDialog.Permission -> "在系统设置中允许 CatsCo 安装应用，返回后即可继续。"
            UpdateDialog.CheckFailed -> "暂时无法连接 GitHub，请稍后重试。"
            UpdateDialog.DownloadFailed -> "安装包下载或校验未完成，请重试。"
            UpdateDialog.InstallFailed -> "请检查系统安装权限后重试。"
        }
        val close = {
            if (current == UpdateDialog.Available && version != null &&
                version > preferences.getInt(deferredPreviewVersion, 0)
            ) {
                preferences.edit().putInt(deferredPreviewVersion, version).apply()
            }
            job?.cancel()
            dialog = null
        }
        CatsCoAlertDialog(
            onDismissRequest = close,
            title = { Text(title) },
            text = {
                Column {
                    Text(body)
                    if (current == UpdateDialog.Downloading) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            dismissButton = if (current in setOf(UpdateDialog.Available, UpdateDialog.Permission, UpdateDialog.Ready,
                    UpdateDialog.CheckFailed, UpdateDialog.DownloadFailed, UpdateDialog.InstallFailed)) {
                { CatsCoSecondaryButton(onClick = close) { Text("稍后") } }
            } else null,
            confirmButton = {
                when (current) {
                    UpdateDialog.Available -> CatsCoButton(onClick = ::download) { Text("下载并安装") }
                    UpdateDialog.Ready, UpdateDialog.InstallFailed -> CatsCoButton(onClick = ::install) { Text("安装") }
                    UpdateDialog.Permission -> CatsCoButton(onClick = {
                        try {
                            permissionSettings.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.fromParts("package", context.packageName, null)))
                        } catch (_: Exception) {
                            dialog = UpdateDialog.InstallFailed
                        }
                    }) { Text("打开设置") }
                    UpdateDialog.CheckFailed -> CatsCoButton(onClick = { check(true) }) { Text("重试") }
                    UpdateDialog.DownloadFailed -> CatsCoButton(onClick = {
                        if (update != null) download() else check(true)
                    }) { Text("重试") }
                    UpdateDialog.Downloading, UpdateDialog.Checking -> CatsCoSecondaryButton(onClick = close) { Text("取消") }
                    UpdateDialog.Current -> CatsCoButton(onClick = close) { Text("完成") }
                }
            },
        )
    }
    return {
        checkedAutomatically = true
        check(true)
    }
}
