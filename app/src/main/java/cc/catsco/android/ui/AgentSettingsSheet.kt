package cc.catsco.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.catsco.android.R
import cc.catsco.android.WorkspaceState
import cc.catsco.android.WorkspaceViewModel
import cc.catsco.android.data.agentQuotaPresentation
import cc.catsco.core.Employee

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentSettingsSheet(employee: Employee, state: WorkspaceState, model: WorkspaceViewModel, sheet: SheetState) {
    val details = state.agentSettings ?: return
    val quota = details.quota?.let(::agentQuotaPresentation)
    val loadingText = if (details.loading) "读取中…" else "暂不可用"
    AgentSettingsLayout(sheet, model::dismissAgentSettings) {
        Surface(shape = CatsCoMobileSpec.settingsGroupShape, color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CatsCoAccountAvatar(
                    state.session?.copy(avatarUrl = employee.avatarUrl), model.accountImages,
                    Modifier.size(56.dp), RoundedCornerShape(14.dp), fallbackIcon = R.drawable.e_agent,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(employee.name, fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(if (employee.online) "当前在线" else "当前离线", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Column {
            val entries = buildList {
                add(SettingsEntry("当前模型", value = quota?.model ?: loadingText, action = null))
                add(SettingsEntry("剩余额度", value = quota?.remaining ?: loadingText, action = null))
                if (employee.owner) add(SettingsEntry("模型设置", R.drawable.e_settings, action = "model/${employee.id}"))
            }
            SettingsGroup("模型", entries, model::openAgentSettingsPage)
            details.error?.let { error ->
                Text(error, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                CatsCoSecondaryButton(onClick = { model.showAgentSettings(employee.id) }) { Text("重新读取") }
            }
        }
        val workspaceEntries = buildList {
            add(SettingsEntry("云端文件", R.drawable.e_cloud, action = "files/${employee.id}"))
            add(SettingsEntry("系统提示词", R.drawable.e_menu, action = "agent-prompt/${employee.id}"))
            add(SettingsEntry("知识库", R.drawable.e_knowledge, action = "agent-knowledge/${employee.id}"))
            add(SettingsEntry("应用", R.drawable.e_apps, action = "agent-apps/${employee.id}"))
            if (employee.owner) {
                add(SettingsEntry("管理员工", R.drawable.e_settings, action = "agent/${employee.id}"))
                add(SettingsEntry("技能管理", R.drawable.e_sparkle, action = "agent-skills/${employee.id}"))
            }
        }
        SettingsGroup("能力与工作空间", workspaceEntries, model::openAgentSettingsPage)
        SettingsGroup("身份", listOf(
            SettingsEntry("用户名", value = employee.username.ifBlank { "未提供" }, action = null),
            SettingsEntry("员工 ID", value = employee.id, action = null),
        ), model::openAgentSettingsPage)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentSettingsLayout(sheet: SheetState, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        shape = CatsCoMobileSpec.sheetShape,
        containerColor = MaterialTheme.colorScheme.background,
        scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = .22f),
        // The fixed-height sheet already leaves room above the status bar. Top inset padding
        // varies with the drag offset and would change its height and expanded anchor each frame.
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom) },
    ) {
        // There are four small sections. Measure them together so the bottom scroll limit
        // includes the identity card before the first gesture reaches it.
        Column(
            Modifier.fillMaxWidth().height(LocalConfiguration.current.screenHeightDp.dp * .88f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            content = content,
        )
    }
}
