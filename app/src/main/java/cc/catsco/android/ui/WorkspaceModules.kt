package cc.catsco.android.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import cc.catsco.android.*
import cc.catsco.android.R
import cc.catsco.android.data.*
import cc.catsco.core.Employee
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import dev.chrisbanes.haze.hazeSource

fun moduleTitle(page: String): String = when {
    page.startsWith("agent-access/") -> "员工资料"
    page.startsWith("agent-apps/") -> "应用"
    page.startsWith("agent-prompt/") -> "系统提示词"
    page.startsWith("agent-skills/") -> "技能管理"
    page.startsWith("agent-knowledge/") -> "知识库"
    page.startsWith("skill/") -> "技能详情"
    page.startsWith("agent/") -> "员工配置"
    page.startsWith("model/") -> "模型配置"
    page.startsWith("files/") -> "云端文件"
    page.startsWith("topic-files/") -> "对话文件"
    page.startsWith("select-device/") -> "选择电脑"
    else -> mapOf("apps" to "应用", "skills" to "SkillHub", "devices" to "我的电脑", "device-audit" to "连接记录", "desktop-connect" to "连接电脑", "profile" to "个人资料", "appearance" to "外观", "usage" to "模型用量", "commercial" to "套餐与权益", "redeem" to "兑换邀请码", "orders" to "订单记录", "relay-key" to "模型访问凭证", "files" to "云端文件", "agents" to "员工管理", "contacts" to "联系人与群组", "people-search" to "添加好友", "message-search" to "搜索消息", "service-agents" to "定制 Agent", "cloud-workers" to "云托管", "notifications" to "消息通知", "surfaces" to "桌面组件", "offline" to "缓存与草稿", "feedback" to "反馈", "server" to "服务器")[page] ?: "CatsCo"
}

