package cc.catsco.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.catsco.android.WorkspaceViewModel
import cc.catsco.android.R
import cc.catsco.android.data.first
import cc.catsco.android.data.attachment
import cc.catsco.android.data.attachmentDescription
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun ModuleSection(title: String? = null, spacing: Dp = 16.dp,
    contentPadding: PaddingValues = PaddingValues(16.dp), content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        title?.let {
            Text(it, Modifier.padding(start = 16.dp).semantics { heading() }, fontSize = CatsCoMobileSpec.settingsSectionText,
                lineHeight = 24.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        }
        Surface(shape = CatsCoMobileSpec.settingsGroupShape, color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.fillMaxWidth().padding(contentPadding), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
        }
    }
}

@Composable
internal fun ModuleTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    errorMessage: String? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    var visited by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val showError = errorMessage != null && visited && !focused
    val interaction = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors(
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        focusedBorderColor = if (MaterialTheme.colorScheme.background.luminance() < .5f) Color(0xFF6C6C76) else Color(0xFF8C8C96),
        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        focusedContainerColor = MaterialTheme.colorScheme.surface,
        focusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val labelPadding = if (label == null) 0.dp else with(LocalDensity.current) {
        MaterialTheme.typography.bodySmall.lineHeight.toDp() / 2
    }
    BasicTextField(
        value, onValueChange, modifier.onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) visited = true
        }.padding(top = labelPadding).defaultMinSize(minHeight = 56.dp), enabled = enabled,
        singleLine = singleLine, minLines = minLines, maxLines = maxLines,
        interactionSource = interaction,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
        textStyle = TextStyle(fontFamily = CatsCoFontFamily, fontSize = 16.sp, lineHeight = 24.sp, color = MaterialTheme.colorScheme.onSurface),
        visualTransformation = visualTransformation, keyboardOptions = keyboardOptions,
        decorationBox = { inner ->
            OutlinedTextFieldDefaults.DecorationBox(value = value, innerTextField = inner, enabled = enabled,
                singleLine = singleLine, visualTransformation = visualTransformation, interactionSource = interaction,
                isError = isError || showError, label = label, placeholder = placeholder,
                supportingText = if (showError) ({ Text(errorMessage.orEmpty()) }) else supportingText,
                colors = colors, container = {
                    OutlinedTextFieldDefaults.Container(enabled, isError || showError, interaction, colors = colors,
                        shape = CatsCoMobileSpec.fieldShape, focusedBorderThickness = 1.dp, unfocusedBorderThickness = 1.dp)
                })
        },
    )
}

internal fun requiredFieldError(value: String, label: String): String? = if (value.isBlank()) "请填写$label" else null

internal fun serverAddressError(value: String): String? = runCatching {
    cc.catsco.core.ServerAddress.parse(value)
}.exceptionOrNull()?.message

