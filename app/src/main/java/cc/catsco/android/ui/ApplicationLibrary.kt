package cc.catsco.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.catsco.android.R
import cc.catsco.android.ResourceState
import cc.catsco.android.WorkspaceState
import cc.catsco.android.WorkspaceViewModel
import cc.catsco.android.data.*
import cc.catsco.core.Employee
import dev.chrisbanes.haze.rememberHazeState
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@Composable
internal fun CatsCoLibraryTabs(skills: Boolean, onSelect: (Boolean) -> Unit) {
    CatsCoSegmentedControl(listOf("apps" to "应用", "skills" to "SkillHub"), if (skills) "skills" else "apps",
        { onSelect(it == "skills") }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), "library-tab")
}

@Composable
internal fun ApplicationsScreen(
    state: WorkspaceState,
    model: WorkspaceViewModel,
    outputAgent: String? = null,
    sourceTopic: String? = null,
) {
    val context = LocalContext.current
    val session = state.session ?: return
    val accountKey = "${session.server.value}/${session.userId}"
    val preferences = remember { context.getSharedPreferences("application-favorites", Context.MODE_PRIVATE) }
    var favorites by remember(accountKey) { mutableStateOf(preferences.getStringSet(accountKey, emptySet()).orEmpty().toSet()) }
    val toggleFavorite: (JSONObject) -> Unit = { app ->
        val key = applicationKey(app)
        favorites = if (key in favorites) favorites - key else favorites + key
        preferences.edit().putStringSet(accountKey, favorites).apply()
    }
    var opened by remember(accountKey, sourceTopic) { mutableStateOf<TaskAppLaunch?>(null) }
    var editing by remember(opened) { mutableStateOf(false) }
    var comment by remember(opened) { mutableStateOf("") }
    var feedbackError by remember(opened) { mutableStateOf("") }
    val clientId = remember(opened, comment) { java.util.UUID.randomUUID().toString() }
    DisposableEffect(opened) {
        val binding = opened
        onDispose { if (binding != null) model.releaseTaskApp(session, binding) }
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            ApplicationsPanel(
                resource = state.resource,
                agents = state.workspace.employees,
                favorites = favorites,
                initialAgent = outputAgent ?: state.page.takeIf { it.startsWith("agent-apps/") }?.removePrefix("agent-apps/").orEmpty(),
                accountKey = accountKey,
                busy = state.busy,
                onRetry = { model.loadResource() },
                onFavorite = toggleFavorite,
                onOpen = { app -> model.action { current ->
                    val body = JSONObject().put("app", app.first("id"))
                    sourceTopic?.let { body.put("topic_id", it) }
                    val result = model.request(current, "/api/artifacts/launch", method = "POST", body = body)
                    val url = result.first("launch_url", "launchUrl", "url")
                    check(url.isNotBlank()) { "应用暂时无法打开，请重试。" }
                    val binding = sourceTopic?.let { TaskAppLaunch.from(result, url, it, app) }
                    if (model.state.value.page == state.page) {
                        opened = binding
                        model.openUrl(url)
                    } else if (binding != null) model.releaseTaskApp(current, binding)
                } },
                onBrowser = { app ->
                    val url = applicationShareUrl(app, session.server.value)
                    if (url.isBlank()) model.notice("这个应用暂时没有可打开的地址。") else model.openUrl(url)
                },
                onShare = { app ->
                    val url = applicationShareUrl(app, session.server.value)
                    if (url.isBlank()) model.notice("这个应用暂时没有可分享的地址。") else {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("应用分享链接", url))
                    }
                },
                outputContext = outputAgent != null,
                sourceTopic = sourceTopic,
                )
        }
        opened?.let { binding ->
            TextButton(onClick = { editing = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("反馈「${binding.title}」首页", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (editing) CatsCoAlertDialog(
                onDismissRequest = { if (!state.busy) editing = false },
                title = { Text("反馈应用首页") },
                text = {
                    Column {
                        ModuleTextField(comment, { comment = it.take(2000); feedbackError = "" }, Modifier.fillMaxWidth(),
                            label = { Text("给当前任务的反馈") }, enabled = !state.busy)
                        if (feedbackError.isNotBlank()) Text(feedbackError, color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { CatsCoSecondaryButton(onClick = { editing = false }, enabled = !state.busy) { Text("取消") } },
                confirmButton = { CatsCoButton(enabled = !state.busy && comment.isNotBlank(), onClick = {
                    model.action(onFailure = { feedbackError = it }) { current ->
                        model.request(current, "/api/artifacts/annotations", method = "POST", body = binding.feedback(comment.trim(), clientId))
                        editing = false
                        comment = ""
                        if (model.state.value.page == state.page) model.openConversation(binding.topic, "任务")
                    }
                }) { Text("发送到任务") } },
            )
        }
    }
}

@Composable
internal fun ApplicationsPanel(
    resource: ResourceState,
    agents: List<Employee>,
    favorites: Set<String>,
    initialAgent: String = "",
    accountKey: String = "",
    busy: Boolean = false,
    onRetry: () -> Unit,
    onFavorite: (JSONObject) -> Unit,
    onOpen: (JSONObject) -> Unit,
    onBrowser: (JSONObject) -> Unit,
    onShare: (JSONObject) -> Unit,
    outputContext: Boolean = false,
    sourceTopic: String? = null,
) {
    var scope by rememberSaveable(accountKey) { mutableStateOf("all") }
    var selectedAgent by rememberSaveable(accountKey, initialAgent) { mutableStateOf(initialAgent) }
    var query by rememberSaveable(accountKey) { mutableStateOf("") }
    var menu by remember { mutableStateOf("") }
    val apps = remember(resource.value) { resource.value.objects("apps") }
    val selected = agents.find { it.id == selectedAgent }
    val visible = remember(apps, selectedAgent, scope, favorites, query) {
        val search = query.trim()
        apps.filter { app ->
            (selectedAgent.isBlank() || app.first("source_agent_id", "agent", "agent_uid") == selectedAgent) &&
                (scope != "favorites" || applicationKey(app) in favorites) &&
                (scope != "manageable" || app.optBoolean("can_manage")) &&
                (search.isEmpty() || listOf(app.first("title", "name"), app.first("source_agent_name", "owner_name"),
                    app.first("description", "subtitle")).any { it.contains(search, ignoreCase = true) })
        }
    }
    val failed = resource.value.optJSONArray("failed_agents")
    val failedIds = (0 until (failed?.length() ?: 0)).map { failed!!.optString(it) }
    val failure = when {
        resource.error != null -> "应用暂时无法加载"
        selectedAgent in failedIds -> "这个 Agent 的应用暂时无法加载"
        selectedAgent.isBlank() && failedIds.isNotEmpty() -> "${failedIds.size} 位员工的应用暂时无法加载"
        else -> ""
    }
    val backdrop = rememberHazeState()
    val taskApps = remember(visible, sourceTopic) {
        if (sourceTopic == null) emptyList() else visible.filter {
            it.first("source_topic_id", "sourceTopicId") == sourceTopic
        }
    }
    val ordered = remember(visible, taskApps) { taskApps + visible.filterNot { it in taskApps } }
    val list = rememberLazyListState()
    val filters = Triple(scope, selectedAgent, query)
    var lastFilters by remember { mutableStateOf(filters) }
    LaunchedEffect(filters) {
        if (filters != lastFilters) {
            lastFilters = filters
            list.scrollToItem(0)
            menu = ""
        }
    }
    LaunchedEffect(agents) {
        if (initialAgent.isBlank() && selectedAgent.isNotBlank() && selected == null) selectedAgent = ""
    }
    LaunchedEffect(list.isScrollInProgress) { if (list.isScrollInProgress) menu = "" }
    Column(Modifier.fillMaxSize().imePadding()) {
        if (!outputContext || apps.size >= 8 || query.isNotBlank()) CatsCoSidebarSearchField(query, { query = it }, backdrop,
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("application-search"), "搜索应用", glass = false)
        if (!outputContext) LibraryToolbar(
            section = "application",
            employeeName = selected?.name ?: "全部员工",
            employees = listOf("" to "全部员工") + agents.map { it.id to it.name },
            selectedEmployee = selectedAgent,
            onEmployeeSelected = { selectedAgent = it },
            scopes = listOf("all" to "全部", "favorites" to "收藏", "manageable" to "可管理"),
            scope = scope,
            onScopeSelected = { scope = it },
        )
        if (failure.isNotBlank()) Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(failure, Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CatsCoSecondaryButton(onClick = onRetry, enabled = !resource.loading) { Text("重试", fontSize = 13.sp) }
        }
        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().testTag("application-list"), state = list,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
                if (resource.loading && apps.isEmpty()) item { ModuleLoading() }
                if (visible.isEmpty() && !resource.loading && failure.isBlank()) item {
                    ModuleEmpty(when {
                        query.isNotBlank() -> "没有找到匹配的应用"
                        scope == "favorites" -> "还没有收藏的应用"
                        scope == "manageable" -> "没有可管理的应用"
                        outputContext && initialAgent.isBlank() -> if (sourceTopic == null) "暂无可查看的 Agent" else "当前任务没有关联的 Agent"
                        else -> "还没有分享的应用"
                    }, when {
                        query.isNotBlank() -> "换一个关键词，或清除搜索后查看全部。"
                        outputContext && initialAgent.isBlank() -> ""
                        else -> "Agent 分享的应用会显示在这里。"
                    })
                }
                itemsIndexed(ordered, key = { _, app -> applicationKey(app) }) { index, app ->
                    if (taskApps.isNotEmpty() && (index == 0 || index == taskApps.size)) {
                        Text(if (index == 0) "本任务产出" else "该 Agent 的其他应用",
                            Modifier.padding(start = 4.dp, top = if (index == 0) 4.dp else 14.dp, bottom = 2.dp),
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val key = applicationKey(app)
                    ApplicationRow(app, key in favorites, menu == key, busy,
                        onMenu = { menu = key }, onDismiss = { menu = "" },
                        onFavorite = { onFavorite(app); menu = "" },
                        onOpen = { menu = ""; onOpen(app) }, onShare = { menu = ""; onShare(app) },
                        onBrowser = { menu = ""; onBrowser(app) })
                }
            }
        }
    }
}

@Composable
internal fun LibraryToolbar(
    section: String,
    employeeName: String,
    employees: List<Pair<String, String>>,
    selectedEmployee: String,
    onEmployeeSelected: (String) -> Unit,
    scopes: List<Pair<String, String>>,
    scope: String,
    onScopeSelected: (String) -> Unit,
    unavailableScope: String? = null,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("$section-agent-row")) {
        val employee: @Composable () -> Unit = {
            LibraryEmployeeMenu(employeeName, employees, selectedEmployee, onEmployeeSelected, "$section-agent")
        }
        val filters: @Composable () -> Unit = {
            Row(Modifier.selectableGroup().testTag("$section-scope").padding(end = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for ((key, label) in scopes) {
                    val selected = scope == key
                    val enabled = key != unavailableScope
                    Box(
                        Modifier.heightIn(min = 48.dp).selectable(selected, enabled = enabled, role = Role.Tab,
                            onClick = { onScopeSelected(key) }).testTag("$section-filter-$key").padding(horizontal = 10.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val colors = MaterialTheme.colorScheme
                        Text(label, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = when {
                                selected -> colors.onSurface
                                enabled -> colors.onSurfaceVariant
                                else -> colors.onSurfaceVariant.copy(alpha = .5f)
                            })
                        if (selected) Box(Modifier.align(Alignment.BottomCenter).offset(y = 4.dp).width(16.dp).height(2.dp)
                            .background(colors.onSurface, RoundedCornerShape(1.dp)))
                    }
                }
            }
        }
        val minimumInlineWidth = (228 + scopes.size * 16).dp * LocalDensity.current.fontScale
        if (maxWidth < minimumInlineWidth) {
            Column {
                employee()
                filters()
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { employee() }
                filters()
            }
        }
    }
}

@Composable
internal fun LibraryItemText(title: String, detail: String, modifier: Modifier = Modifier, detailMaxLines: Int = 1,
    highlight: String = "") {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (detail.isNotBlank()) Text(highlightedSnippet(detail, highlight, MaterialTheme.colorScheme.onSurface), fontSize = 13.sp, lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = detailMaxLines, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun LibraryEmployeeMenu(label: String, options: List<Pair<String, String>>, selected: String,
    onSelect: (String) -> Unit, testTag: String) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        val colors = MaterialTheme.colorScheme
        Surface(shape = RoundedCornerShape(24.dp), color = Color.Transparent) {
            Row(Modifier.heightIn(min = 48.dp)
                .catsCoDisclosureClickable(expanded, enabled = options.isNotEmpty()) { expanded = true }
                .testTag(testTag)
                .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.e_people), "当前员工",
                    Modifier.size(CatsCoMobileSpec.actionIconSize), tint = colors.onSurfaceVariant)
                Text(label, Modifier.weight(1f, fill = false), fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                CatsCoDisclosureChevron(expanded, size = 16.dp, tint = colors.onSurfaceVariant)
            }
        }
        CatsCoMenuPopup(expanded, { expanded = false }, testTag = "$testTag-menu", width = 208.dp) {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                options.forEach { (id, name) ->
                    DropdownMenuItem(text = { Text(name, fontSize = CatsCoMobileSpec.menuText, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (selected == id) FontWeight.Medium else FontWeight.Normal) },
                        trailingIcon = { if (selected == id) Icon(painterResource(R.drawable.e_select), null, Modifier.size(CatsCoMobileSpec.actionIconSize)) },
                        modifier = Modifier.heightIn(min = CatsCoMobileSpec.menuItemHeight),
                        onClick = { expanded = false; onSelect(id) })
                }
            }
        }
    }
}

@Composable
private fun ApplicationRow(app: JSONObject, favorite: Boolean, menu: Boolean, busy: Boolean,
    onMenu: () -> Unit, onDismiss: () -> Unit, onFavorite: () -> Unit,
    onOpen: () -> Unit, onShare: () -> Unit, onBrowser: () -> Unit) {
    val title = app.first("title", "name").ifBlank { "未命名应用" }
    val date = applicationTimestamp(app).takeIf { it > 0 }?.let {
        DateTimeFormatter.ofPattern("MM/dd").format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
    }
    val detail = listOfNotNull(app.first("source_agent_name", "owner_name").takeIf(String::isNotBlank), date?.let { "更新于 $it" }).joinToString(" · ")
    val horizontalDrag = remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).testTag("application-row-${app.first("id")}")
            .heightIn(min = 76.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    try {
                        do {
                            // Observe the drag without intercepting the drawer or scrolling.
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                                ?: break
                            val displacement = change.position - down.position
                            if (abs(displacement.x) > viewConfiguration.touchSlop && abs(displacement.x) > abs(displacement.y)) {
                                horizontalDrag.value = true
                            }
                        } while (change.pressed)
                        awaitPointerEvent(PointerEventPass.Final)
                    } finally {
                        horizontalDrag.value = false
                    }
                }
            }
            .clickable(enabled = !busy) { if (!horizontalDrag.value) onOpen() }
            .padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            LibraryItemText(title, detail, Modifier.weight(1f).padding(vertical = 14.dp))
            Box {
                CatsCoBoundedIconButton(onClick = onMenu, modifier = Modifier.size(48.dp).testTag("application-more-${app.first("id")}")) {
                    Icon(painterResource(R.drawable.e_more), "$title 的更多操作", Modifier.size(CatsCoMobileSpec.actionIconSize), tint = MaterialTheme.colorScheme.onSurface)
                }
                CatsCoMenuPopup(menu, onDismiss, testTag = "application-action-menu", width = 208.dp) {
                    listOf(Triple("打开", R.drawable.e_apps, onOpen),
                        Triple(if (favorite) "取消收藏" else "收藏", R.drawable.e_star, onFavorite),
                        Triple("复制分享链接", R.drawable.message_copy, onShare),
                        Triple("在浏览器中打开", R.drawable.e_external, onBrowser)).forEachIndexed { index, (label, icon, action) ->
                        CatsCoMenuActionItem(label, icon, enabled = index != 0 || !busy, onClick = action)
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 4.dp), thickness = .5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))
    }
}