@Composable
fun WorkspaceModuleScreen(state: WorkspaceState, model: WorkspaceViewModel, initialSearch: String = "") {
    val page = state.page
    val value = state.resource.value
    if (page == "apps" || page == "skills" || page.startsWith("agent-apps/")) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                if (page == "skills") SkillHub(state, model) else ApplicationsScreen(state, model)
            }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        val searchable = page in setOf("skills", "people-search", "message-search")
        if (state.resource.loaded || searchable) Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (state.resource.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (state.resource.loading && !state.resource.loaded && !searchable) { ModuleLoading(); return@Column }
        if (state.resource.error != null && !searchable) {
            Box(Modifier.padding(16.dp)) {
                ModuleError(state.resource.error, state.resource.errorStatus, { model.loadResource() }, model::back)
            }
            if (!state.resource.loaded || state.resource.errorStatus in setOf(403, 404)) return@Column
        }
        when {
            page.startsWith("skill/") -> SkillDetail(state, model)
            page.startsWith("agent-prompt/") -> ModuleForm {
                val prompt = value.first("prompt", "system_prompt", "content")
                if (prompt.isBlank()) Text("此 Agent 暂无可查看的系统提示词。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else SelectionContainer { MarkdownContent(prompt) }
            }
            page.startsWith("agent-knowledge/") -> AgentKnowledgeScreen(state, model)
            page.startsWith("agent-skills/") -> AgentSkillsScreen(state, model)
            page == "files" -> ModuleForm {
                ModuleEmpty("暂无可查看的 Agent", "添加 Agent 后，可以查看它生成或接收的云端文件。",
                    action = { CatsCoButton(onClick = { model.navigate("agents") }) { Text("管理员工") } })
            }
            page.startsWith("files/") || page.startsWith("topic-files/") -> Records(value.objects("artifacts", "files", "items"), R.drawable.e_file,
                header = if (page.startsWith("files/")) ({ ModuleChoice("Agent", page.removePrefix("files/"),
                    state.workspace.employees.map { it.id to it.name }, model::selectFileAgent) }) else null,
                empty = "还没有文件", emptyDescription = "此范围内的 Agent 生成或接收文件后，会在这里显示。", detail = ::fileRecordDescription,
                onClick = { model.openAttachment(attachment(it.optJSONObject("attachment") ?: it)) })
            page == "devices" && value.objects("devices").isEmpty() -> ModuleForm {
                ModuleSection {
                    ModuleEmpty("还没有连接电脑", "在电脑上打开 CatsCo Runtime，连接并授权后，员工可以在这台电脑上执行任务。", icon = R.drawable.e_computer)
                }
                SettingsGroup("电脑连接", listOf(
                    SettingsEntry("连接电脑", R.drawable.e_add, action = "desktop-connect"),
                    SettingsEntry("连接记录", R.drawable.e_refresh, action = "device-audit"),
                    SettingsEntry("云托管", R.drawable.e_cloud, action = "cloud-workers"),
                ), model::navigate)
            }
            page == "devices" -> Records(value.objects("devices"), R.drawable.e_computer,
                header = { Row { if (value.objects("devices").isNotEmpty()) CatsCoSecondaryButton(onClick = { model.navigate("desktop-connect") }) { Text("连接电脑") }; CatsCoSecondaryButton(onClick = { model.navigate("device-audit") }) { Text("连接记录") }; CatsCoSecondaryButton(onClick = { model.navigate("cloud-workers") }) { Text("云托管") } } },
                empty = "还没有连接电脑", emptyDescription = "连接电脑后，Agent 可以在已授权的电脑上执行任务。", emptyAction = { CatsCoButton(onClick = { model.navigate("desktop-connect") }) { Text("连接电脑") } }, detail = { if (it.optBoolean("routable")) "在线 · 可以接收任务" else it.first("unavailableReason").ifBlank { "离线或暂不可用" } },
                trailing = { device -> ConfirmAction("解除连接", state.busy) { model.action { session ->
                    model.request(session, "/api/devices/${encodeSegment(device.first("deviceId", "device_id", "id"))}", method = "DELETE")
                    model.loadResource()
                } } })
            page.startsWith("select-device/") -> Records(value.objects("devices"), R.drawable.e_computer, empty = "没有可供此任务使用的电脑", detail = { if (it.optBoolean("routable")) "在线" else "不可用" },
                trailing = { device ->
                    val id = device.first("deviceId", "device_id")
                    CatsCoSecondaryButton(enabled = device.optBoolean("routable") && !state.busy && !value.isNull("revision"), onClick = { model.action { session ->
                        val topic = page.removePrefix("select-device/")
                        model.request(session, "/api/conversations/device-selection", mapOf("topic_id" to topic, "agent_id" to model.agentFor(topic)), "POST", JSONObject().put("device_id", id).put("expected_revision", value.get("revision")))
                        model.notice("电脑选择已保存。")
                        model.loadResource()
                    } }) { Text(if (value.optString("selected_device_id") == id) "已选择" else "选择") }
                })
            page == "desktop-connect" -> DesktopConnect(state, model)
            page == "device-audit" -> Records(value.objects("events", "records", "entries", "audit", "items"), R.drawable.e_computer, empty = "暂无连接记录", detail = { listOf(displayModuleStatus(it.first("description", "action")), displayModuleDate(it.first("created_at", "timestamp"))).filter(String::isNotBlank).joinToString(" · ") })
            page == "profile" -> ProfileEditor(state, model)
            page == "appearance" -> ModuleForm {
                ModuleSection("应用外观") {
                listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (mode, label) ->
                    Row(Modifier.fillMaxWidth().clickable { model.appearance(mode) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(state.appearance == mode, { model.appearance(mode) }); Text(label, fontSize = 16.sp)
                    }
                }
                }
            }
            page == "usage" -> UsageDetails(value, model)
            page == "commercial" -> CommercialDetails(state, model)
            page == "redeem" -> ModuleForm { InviteRedemption(state, model) }
            page == "orders" -> Records(value.objects("orders", "items"), R.drawable.e_file, empty = "暂无订单", detail = { listOf(displayOrderStatus(it.first("status", "state")), displayModuleDate(it.first("created_at")), orderAmount(it)).filter(String::isNotBlank).joinToString(" · ") })
            page == "feedback" -> FeedbackEditor(state, model)
            page == "server" -> ModuleForm {
                ModuleSection {
                val draft = rememberModuleDraft(model, state.page, mapOf("server" to (state.session?.server?.value ?: model.serverAddress)))
                val server = draft["server"]
                val serverError = serverAddressError(server)
                ModuleTextField(server, { draft.update("server", it) }, Modifier.fillMaxWidth(), label = { Text("HTTPS 服务器") }, singleLine = true,
                    errorMessage = serverError, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri))
                ModuleDraftStatus(draft)
                Text("更换服务器后需要重新登录。", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CatsCoButton(onClick = { model.configureServer(server) }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy && serverError == null) { Text("保存服务器") }
                }
            }
            page == "notifications" -> NotificationPreferences(state)
            page == "surfaces" -> ModuleForm {
                ModuleSection(spacing = 0.dp) {
                    val context = LocalContext.current
                    Text("点按组件打开最近任务，内容在打开 CatsCo 时更新。", Modifier.padding(bottom = 16.dp), fontSize = 15.sp, lineHeight = 24.sp)
                    ModuleActionRow("添加到桌面", R.drawable.e_add, onClick = {
                        val manager = android.appwidget.AppWidgetManager.getInstance(context)
                        if (manager.isRequestPinAppWidgetSupported) {
                            TaskWidgetProvider.update(context, state.workspace.conversations, force = true, syncedAt = state.lastSyncedAt)
                            manager.requestPinAppWidget(android.content.ComponentName(context, TaskWidgetProvider::class.java), null, null)
                        } else model.notice("请在手机桌面长按空白区域，添加 CatsCo 任务组件。")
                    })
                    HorizontalDivider(thickness = .5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    ModuleActionRow("消息通知", R.drawable.e_notifications, trailing = true) { model.navigate("notifications") }
                }
                if (state.lastSyncedAt > 0) Text("最近同步 · ${displayModuleDate(java.time.Instant.ofEpochMilli(state.lastSyncedAt).toString())}",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            page == "offline" -> ModuleForm {
                ModuleSection {
                    Text("缓存与草稿已加密。", fontSize = 15.sp, lineHeight = 24.sp)
                    ConfirmAction("清除缓存与草稿", state.busy, danger = false,
                        description = "将删除此账号在当前服务器下的本机缓存和未发送草稿，云端消息可重新同步。", action = model::clearOfflineData)
                }
            }
            page == "relay-key" -> RelayCredentials(state, model)
            page == "contacts" -> ContactsScreen(state, model)
            page == "people-search" -> SearchRecords(state, model, value.objects("users"), "搜索好友 ID 或用户名", R.drawable.e_account, trailing = { person ->
                CatsCoSecondaryButton(enabled = !state.busy, onClick = { model.action { session ->
                    model.request(session, "/api/friends/request", method = "POST", body = JSONObject().put("user_id", person.first("uid", "id").toLong()).put("message", ""))
                    model.notice("好友申请已发送。")
                } }) { Text("添加") }
            }) {}
            page == "message-search" -> SearchRecords(state, model, value.objects("results", "messages"), "搜索消息内容", R.drawable.e_chat, initialQuery = initialSearch, detail = { searchSnippetText(it.first("snippet", "content")) }, onClick = model::openSearchResult)
            page == "agents" -> AgentsScreen(state, model)
            page.startsWith("agent-access/") -> CollaboratingEmployee(state, model)
            page.startsWith("agent/") -> AgentEditor(state, model)
            page.startsWith("model/") -> ModelEditor(state, model)
            page == "service-agents" -> Records(value.objects("agents", "items"), R.drawable.e_sparkle, empty = "当前账号没有可用的定制 Agent", trailing = { agent ->
                val enabled = agent.optBoolean("enabled", agent.optBoolean("subscribed", true))
                CatsCoSecondaryButton(enabled = !state.busy, onClick = { model.action { session ->
                    model.request(session, "/api/service-agents/${encodeSegment(agent.first("slug", "id"))}/subscription", method = if (enabled) "DELETE" else "PUT", body = if (enabled) null else JSONObject())
                    model.loadResource()
                } }) { Text(if (enabled) "移出清单" else "添加") }
            })
            page == "cloud-workers" -> Records(value.objects("workers", "items", "agents"), R.drawable.e_cloud, empty = "还没有云托管员工", emptyDescription = "已开通的云端托管员工会显示在这里。", detail = { displayModuleStatus(it.first("display_state", "status", "state", "description")) })
        }
    }
}

@Composable
private fun ModuleForm(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp), content = content)
}

@Composable
private fun Field(label: String, value: String) {
    if (value.isNotBlank()) ModuleInfo(label, value)
}

private fun recordName(value: JSONObject): String = value.first("display_name", "displayName", "title", "name", "topic_name", "username", "deviceName", "deviceId", "slug", "order_no", "id").ifBlank { "未命名" }

@Composable
private fun Records(
    records: List<JSONObject>,
    icon: Int,
    empty: String = "暂无内容",
    header: (@Composable () -> Unit)? = null,
    detail: (JSONObject) -> String = { displayModuleStatus(it.first("description", "summary", "role", "version", "status")) },
    trailing: (@Composable (JSONObject) -> Unit)? = null,
    onClick: ((JSONObject) -> Unit)? = null,
    emptyDescription: String = "",
    emptyAction: @Composable (() -> Unit)? = null,
    showEmpty: Boolean = true,
    highlight: String = "",
    title: (JSONObject) -> String = ::recordName,
    bottomInset: androidx.compose.ui.unit.Dp = 16.dp,
    leading: (@Composable (JSONObject) -> Unit)? = null,
    list: LazyListState = rememberLazyListState(),
) {
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottomInset)) {
        header?.let { content -> item { Column(Modifier.padding(bottom = 12.dp)) { content() } } }
        if (records.isEmpty() && showEmpty) item {
            ModuleSection { ModuleEmpty(empty, emptyDescription, emptyAction, icon = icon) }
        }
        itemsIndexed(records) { index, record ->
            val shape = RoundedCornerShape(
                topStart = if (index == 0) 24.dp else 0.dp,
                topEnd = if (index == 0) 24.dp else 0.dp,
                bottomStart = if (index == records.lastIndex) 24.dp else 0.dp,
                bottomEnd = if (index == records.lastIndex) 24.dp else 0.dp,
            )
            Surface(shape = shape, color = MaterialTheme.colorScheme.surface) {
                Column {
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                        .then(if (onClick != null) Modifier.clickable { onClick(record) } else Modifier)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (leading != null) leading(record)
                        else Icon(painterResource(icon), null, Modifier.size(CatsCoMobileSpec.actionIconSize), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(title(record), fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            detail(record).takeIf(String::isNotBlank)?.let {
                                Text(highlightedSnippet(it, highlight, MaterialTheme.colorScheme.onSurface), fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        trailing?.invoke(record)
                    }
                    if (index < records.lastIndex) HorizontalDivider(Modifier.padding(start = if (leading == null) 53.dp else 70.dp, end = 16.dp), thickness = .5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f))
                }
            }
        }
    }
}

@Composable
private fun SearchRecords(state: WorkspaceState, model: WorkspaceViewModel, records: List<JSONObject>, placeholder: String, icon: Int,
    initialQuery: String = "",
    extraHeader: (@Composable () -> Unit)? = null,
    title: (JSONObject) -> String = ::recordName,
    detail: (JSONObject) -> String = { it.first("description", "summary", "role") }, trailing: (@Composable (JSONObject) -> Unit)? = null,
    floatingSearch: Boolean = false, onClick: (JSONObject) -> Unit) {
    var query by rememberSaveable(state.session?.userId, state.page) { mutableStateOf(model.moduleQuery(state.page, initialQuery)) }
    LaunchedEffect(query, state.page) {
        model.saveModuleQuery(state.page, query)
        delay(350)
        model.loadResource(query)
    }
    val backdrop = dev.chrisbanes.haze.rememberHazeState()
    Box(Modifier.fillMaxSize().then(if (floatingSearch) Modifier.imePadding() else Modifier)) {
        Box(Modifier.fillMaxSize().then(if (floatingSearch) Modifier.hazeSource(backdrop) else Modifier)) {
            Records(records, icon,
                header = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        extraHeader?.invoke()
                        if (floatingSearch && state.resource.loading) ModuleLoading()
                        if (floatingSearch) CatsCoSidebarSearchField(query, { query = it }, backdrop, placeholder = placeholder, glass = false)
                        else ModuleTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text(placeholder) }, singleLine = true)
                        state.resource.error?.let { ModuleError(it, state.resource.errorStatus, { model.loadResource(query) }, model::back) }
                    }
                },
                detail = detail, trailing = trailing, onClick = onClick,
                empty = when {
                    query.isNotBlank() -> "没有找到相关内容"
                    floatingSearch -> "还没有技能"
                    else -> "输入关键词开始搜索"
                },
                showEmpty = !state.resource.loading && state.resource.error == null,
                highlight = query,
                title = title,
                bottomInset = 16.dp,
            )
        }
    }
}