@Composable
internal fun ModuleEmpty(title: String, description: String = "", action: @Composable (() -> Unit)? = null,
    icon: Int? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        icon?.let { Icon(painterResource(it), null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(title, fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        if (description.isNotBlank()) Text(description, fontSize = 14.sp, lineHeight = 22.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

@Composable
internal fun ModuleError(message: String, status: Int?, retry: () -> Unit, back: () -> Unit) {
    ModuleSection {
        ModuleEmpty(
            title = when (status) {
                403 -> "暂无访问权限"
                404 -> "此功能暂不可用"
                else -> "读取未完成"
            },
            description = when (status) {
                403 -> "当前账号暂时无法访问此内容。"
                404 -> "当前服务器尚未提供此功能，或内容已被移除。"
                else -> message
            },
            icon = if (status == 403) R.drawable.e_key else R.drawable.e_refresh,
            action = {
                if (status == 403 || status == 404) CatsCoSecondaryButton(onClick = back) { Text("返回上一页") }
                else CatsCoSecondaryButton(onClick = retry) { Text("重新读取") }
            },
        )
    }
}

@Composable
internal fun ModuleLoading() {
    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text("正在读取…", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ModuleInfo(label: String, value: String) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val labelLimit = maxWidth * .45f
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.widthIn(max = labelLimit), fontSize = 16.sp, lineHeight = 24.sp)
            SelectionContainer(Modifier.weight(1f)) {
                Text(value, Modifier.fillMaxWidth(), fontSize = 16.sp, lineHeight = 24.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
            }
        }
    }
}

@Composable
internal fun ModuleInfoRows(values: List<Pair<String, String>>) {
    val visible = values.filter { it.second.isNotBlank() }
    Column {
        visible.forEachIndexed { index, (label, value) ->
            ModuleInfo(label, value)
            if (index < visible.lastIndex) HorizontalDivider(thickness = .5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f))
        }
    }
}

internal fun skillPublisher(skill: JSONObject): String {
    val author = skill.opt("author")
    val name = when (author) {
        is JSONObject -> author.first("displayName", "display_name", "name", "username")
        is String -> author.trim()
        else -> ""
    }
    return name.ifBlank { skill.first("author_name", "owner_name") }
}

internal fun skillVersion(skill: JSONObject): String = (skill.optJSONObject("version") ?: skill)
    .first("latestVersion", "latest_version", "version")

internal fun skillCatalogueTitle(skill: JSONObject): String =
    skill.first("displayName", "display_name", "title", "name", "skillId", "skill_id", "id").ifBlank { "未命名技能" }

internal fun skillCatalogueDescription(skill: JSONObject): String =
    searchSnippetText(skill.first("summary", "description"))

internal fun skillCatalogueStatus(skill: JSONObject, reference: JSONObject?, showAdded: Boolean): String {
    if (reference == null) return ""
    val current = reference.first("version")
    val latest = skillVersion(skill)
    return when {
        current.isNotBlank() && latest.isNotBlank() && current != latest -> "可更新"
        showAdded -> "已添加"
        else -> ""
    }
}

internal fun displayModuleStatus(value: String): String = when (value.lowercase()) {
    "shared" -> "共享服务"
    "active", "enabled" -> "生效中"
    "applied" -> "已应用"
    "ready" -> "可用"
    "not_configured" -> "尚未配置"
    "created" -> "创建中"
    "pending" -> "等待处理"
    "paid" -> "支付成功"
    "fulfilled" -> "已生效"
    "closed" -> "已关闭"
    "failed", "error" -> "未成功"
    "refunding" -> "退款中"
    "refunded" -> "已退款"
    "cancelled", "canceled" -> "已取消"
    "expired" -> "已到期"
    "revoked" -> "已撤销"
    "disabled" -> "未启用"
    "connected", "approved" -> "已连接"
    "offline" -> "离线"
    "online" -> "在线"
    "custom" -> "自备模型"
    "local" -> "员工本地模型"
    "general" -> "通用 Agent"
    else -> value
}

internal fun displayOrderStatus(value: String): String = when (value) {
    "pending" -> "待支付"
    "failed" -> "创建失败"
    else -> displayModuleStatus(value)
}

internal fun displayModuleDate(value: String, includeTime: Boolean = true): String {
    if (value.isBlank()) return ""
    val instant = runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
    if (instant != null) return DateTimeFormatter.ofPattern(
        if (includeTime) "yyyy年M月d日 HH:mm" else "yyyy年M月d日", Locale.CHINA,
    ).withZone(ZoneId.systemDefault()).format(instant)
    return runCatching { LocalDate.parse(value).format(DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)) }
        .getOrDefault(value)
}

internal fun orderAmount(order: JSONObject): String {
    val yuan = order.first("amount_cny").toBigDecimalOrNull()
        ?: order.first("amount_fen", "price_fen").toBigDecimalOrNull()?.divide(BigDecimal(100))
        ?: return ""
    val currency = order.optString("currency", "CNY").uppercase(Locale.ROOT)
    val amount = yuan.stripTrailingZeros().toPlainString()
    return if (currency == "CNY") "¥$amount" else "$currency $amount"
}

internal fun fileRecordDescription(record: JSONObject): String = listOf(
    attachmentDescription(attachment(record.optJSONObject("attachment") ?: record)),
    displayModuleDate(record.first("created_at", "updated_at"), includeTime = false),
).filter(String::isNotBlank).joinToString(" · ")

internal fun highlightedSnippet(text: String, query: String, foreground: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    if (query.isNotBlank()) {
        var start = text.indexOf(query.trim(), ignoreCase = true)
        while (start >= 0) {
            addStyle(SpanStyle(color = foreground, fontWeight = FontWeight.SemiBold), start, start + query.trim().length)
            start = text.indexOf(query.trim(), startIndex = start + query.trim().length, ignoreCase = true)
        }
    }
}

@Composable
internal fun ModuleChoice(label: String, value: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit,
    enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled) { open = true },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), fontSize = 16.sp, lineHeight = 24.sp)
        Text(options.find { it.first == value }?.second ?: value, Modifier.widthIn(max = 170.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(painterResource(R.drawable.e_expand), null, Modifier.size(CatsCoMobileSpec.actionIconSize).graphicsLayer { rotationZ = -90f },
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f))
    }
    if (open) CatsCoAlertDialog(onDismissRequest = { open = false }, title = { Text(label) }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            options.forEach { (id, name) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onSelect(id); open = false },
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(value == id, onClick = null)
                    Text(name, Modifier.padding(start = 12.dp), fontSize = 16.sp)
                }
            }
        }
    }, confirmButton = { CatsCoSecondaryButton(onClick = { open = false }) { Text("取消") } })
}

