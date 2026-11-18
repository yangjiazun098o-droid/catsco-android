package cc.catsco.android.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

/** Operation feedback must not resize the conversation or expose HTTP diagnostics. */
@Composable
internal fun WorkspaceErrorFeedback(error: String?, onConsumed: (String) -> Unit) {
    val host = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        error?.let {
            val message = if (it.startsWith("请求失败（")) "暂时无法完成操作，请重试。" else it
            host.showSnackbar(message, duration = SnackbarDuration.Short)
            onConsumed(it)
        }
    }
    SnackbarHost(host)
}