@Composable
private fun ConfirmAction(label: String, busy: Boolean, danger: Boolean = true, icon: Int = R.drawable.e_delete,
    description: String? = null, action: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    ModuleActionRow(label, icon, enabled = !busy, danger = danger) { confirming = true }
    if (confirming) CatsCoAlertDialog(onDismissRequest = { confirming = false }, title = { Text("$label？") },
        text = description?.let { message -> ({ Text(message) }) },
        confirmButton = { CatsCoButton(onClick = { confirming = false; action() }) { Text("确认") } },
        dismissButton = { CatsCoSecondaryButton(onClick = { confirming = false }) { Text("取消") } })
}

@Composable
private fun ProfileEditor(state: WorkspaceState, model: WorkspaceViewModel) {
    val value = state.resource.value
    val draft = rememberModuleDraft(model, state.page, mapOf("name" to value.first("display_name", "name").ifBlank { state.session?.name.orEmpty() }))
    val name = draft["name"]
    ModuleForm {
        ModuleSection {
            ModuleTextField(name, { draft.update("name", it) }, Modifier.fillMaxWidth(), label = { Text("显示名称") }, singleLine = true,
                errorMessage = requiredFieldError(name, "显示名称"))
            ModuleDraftStatus(draft)
            CatsCoButton(onClick = {
                val sent = draft.snapshot()
                model.saveProfile(name, value.optString("avatar_url")) { draft.accept(sent) }
            }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy && !state.resource.loading && state.resource.error == null && name.isNotBlank() && draft.dirty) { Text("保存资料") }
        }
        ModuleSection("账号信息", spacing = 0.dp) {
            ModuleInfoRows(listOf("用户名" to value.first("username"), "邮箱" to value.first("email"),
                "用户 ID" to state.session?.userId.orEmpty()))
        }
    }
}