internal fun reasoningLabel(value: String): String = when (value) {
    "" -> "使用默认值"
    "none", "disabled" -> "关闭"
    "minimal" -> "最低"
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    "xhigh" -> "更高"
    "max" -> "最高"
    else -> value
}

internal fun modelApplyExplanation(detail: String): String = when {
    Regex("401|403|api.?key|auth|鉴权|凭证", RegexOption.IGNORE_CASE).containsMatchIn(detail) -> "模型服务鉴权失败，请检查访问凭证。"
    Regex("quota|balance|rate.?limit|402|429|额度|余额", RegexOption.IGNORE_CASE).containsMatchIn(detail) -> "模型额度不足或请求受到限制，请检查额度后重试。"
    Regex("timeout|network|econn|网络|超时", RegexOption.IGNORE_CASE).containsMatchIn(detail) -> "模型服务连接失败，请检查网络与服务地址。"
    else -> "员工尚未成功应用这次配置，请检查设置后重新保存。"
}

/** Values survive navigation and refresh. Persistence uses the existing account-scoped encrypted store. */
@Stable
internal class ModuleDraft(
    initial: Map<String, String>,
    saved: Map<String, String>,
    private val persist: (Map<String, String>) -> Unit,
) {
    private val baseline = mutableStateMapOf<String, String>().apply { putAll(initial) }
    private val values = mutableStateMapOf<String, String>().apply {
        putAll(initial)
        putAll(saved.filterKeys { it in initial })
    }
    val dirty: Boolean get() = values.toMap() != baseline.toMap()
    operator fun get(field: String): String = values[field].orEmpty()
    fun update(field: String, value: String) {
        values[field] = value
        persist(if (dirty) values.toMap() else emptyMap())
    }
    fun snapshot(): Map<String, String> = values.toMap()
    fun reconcile(initial: Map<String, String>) {
        if (baseline.toMap() == initial) return
        initial.forEach { (field, value) ->
            if (values[field] == baseline[field]) values[field] = value
        }
        baseline.clear()
        baseline.putAll(initial)
        persist(if (dirty) values.toMap() else emptyMap())
    }
    fun accept(sent: Map<String, String>) {
        baseline.putAll(sent)
        persist(if (dirty) values.toMap() else emptyMap())
    }
    fun discard() {
        values.clear()
        values.putAll(baseline)
        persist(emptyMap())
    }
}

@Composable
internal fun rememberModuleDraft(model: WorkspaceViewModel, page: String, initial: Map<String, String>): ModuleDraft {
    val workspace by model.state.collectAsState()
    val session = workspace.session
    val draft = remember(model, session?.server, session?.userId, page) {
        ModuleDraft(initial, model.moduleDraft(page)) { model.saveModuleDraft(page, it) }
    }
    LaunchedEffect(draft, initial) { draft.reconcile(initial) }
    return draft
}

@Composable
internal fun ModuleDraftStatus(draft: ModuleDraft) {
    if (draft.dirty) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("已保留本机草稿", Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CatsCoSecondaryButton(onClick = draft::discard) { Text("放弃修改") }
    }
}
