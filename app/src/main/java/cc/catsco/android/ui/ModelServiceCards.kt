package cc.catsco.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.catsco.android.WorkspaceState
import cc.catsco.android.WorkspaceViewModel
import cc.catsco.android.data.first
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

@Composable
internal fun ModelUsageCard(summary: JSONObject, title: String? = null, details: List<Pair<String, String>> = emptyList()) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val remaining = summary.optDouble("remaining_percent", Double.NaN).takeIf { it.isFinite() }
    val configured = summary.optBoolean("quota_configured")
    ModuleSection {
        if (!title.isNullOrBlank()) Text(title, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
        Text(if (configured) "剩余额度" else "模型服务", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (configured) {
            Text(if (remaining == null) "暂未提供" else "${remaining.toInt()}%",
                fontSize = if (remaining == null) 18.sp else 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium)
            if (remaining != null) LinearProgressIndicator(progress = { (remaining / 100).coerceIn(0.0, 1.0).toFloat() },
                modifier = Modifier.fillMaxWidth().height(5.dp), color = Mint,
                trackColor = MaterialTheme.colorScheme.secondaryContainer)
        }
        summary.first("model").takeIf(String::isNotBlank)?.let { Text(it, fontSize = 16.sp) }
        HorizontalDivider(thickness = .5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).catsCoDisclosureClickable(expanded) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically) {
            Text("模型服务详情", Modifier.weight(1f), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CatsCoDisclosureChevron(expanded, size = 23.dp)
        }
        CatsCoSectionReveal(expanded) { ModuleInfoRows(listOf(
            "服务来源" to displayModuleStatus(summary.first("provider")),
            "状态" to when (summary.optString("status")) {
                "custom" -> "自备模型"
                "over_limit" -> "额度已用尽"
                "" -> ""
                else -> displayModuleStatus(summary.first("status"))
            },
        ) + details) }
    }
}

@Composable
internal fun CredentialValue(label: String, value: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 14.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth()) {
            SelectionContainer(Modifier.fillMaxWidth().padding(end = if (action == null) 0.dp else 56.dp)) {
                Text(value.ifBlank { "暂未提供" }, fontSize = 16.sp, lineHeight = 24.sp)
            }
            if (action != null) Box(Modifier.align(Alignment.CenterEnd).height(24.dp).wrapContentSize(unbounded = true), contentAlignment = Alignment.Center) { action() }
        }
    }
}

@Composable
internal fun InviteRedemption(state: WorkspaceState, model: WorkspaceViewModel) {
    var invite by rememberSaveable(state.session?.userId, state.session?.server) { mutableStateOf("") }
    ModuleSection("邀请码") {
        ModuleTextField(invite, { invite = it }, Modifier.fillMaxWidth(), label = { Text("邀请码") }, singleLine = true,
            enabled = !state.busy, errorMessage = requiredFieldError(invite, "邀请码"))
        CatsCoButton(onClick = { model.action { session ->
            val sent = invite.trim()
            model.request(session, "/api/relay/invite/redeem", method = "POST", body = JSONObject().put("code", sent))
            currentCoroutineContext().ensureActive()
            if (invite.trim() == sent) invite = ""
            model.notice("兑换成功。")
            model.refreshEntitlementsAfterRedemption()
        } }, modifier = Modifier.fillMaxWidth(), enabled = invite.isNotBlank() && !state.busy) { Text("兑换") }
    }
}