@Composable
private fun FeedbackEditor(state: WorkspaceState, model: WorkspaceViewModel) {
    val draft = rememberModuleDraft(model, state.page, mapOf("description" to ""))
    val description = draft["description"]
    ModuleForm {
        ModuleSection {
            ModuleTextField(description, { draft.update("description", it) }, Modifier.fillMaxWidth().heightIn(min = 180.dp),
                label = { Text("问题或建议") }, placeholder = { Text("发生了什么？你希望怎样改进？") }, minLines = 5, errorMessage = requiredFieldError(description, "问题描述"))
            ModuleDraftStatus(draft)
            CatsCoButton(onClick = {
                model.action { session ->
                    model.request(session, "/api/feedback", method = "POST", body = JSONObject().put("description", description).put("category", "suggestion").put("user_agent", "CatsCo Android ${BuildConfig.VERSION_NAME}"))
                    if (draft["description"] == description) draft.update("description", "")
                    model.notice("反馈已发送，感谢你的建议。")
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = description.isNotBlank() && !state.busy) { Text("发送反馈") }
        }
    }
}

@Composable
private fun NotificationPreferences(state: WorkspaceState) {
    val context = LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var allowed by remember { mutableStateOf(androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var requested by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    DisposableEffect(lifecycle, context) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) allowed = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    ModuleForm {
        ModuleSection("系统通知") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("系统通知", fontSize = 16.sp)
                    Text("允许 CatsCo 发送通知", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = allowed, onCheckedChange = { enabled ->
                    if (enabled && !allowed && Build.VERSION.SDK_INT >= 33 && !requested) {
                        requested = true
                        permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }, colors = SwitchDefaults.colors(checkedTrackColor = Mint, checkedThumbColor = Color.White,
                    uncheckedThumbColor = Color.White, uncheckedBorderColor = Color.Transparent))
            }
        }
        ModuleSection("任务更新") {
            Text("任务状态在打开 CatsCo 后同步。后台任务完成提醒需要推送服务支持。", fontSize = 14.sp,
                lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CommercialDetails(state: WorkspaceState, model: WorkspaceViewModel) {
    val value = state.resource.value
    val summary = value.optJSONObject("summary") ?: value
    val entitlements = summary.objects("entitlements").ifEmpty { value.objects("entitlements", "packages", "quotas", "grants") }
    val usage = value.optJSONObject("model_usage")?.optJSONObject("summary")
    val usageModel = usage?.first("model").orEmpty()
    val matched = entitlements.indexOfFirst { usageModel.isNotBlank() && it.first("model") == usageModel }
    ModuleForm {
        val planName = summary.first("plan_name", "name")
        if (planName.isNotBlank() || summary.first("credit_balance", "credits", "balance").isNotBlank()) ModuleSection("账户概览") {
            Field("当前套餐", planName)
            Field(if (summary.has("credit_balance") || summary.has("credits")) "Credit 余额" else "账户余额", summary.first("credit_balance", "credits", "balance"))
            Field("到期时间", displayModuleDate(summary.first("expires_at", "expire_at")))
        }
        if (usage != null && matched < 0) ModelUsageCard(usage, "共享模型服务")
        value.first("model_usage_error").takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp) }
        if (entitlements.isEmpty() && usage == null) ModuleSection {
            ModuleEmpty("暂无已开通的权益", "可以在网页版查看当前可开通的套餐。")
        }
        entitlements.forEachIndexed { index, entitlement ->
            if (index == matched && usage != null) {
                ModelUsageCard(usage, entitlement.first("plan_name", "name").ifBlank { "共享模型服务" }, listOf(
                    "到期时间" to if (entitlement.has("expires_at") && entitlement.isNull("expires_at")) "长期有效" else displayModuleDate(entitlement.first("expires_at")),
                    "权益状态" to displayModuleStatus(entitlement.first("state", "status")),
                ))
            } else ModuleSection {
                val plan = summary.objects("plans").find {
                    entitlement.optLong("plan_id") > 0 && it.optLong("id") == entitlement.optLong("plan_id")
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(entitlement.first("plan_name", "name", "model").ifBlank { plan?.first("name").orEmpty().ifBlank { "套餐权益" } },
                        Modifier.weight(1f), fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold)
                    Text(displayModuleStatus(entitlement.first("state", "status")), fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Field("模型", entitlement.first("model"))
                Field("剩余额度", entitlement.first("remaining", "balance"))
                Field("到期时间", if (entitlement.has("expires_at") && entitlement.isNull("expires_at")) "长期有效" else displayModuleDate(entitlement.first("expires_at")))
            }
        }
        SettingsGroup("管理", listOf(SettingsEntry("兑换邀请码", R.drawable.e_key, action = "redeem"),
            SettingsEntry("订单记录", R.drawable.e_file, action = "orders"),
            SettingsEntry("在网页版管理套餐", R.drawable.e_external, action = "web"))) { action ->
            if (action == "web") model.state.value.session?.server?.value?.let { model.openUrl(it + "/?open=relay") }
            else model.navigate(action)
        }
    }
}

@Composable
private fun UsageDetails(value: JSONObject, model: WorkspaceViewModel) {
    val summary = value.optJSONObject("summary")
    ModuleForm {
        if (summary == null) ModuleSection {
            ModuleEmpty("尚未配置模型额度", "可在套餐与权益中查看当前账号的可用服务。", icon = R.drawable.e_chart)
        } else ModelUsageCard(summary)
        SettingsGroup("账户", listOf(SettingsEntry("套餐与权益", R.drawable.e_sparkle, action = "commercial")), model::navigate)
    }
}

@Composable
private fun RelayCredentials(state: WorkspaceState, model: WorkspaceViewModel) {
    val value = state.resource.value
    val credential = value.optJSONObject("credential")?.optJSONObject("key")
    var secret by remember(state.session?.userId, state.session?.server) { mutableStateOf("") }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) secret = "" }
        owner.lifecycle.addObserver(observer)
        onDispose { secret = ""; owner.lifecycle.removeObserver(observer) }
    }
    fun change(method: String, suffix: String) = model.action { session ->
        val result = model.request(session, "/api/relay/key$suffix", method = method, body = if (method == "DELETE") null else JSONObject())
        secret = result.first("plain_key", "api_key", "plaintext_key", "token", "key_value").ifBlank { result.optJSONObject("key")?.first("key", "value", "plain_key", "api_key", "token").orEmpty() }
            .ifBlank { (result.opt("key") as? String).orEmpty() }
        model.loadResource()
    }
    ModuleForm {
        ModuleSection("模型服务", spacing = 0.dp, contentPadding = PaddingValues(0.dp)) {
            CredentialValue("服务地址", value.first("base_url"))
            HorizontalDivider(thickness = .5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            CredentialValue("访问凭证", secret.ifBlank { credential?.first("prefix").orEmpty() },
                action = if (credential != null && value.optBoolean("self_service_enabled")) ({
                    IconButton(onClick = { if (secret.isNotBlank()) secret = "" else change("POST", "/reveal") }, enabled = !state.busy) {
                        Icon(painterResource(if (secret.isNotBlank()) R.drawable.message_eye_off else R.drawable.message_eye),
                            if (secret.isNotBlank()) "隐藏访问凭证" else "查看访问凭证", Modifier.size(23.dp))
                    }
                }) else null)
            HorizontalDivider(thickness = .5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("状态", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(displayModuleStatus(credential?.first("state").orEmpty()).ifBlank { "尚未创建" }, fontSize = 14.sp)
            }
            if (value.optBoolean("self_service_enabled")) {
                if (credential == null) CatsCoButton(onClick = { change("POST", "") }, enabled = !state.busy) { Text("创建访问凭证") }
            }
        }
        if (credential != null && value.optBoolean("self_service_enabled")) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          ModuleSection("凭证管理", spacing = 0.dp) {
            ConfirmAction("更换访问凭证", state.busy, danger = false, icon = R.drawable.e_refresh) { change("POST", "/rotate") }
            HorizontalDivider(thickness = .5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            ConfirmAction("撤销访问凭证", state.busy) { change("DELETE", "") }
          }
          Text("更换或撤销后，使用原凭证的客户端需要更新配置。", Modifier.padding(horizontal = 4.dp),
              fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DesktopConnect(state: WorkspaceState, model: WorkspaceViewModel) {
    var connection by remember { mutableStateOf(JSONObject()) }
    var status by remember { mutableStateOf("") }
    LaunchedEffect(connection.optString("code")) {
        val code = connection.optString("code")
        if (code.isNotBlank()) repeat(60) {
            delay(3000)
            val session = state.session ?: return@LaunchedEffect
            try {
                val result = model.request(session, "/api/desktop-connect/status", mapOf("code" to code))
                status = result.first("status", "state")
                if (status in setOf("connected", "approved", "expired", "cancelled")) return@LaunchedEffect
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { status = error.message.orEmpty(); return@LaunchedEffect }
        }
    }
    ModuleForm {
        ModuleSection("连接电脑") {
        Text("在电脑上打开 CatsCo Runtime，输入连接码完成连接。", fontSize = 15.sp)
        Field("连接码", connection.first("code")); Field("状态", displayModuleStatus(status))
        CatsCoButton(onClick = { model.action { session -> connection = model.request(session, "/api/desktop-connect/session", method = "POST", body = JSONObject()); status = "等待电脑连接" } }, enabled = !state.busy) { Text("生成连接码") }
        }
    }
}

@Composable
private fun SkillHub(state: WorkspaceState, model: WorkspaceViewModel) {
    val owners = state.workspace.employees.filter { it.owner }
    val selected = skillTarget(state)
    var configuration by remember(selected) { mutableStateOf<JSONObject?>(null) }
    var error by remember(selected) { mutableStateOf<String?>(null) }
    var errorStatus by remember(selected) { mutableStateOf<Int?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var catalogueScope by rememberSaveable(selected) { mutableStateOf("all") }
    LaunchedEffect(selected, attempt) {
        if (selected.isBlank()) return@LaunchedEffect
        configuration = null
        error = null
        errorStatus = null
        try {
            val session = state.session ?: return@LaunchedEffect
            configuration = model.request(session, "/api/bots/definition/skills", mapOf("uid" to selected))
        } catch (canceled: kotlinx.coroutines.CancellationException) { throw canceled }
        catch (failure: Exception) {
            errorStatus = (failure as? cc.catsco.android.data.ApiFailure)?.status
            error = if (errorStatus == 403) "此 Agent 的技能配置暂不可查看。" else "技能配置读取失败。"
        }
    }
    val catalogue = state.resource.value.objects("skills", "items")
    val configuredIds = configuration?.objects("skills")?.map { it.first("skillId", "skill_id") }.orEmpty().toSet()
    val visibleSkills = if (catalogueScope == "added") catalogue.filter { it.first("skillId", "skill_id", "id") in configuredIds } else catalogue
    val readingAddedSkills = catalogueScope == "added" && configuration == null && error == null
    var query by rememberSaveable(state.session?.userId, state.session?.server) { mutableStateOf(model.moduleQuery("skills")) }
    LaunchedEffect(query) {
        model.saveModuleQuery("skills", query)
        delay(350)
        model.loadResource(query)
    }
    val backdrop = dev.chrisbanes.haze.rememberHazeState()
    val list = rememberLazyListState()
    LaunchedEffect(query, selected, catalogueScope) { list.scrollToItem(0) }
    Column(Modifier.fillMaxSize().imePadding()) {
        LibraryEmployeeSelector(owners.find { it.id == selected }?.name ?: "暂无员工", owners.map { it.id to it.name },
            selected, model::selectSkillTarget, "skill-agent")
        CatsCoSidebarSearchField(query, { query = it }, backdrop,
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("skill-search"), "搜索技能", glass = false)
        CatsCoSegmentedControl(listOf("all" to "全部", "added" to "已添加"), catalogueScope,
            { catalogueScope = it }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("skill-scope"),
            "skill-filter", enabled = configuration != null)
        if (owners.isEmpty()) Text("创建自己的员工后，可以为它添加技能。", Modifier.padding(horizontal = 20.dp),
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { message ->
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (errorStatus != 403) CatsCoSecondaryButton(onClick = { attempt++ }) { Text("重新读取") }
            }
        }
        state.resource.error?.let {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("技能暂时无法加载", Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.resource.errorStatus in setOf(403, 404)) CatsCoSecondaryButton(onClick = model::back) { Text("返回上一页") }
                else CatsCoSecondaryButton(onClick = { model.loadResource(query) }, enabled = !state.resource.loading) { Text("重试") }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().hazeSource(backdrop).testTag("skill-list"), state = list,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
            if ((state.resource.loading && catalogue.isEmpty()) || readingAddedSkills) item { ModuleLoading() }
            if (visibleSkills.isEmpty() && !state.resource.loading && state.resource.error == null &&
                (catalogueScope != "added" || configuration != null)) item {
                ModuleEmpty(when {
                    query.isNotBlank() -> "没有找到匹配的技能"
                    catalogueScope == "added" -> "还没有添加技能"
                    else -> "还没有技能"
                })
            }
            items(visibleSkills) { skill ->
                val status = when {
                    selected.isBlank() -> ""
                    error != null -> "配置状态暂不可读取"
                    configuration == null -> "正在读取配置…"
                    else -> skillConfigurationStatus(skill, configuration!!.objects("skills"))
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).testTag("skill-row-${skill.first("skillId", "skill_id", "id")}")
                    .clickable { model.navigate("skill/${skill.first("skillId", "skill_id", "id")}") }.padding(start = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    LibraryItemText(skillCatalogueTitle(skill), skillCatalogueMetadata(skill, status).replace("\n", " · "),
                        Modifier.weight(1f).padding(vertical = 14.dp), detailMaxLines = 2, highlight = query)
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.e_expand), null,
                            Modifier.size(CatsCoMobileSpec.actionIconSize).graphicsLayer { rotationZ = -90f },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(Modifier.padding(start = 4.dp), thickness = .5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))
            }
        }
    }
}

private fun skillTarget(state: WorkspaceState): String {
    val owners = state.workspace.employees.filter { it.owner }
    return state.skillTargetId.takeIf { id -> owners.any { it.id == id } }
        ?: state.employeeId.takeIf { id -> owners.any { it.id == id } }
        ?: owners.firstOrNull()?.id.orEmpty()
}

@Composable
private fun SkillDetail(state: WorkspaceState, model: WorkspaceViewModel) {
    val value = state.resource.value.optJSONObject("skill") ?: state.resource.value
    val id = state.page.removePrefix("skill/")
    val version = value.optJSONObject("version") ?: value
    val number = version.first("latestVersion", "latest_version", "version")
    val hash = version.first("contentHash", "content_hash", "sha256")
    val stableVersion = number.isNotBlank() && hash.length == 64 && hash.all { it in "0123456789abcdefABCDEF" }
    val owners = state.workspace.employees.filter { it.owner }
    val selected = skillTarget(state)
    var installed by remember(id, selected) { mutableStateOf<JSONObject?>(null) }
    var installError by remember(id, selected) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(id, selected, attempt) {
        installed = null; installError = null
        if (selected.isNotBlank()) {
            try {
                val session = state.session ?: return@LaunchedEffect
                installed = model.request(session, "/api/bots/definition/skills", mapOf("uid" to selected))
            } catch (canceled: kotlinx.coroutines.CancellationException) { throw canceled }
            catch (failure: Exception) { installError = failure.message ?: "无法读取此 Agent 的技能配置。" }
        }
    }
    val configured = installed?.objects("skills")?.any { it.first("skillId") == id && it.first("version") == number && it.first("contentHash").equals(hash, true) } == true
    ModuleForm {
        ModuleSection {
            Text(recordName(value), fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
            Field("版本", number)
            Field("发布者", skillPublisher(value))
        }
        if (owners.isEmpty()) ModuleSection("添加到 Agent") {
            ModuleEmpty("暂无可管理的 Agent", "创建自己的 Agent 后，可以为它添加技能。",
                action = { CatsCoButton(onClick = { model.navigate("agents") }) { Text("管理员工") } })
        } else ModuleSection("添加到 Agent") {
            ModuleChoice("目标 Agent", selected, owners.map { it.id to it.name }, model::selectSkillTarget, enabled = !state.busy)
            Text(when {
                installError != null -> installError.orEmpty()
                installed == null -> "正在读取技能配置…"
                configured -> "此 Agent 已配置当前版本"
                !stableVersion -> "此技能暂未提供可添加的稳定版本"
                else -> "添加后由 Agent 同步此版本"
            }, fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (installError != null) CatsCoSecondaryButton(onClick = { attempt++ }) { Text("重新读取") }
            CatsCoButton(onClick = { model.action { session ->
                val target = selected
                val config = model.request(session, "/api/bots/definition/skills", mapOf("uid" to target))
                val refs = config.objects("skills").filter { it.first("skillId") != id }.map(::skillReference).toMutableList()
                refs.add(JSONObject().put("source", "skillhub").put("skillId", id).put("version", number).put("contentHash", hash.lowercase()))
                model.request(session, "/api/bots/definition/skills", mapOf("uid" to target), "PATCH", JSONObject().put("revision", config.get("revision")).put("skills", JSONArray(refs)))
                if (selected == target) installed = JSONObject(config.toString()).put("skills", JSONArray(refs))
                model.notice("技能配置已添加，等待 Agent 同步。")
            } }, enabled = selected.isNotBlank() && !configured && installed != null && stableVersion && !state.busy && !state.resource.loading) {
                Text(if (configured) "已配置此版本" else "添加到 Agent")
            }
        }
        ModuleSection("使用说明") { MarkdownContent(value.first("readme", "content", "description")) }
    }
}

@Composable
internal fun ContactsAddAction(state: WorkspaceState, model: WorkspaceViewModel) {
    var creating by rememberSaveable { mutableStateOf(false) }
    CatsCoHeaderAddMenu("添加联系人或群组", enabled = !state.busy, options = listOf(
        "添加好友" to { model.navigate("people-search") },
        "创建群组" to { creating = true },
    ))
    if (creating) CreateGroupDialog(state.resource.value.objects("friends"), state.busy, model) { creating = false }
}

@Composable
private fun CreateGroupDialog(friends: List<JSONObject>, busy: Boolean, model: WorkspaceViewModel, onClose: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var selectedIds by rememberSaveable { mutableStateOf(longArrayOf()) }
    CatsCoAlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)).padding(vertical = 12.dp),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text("创建群组") },
        text = {
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            val lightSystemBars = MaterialTheme.colorScheme.background.luminance() > .5f
            SideEffect {
                window?.let {
                    WindowCompat.getInsetsController(it, it.decorView).apply {
                        isAppearanceLightStatusBars = lightSystemBars
                        isAppearanceLightNavigationBars = lightSystemBars
                    }
                }
            }
            Column(Modifier.heightIn(max = 400.dp)) {
                ModuleTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("群组名称") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    enabled = !busy, errorMessage = requiredFieldError(name, "群组名称"))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    friends.forEach { friend ->
                        val id = friend.first("uid", "id").toLongOrNull() ?: return@forEach
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(id in selectedIds, { checked ->
                                selectedIds = if (checked) selectedIds + id else selectedIds.filterNot { it == id }.toLongArray()
                            }, enabled = !busy)
                            Text(recordName(friend))
                        }
                    }
                }
            }
        },
        confirmButton = {
            CatsCoSecondaryButton(enabled = name.isNotBlank() && selectedIds.isNotEmpty() && !busy, onClick = {
                model.action { session ->
                    val body = JSONObject().put("name", name.trim()).put("member_ids", JSONArray(selectedIds.toList()))
                    model.request(session, "/api/groups/create", method = "POST", body = body)
                    onClose()
                    model.loadResource()
                    model.refresh()
                }
            }) { Text("创建") }
        },
        dismissButton = { CatsCoSecondaryButton(onClick = onClose, enabled = !busy) { Text("取消") } },
    )
}

@Composable
private fun ContactsScreen(state: WorkspaceState, model: WorkspaceViewModel) {
    val value = state.resource.value
    var query by rememberSaveable(state.session?.userId) { mutableStateOf("") }
    val friends = value.objects("friends")
    val pendingRequests = value.objects("requests")
    val contacts = friends.map { JSONObject(it.toString()).put("_contact_kind", "friend") } +
        value.objects("groups").map { JSONObject(it.toString()).put("_contact_kind", "group") }
    val matching = contacts.filter { contact ->
        query.isBlank() || listOf(recordName(contact), contact.first("username", "description", "uid", "id"))
            .any { text -> text.contains(query.trim(), ignoreCase = true) }
    }
    val backdrop = dev.chrisbanes.haze.rememberHazeState()
    val list = rememberLazyListState()
    Column(Modifier.fillMaxSize().imePadding()) {
        CatsCoSidebarSearchField(query, { query = it; list.requestScrollToItem(0) }, backdrop,
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp),
            placeholder = "搜索联系人或群组", glass = false)
        Box(Modifier.weight(1f)) {
            Records(
                matching, R.drawable.e_people,
                empty = if (query.isBlank()) "还没有联系人" else "没有找到相关联系人或群组",
                emptyDescription = if (query.isBlank()) "添加好友或创建群组后，可以在这里开始会话。" else "试试其他名称或用户名。",
                bottomInset = 16.dp,
                list = list,
                leading = { record ->
                    CatsCoAccountAvatar(
                        state.session?.copy(avatarUrl = record.first("avatar_url", "avatarUrl")),
                        model.accountImages, Modifier.size(40.dp), shape = CatsCoMobileSpec.fieldShape,
                        fallbackIcon = if (record.optString("_contact_kind") == "group") R.drawable.e_people else R.drawable.e_account,
                    )
                },
                detail = { record ->
                    if (record.optString("_contact_kind") == "group") "群组"
                    else record.first("username").takeUnless { it == recordName(record) }.orEmpty()
                },
                header = if (pendingRequests.isNotEmpty()) ({
                    Column {
                        pendingRequests.forEach { request ->
                            Text(request.first("from_display_name", "from_username", "message"))
                            Row {
                                listOf(true to "接受", false to "拒绝").forEach { (accept, label) ->
                                    CatsCoSecondaryButton(enabled = !state.busy, onClick = {
                                        model.action { session ->
                                            model.request(session, if (accept) "/api/friends/accept" else "/api/friends/reject",
                                                method = "POST", body = JSONObject().put("user_id", request.opt("from_user_id")))
                                            model.loadResource()
                                            model.refresh()
                                        }
                                    }) { Text(label) }
                                }
                            }
                        }
                    }
                }) else null,
                onClick = { record ->
                    val group = record.optString("_contact_kind") == "group"
                    val topic = if (group) "grp_${record.first("id", "group_id")}" else {
                        val ids = listOf(state.session?.userId?.toLongOrNull() ?: 0, record.first("uid", "id").toLongOrNull() ?: 0).sorted()
                        "p2p_${ids[0]}_${ids[1]}"
                    }
                    model.openConversation(topic, recordName(record))
                },
            )
        }
    }
}

@Composable
private fun AgentsScreen(state: WorkspaceState, model: WorkspaceViewModel) {
    val roster = state.resource.value.objects("bots", "agents").filter { it.first("id", "uid", "user_id").isNotBlank() }
    Records(roster, R.drawable.e_agent, empty = "还没有员工",
        showEmpty = !state.resource.loading && state.resource.error == null,
        detail = { if (employee(it).owner) "我的员工" else "协作员工" },
        leading = { record ->
            CatsCoAccountAvatar(state.session?.copy(avatarUrl = record.first("avatar_url")), model.accountImages,
                Modifier.size(40.dp), shape = CatsCoMobileSpec.fieldShape, fallbackIcon = R.drawable.e_agent)
        },
        onClick = {
            val selected = employee(it)
            model.navigate(if (selected.owner) "agent/${selected.id}" else "agent-access/${selected.id}")
        },
    )
}

@Composable
internal fun EmployeeAddAction(busy: Boolean, model: WorkspaceViewModel) {
    var form by rememberSaveable { mutableStateOf<EmployeeForm?>(null) }
    CatsCoHeaderAddMenu("添加员工", enabled = !busy, options = EmployeeForm.entries.map { entry ->
        entry.title to { form = entry }
    })
    form?.let { EmployeeAddDialog(it, busy, model) { form = null } }
}

private enum class EmployeeForm(val title: String, val submitLabel: String) {
    Create("创建员工", "创建"),
    Invite("使用邀请码", "加入"),
}

@Composable
private fun EmployeeAddDialog(form: EmployeeForm, busy: Boolean, model: WorkspaceViewModel, onClose: () -> Unit) {
    val creating = form == EmployeeForm.Create
    var input by rememberSaveable(form) { mutableStateOf("") }
    var name by rememberSaveable(form) { mutableStateOf("") }
    var failure by remember(form) { mutableStateOf<String?>(null) }
    val inputLabel = if (creating) "用户名" else "员工邀请码"
    CatsCoAlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(form.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ModuleTextField(input, { input = it; failure = null }, Modifier.fillMaxWidth(), label = { Text(inputLabel) },
                    singleLine = true, enabled = !busy, errorMessage = requiredFieldError(input, inputLabel))
                if (creating) ModuleTextField(name, { name = it; failure = null }, Modifier.fillMaxWidth(), label = { Text("显示名称") },
                    singleLine = true, enabled = !busy, errorMessage = requiredFieldError(name, "显示名称"))
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            CatsCoButton(enabled = !busy && input.isNotBlank() && (!creating || name.isNotBlank()), onClick = {
                failure = null
                model.action(onFailure = { failure = it }) { session ->
                    if (creating) model.request(session, "/api/bots", method = "POST", body = JSONObject()
                        .put("username", input.trim()).put("display_name", name.trim()).put("role", "general").put("description", ""))
                    else model.request(session, "/api/bots/invite/redeem", method = "POST", body = JSONObject().put("code", input.trim()))
                    onClose()
                    model.loadResource()
                    model.refresh()
                    model.notice(if (creating) "员工已创建。" else "员工已添加。")
                }
            }) { Text(form.submitLabel) }
        },
        dismissButton = { CatsCoSecondaryButton(onClick = onClose, enabled = !busy) { Text("取消") } },
    )
}

@Composable
private fun CollaboratingEmployee(state: WorkspaceState, model: WorkspaceViewModel) {
    val record = employee(state.resource.value)
    ModuleForm {
        EmployeeIdentity(state, model, record, subtitle = "协作员工")
        SettingsGroup("能力与工作空间", listOf(
            SettingsEntry("云端文件", R.drawable.e_cloud, action = "files/${record.id}"),
            SettingsEntry("应用", R.drawable.e_apps, action = "agent-apps/${record.id}"),
            SettingsEntry("知识库", R.drawable.e_knowledge, action = "agent-knowledge/${record.id}"),
            SettingsEntry("系统提示词", R.drawable.e_file, action = "agent-prompt/${record.id}"),
        ), model::navigate)
    }
}

@Composable
private fun EmployeeIdentity(state: WorkspaceState, model: WorkspaceViewModel, employee: Employee?, subtitle: String? = null) {
    ModuleSection {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CatsCoAccountAvatar(state.session?.copy(avatarUrl = employee?.avatarUrl.orEmpty()), model.accountImages,
                Modifier.size(56.dp), shape = CatsCoMobileSpec.fieldShape, fallbackIcon = R.drawable.e_agent)
            Column(Modifier.weight(1f)) {
                Text(employee?.name.orEmpty(), fontSize = 19.sp, fontWeight = FontWeight.Medium)
                val detail = subtitle ?: employee?.let { if (it.online) "在线" else "离线" }
                detail?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun AgentSkillsScreen(state: WorkspaceState, model: WorkspaceViewModel) {
    val uid = state.page.removePrefix("agent-skills/")
    val config = state.resource.value.optJSONObject("definition") ?: state.resource.value
    val references = config.objects("skills")
    val records = references.map { JSONObject(it.toString()).put("name", it.first("displayName", "skillId")) }
    Records(
        records, R.drawable.e_sparkle, empty = "此 Agent 还没有添加技能。",
        header = { CatsCoSecondaryButton(onClick = { model.navigate("skills") }) { Text("添加技能") } },
        onClick = { skill -> model.navigate("skill/${skill.first("skillId")}") },
        trailing = { skill ->
            ConfirmAction("移除", state.busy || config.isNull("revision")) {
                model.action { session ->
                    val remaining = references.filter { it.first("skillId") != skill.first("skillId") }.map(::skillReference)
                    model.request(session, "/api/bots/definition/skills", mapOf("uid" to uid), "PATCH", JSONObject().put("revision", config.get("revision")).put("skills", JSONArray(remaining)))
                    model.loadResource()
                    model.notice("技能已移除，等待员工同步。")
                }
            }
        },
    )
}

private fun skillReference(value: JSONObject): JSONObject = JSONObject()
    .put("source", value.opt("source")).put("skillId", value.opt("skillId")).put("version", value.opt("version"))
    .apply { if (!value.isNull("contentHash")) put("contentHash", value.get("contentHash")) }

@Composable
private fun AgentEditor(state: WorkspaceState, model: WorkspaceViewModel) {
    val uid = state.page.removePrefix("agent/")
    val definition = state.resource.value
    val employee = state.workspace.employees.find { it.id == uid }
    val profile = rememberModuleDraft(model, "${state.page}/profile", mapOf(
        "name" to employee?.name.orEmpty(), "description" to employee?.description.orEmpty(),
    ))
    val prompt = rememberModuleDraft(model, "${state.page}/prompt", mapOf(
        "content" to definition.optJSONObject("prompt")?.optString("customSystemPrompt").orEmpty(),
    ))
    var editing by rememberSaveable(uid) { mutableStateOf(false) }
    var editingPrompt by rememberSaveable(uid) { mutableStateOf(false) }
    if (editing) CatsCoAlertDialog(onDismissRequest = { editing = false }, title = { Text("员工资料") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ModuleTextField(profile["name"], { profile.update("name", it) }, Modifier.fillMaxWidth(), label = { Text("显示名称") }, singleLine = true, enabled = !state.busy,
                errorMessage = requiredFieldError(profile["name"], "显示名称"))
            ModuleTextField(profile["description"], { profile.update("description", it) }, Modifier.fillMaxWidth(), label = { Text("描述") }, minLines = 2, enabled = !state.busy)
            ModuleDraftStatus(profile)
        }
    }, confirmButton = {
            CatsCoButton(enabled = profile["name"].isNotBlank() && profile.dirty && !state.busy, onClick = {
                val sent = profile.snapshot()
                model.action { session ->
                    model.request(session, "/api/bots", mapOf("uid" to uid), "PATCH", JSONObject().put("display_name", sent["name"]).put("description", sent["description"]))
                    profile.accept(sent)
                    editing = false
                    model.refresh(); model.notice("员工资料已保存。")
                }
            }) { Text("保存") }
    }, dismissButton = { CatsCoSecondaryButton(onClick = { editing = false }) { Text("取消") } })
    if (editingPrompt) CatsCoAlertDialog(onDismissRequest = { editingPrompt = false }, title = { Text("系统提示词") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ModuleTextField(prompt["content"], { prompt.update("content", it) }, Modifier.fillMaxWidth(), label = { Text("自定义系统提示词") }, minLines = 4, enabled = !state.busy)
            ModuleDraftStatus(prompt)
        }
    }, confirmButton = {
            CatsCoButton(enabled = !definition.isNull("revision") && (prompt.dirty || definition.optJSONObject("prompt")?.optString("selected") != "custom") && !state.busy, onClick = {
                val sent = prompt.snapshot()
                model.action { session ->
                    model.request(session, "/api/bots/definition/prompt", mapOf("uid" to uid), "PATCH", JSONObject().put("revision", definition.get("revision")).put("prompt", JSONObject().put("selected", "custom").put("customSystemPrompt", sent["content"])))
                    prompt.accept(sent)
                    editingPrompt = false
                    model.loadResource(); model.notice("提示词已保存，等待员工同步。")
                }
            }) { Text("保存") }
    }, dismissButton = { CatsCoSecondaryButton(onClick = { editingPrompt = false }) { Text("取消") } })
    ModuleForm {
        EmployeeIdentity(state, model, employee)
        SettingsGroup("模型与能力", listOf(
            SettingsEntry("模型配置", R.drawable.e_settings, action = "model/$uid"),
            SettingsEntry("技能管理", R.drawable.e_sparkle, action = "agent-skills/$uid"),
            SettingsEntry("应用", R.drawable.e_apps, action = "agent-apps/$uid"),
            SettingsEntry("知识库", R.drawable.e_knowledge, action = "agent-knowledge/$uid"),
            SettingsEntry("系统提示词", R.drawable.e_file, action = "prompt-editor"),
        )) { action -> if (action == "prompt-editor") editingPrompt = true else model.navigate(action) }
        SettingsGroup("资料", listOf(SettingsEntry("云端文件", R.drawable.e_cloud, action = "files/$uid")), model::navigate)
        ModuleSection("员工管理") {
            ModuleActionRow("编辑员工资料", R.drawable.e_edit) { editing = true }
            ConfirmAction("删除员工", state.busy) {
                model.action { session ->
                    model.request(session, "/api/bots", mapOf("uid" to uid), "DELETE")
                    model.refresh(); model.back()
                }
            }
        }
    }
}

@Composable
private fun ModelEditor(state: WorkspaceState, model: WorkspaceViewModel) {
    val uid = state.page.removePrefix("model/")
    val config = state.resource.value.optJSONObject("config") ?: state.resource.value
    val desired = config.optJSONObject("desired") ?: JSONObject()
    val applied = config.optJSONObject("applied")
    val custom = config.optJSONObject("custom") ?: JSONObject()
    val initialSelection = if (desired.optString("kind") == "custom") "custom" else desired.first("model_id", "kind").ifBlank { "local" }
    val draft = rememberModuleDraft(model, state.page, mapOf(
        "selected" to initialSelection, "effort" to desired.optString("reasoning_effort"),
        "protocol" to custom.optString("protocol", "anthropic"), "endpoint" to custom.optString("api_base"),
        "name" to custom.optString("model"), "context" to custom.optLong("context_window_tokens", 200000).toString(),
    ))
    val selected = draft["selected"]
    var apiKey by remember { mutableStateOf("") }
    var chooser by rememberSaveable { mutableStateOf(false) }
    var modelQuery by rememberSaveable { mutableStateOf("") }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) apiKey = "" }
        owner.lifecycle.addObserver(observer)
        onDispose { apiKey = ""; owner.lifecycle.removeObserver(observer) }
    }
    val options = listOf(JSONObject().put("id", "local").put("name", "员工本地模型")) + state.resource.value.objects("models") +
        if (config.optBoolean("custom_supported") || selected == "custom") listOf(JSONObject().put("id", "custom").put("name", "自备模型")) else emptyList()
    val selectedOption = options.find { it.first("id", "model_id") == selected }
    val effortValues = selectedOption?.optJSONArray("reasoning_efforts")?.let { array ->
        (0 until array.length()).map { array.optString(it) }.filter(String::isNotBlank)
    }.orEmpty()
    val efforts = if (selected == "custom") listOf("", "none", "minimal", "low", "medium", "high", "xhigh", "max", "disabled")
        else (listOf("") + effortValues + draft["effort"].takeIf(String::isNotBlank).orEmpty()).distinct()
    val validEndpoint = runCatching {
        val uri = java.net.URI(draft["endpoint"])
        uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null && uri.rawFragment == null
    }.getOrDefault(false)
    val validCustom = validEndpoint && draft["name"].isNotBlank() && (draft["context"].toLongOrNull() ?: 0) > 0 &&
        (custom.optBoolean("api_key_configured") || apiKey.isNotBlank())
    val managementEnabled = config.optBoolean("management_enabled", true)
    if (chooser) CatsCoAlertDialog(onDismissRequest = { chooser = false }, title = { Text("选择模型") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ModuleTextField(modelQuery, { modelQuery = it }, Modifier.fillMaxWidth(), placeholder = { Text("搜索模型") }, singleLine = true)
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                options.filter { recordName(it).contains(modelQuery, ignoreCase = true) }.forEach { option ->
                    val id = option.first("id", "model_id")
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable {
                        draft.update("selected", id)
                        draft.update("effort", option.optString("default_reasoning_effort"))
                        chooser = false
                    }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == id, onClick = null)
                        Text(recordName(option), Modifier.padding(start = 12.dp), fontSize = 16.sp)
                    }
                }
            }
        }
    }, confirmButton = { CatsCoSecondaryButton(onClick = { chooser = false }) { Text("取消") } })
    ModuleForm {
        ModuleSection("生效情况") {
            Field("当前生效模型", applied?.let {
                if (it.optString("kind") == "custom") it.first("model", "model_name").ifBlank { "自备模型" }
                else displayModuleStatus(it.first("model_id", "kind"))
            }.orEmpty().ifBlank { "暂未提供" })
            Field("配置状态", displayModuleStatus(config.first("status")).ifBlank { "暂未提供" })
            if (config.optString("status") == "failed") Text(
                modelApplyExplanation(config.first("last_error", "error", "apply_error")),
                fontSize = 14.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.error,
            )
            if (!managementEnabled) Text("此员工的模型管理暂不可用。", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ModuleSection("选择配置") {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = managementEnabled && !state.busy) { chooser = true },
                verticalAlignment = Alignment.CenterVertically) {
                Text("模型", Modifier.weight(1f), fontSize = 16.sp)
                Text(selectedOption?.let(::recordName) ?: selected, Modifier.widthIn(max = 220.dp), fontSize = 14.sp,
                    lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(painterResource(R.drawable.e_expand), null, Modifier.padding(start = 10.dp).size(CatsCoMobileSpec.actionIconSize).graphicsLayer { rotationZ = -90f },
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (managementEnabled && !state.busy) 1f else .38f))
            }
            if (efforts.size > 1 || selected == "custom") ModuleChoice("推理强度", draft["effort"], efforts.map { it to reasoningLabel(it) },
                { draft.update("effort", it) }, enabled = managementEnabled && !state.busy)
        }
        if (selected == "custom") ModuleSection("自备模型") {
            ModuleChoice("接口协议", draft["protocol"], listOf("anthropic" to "Anthropic Messages", "openai-chat-completions" to "OpenAI Chat Completions", "openai-responses" to "OpenAI Responses"), { draft.update("protocol", it) }, enabled = !state.busy)
            ModuleTextField(draft["endpoint"], { draft.update("endpoint", it) }, Modifier.fillMaxWidth(), label = { Text("HTTPS API 地址") }, singleLine = true, enabled = !state.busy,
                errorMessage = if (validEndpoint) null else "请填写有效的 HTTPS API 地址")
            ModuleTextField(draft["name"], { draft.update("name", it) }, Modifier.fillMaxWidth(), label = { Text("模型名称") }, singleLine = true, enabled = !state.busy,
                errorMessage = requiredFieldError(draft["name"], "模型名称"))
            ModuleTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), label = { Text(if (custom.optBoolean("api_key_configured")) "API Key（留空保留）" else "API Key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = !state.busy,
                errorMessage = if (custom.optBoolean("api_key_configured")) null else requiredFieldError(apiKey, "API Key"))
            ModuleTextField(draft["context"], { draft.update("context", it) }, Modifier.fillMaxWidth(), label = { Text("上下文 Token 数") }, singleLine = true, enabled = !state.busy,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                errorMessage = if ((draft["context"].toLongOrNull() ?: 0) > 0) null else "请输入大于 0 的整数")
        }
        ModuleDraftStatus(draft)
        CatsCoButton(modifier = Modifier.fillMaxWidth(), enabled = selected.isNotBlank() && (selected != "custom" || validCustom) &&
            !state.busy && !state.resource.loading && state.resource.error == null && managementEnabled, onClick = {
            val sent = draft.snapshot()
            val key = apiKey
            model.action { session ->
                val body = JSONObject().put("kind", when (sent["selected"]) { "local" -> "local"; "custom" -> "custom"; else -> "catalog" })
                    .put("model_id", sent["selected"]).put("reasoning_effort", sent["effort"])
                if (sent["selected"] == "custom") body.put("custom", JSONObject().put("protocol", sent["protocol"]).put("api_base", sent["endpoint"])
                    .put("model", sent["name"]).put("context_window_tokens", sent.getValue("context").toLong()).put("reasoning_effort", sent["effort"])
                    .apply { if (key.isNotBlank()) put("api_key", key) })
                model.request(session, "/api/bots/model-config", mapOf("uid" to uid), "PATCH", body)
                draft.accept(sent); apiKey = ""
                model.loadResource(); model.notice("配置已保存，等待员工应用。")
            }
        }) { Text("保存模型配置") }
    }
}
