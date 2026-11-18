package cc.catsco.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.catsco.android.R
import cc.catsco.android.ResourceState
import cc.catsco.android.WorkspaceState
import cc.catsco.android.WorkspaceViewModel
import cc.catsco.android.data.*
import cc.catsco.core.Employee
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
internal fun CatsCoLibraryTabs(skills: Boolean, onSelect: (Boolean) -> Unit) {
    CatsCoSegmentedControl(listOf("apps" to "应用", "skills" to "SkillHub"), if (skills) "skills" else "apps",
        { onSelect(it == "skills") }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), "library-tab")
}

@Composable
internal fun ApplicationsScreen(state: WorkspaceState, model: WorkspaceViewModel) {
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
    ApplicationsPanel(
        resource = state.resource,
        agents = state.workspace.employees,
        favorites = favorites,
        initialAgent = state.page.takeIf { it.startsWith("agent-apps/") }?.removePrefix("agent-apps/").orEmpty(),
        accountKey = accountKey,
        busy = state.busy,
        onRetry = { model.loadResource() },
        onFavorite = toggleFavorite,
        onOpen = { app -> model.action { current ->
            val result = model.request(current, "/api/artifacts/launch", method = "POST", body = JSONObject().put("app", app.first("id")))
            val url = result.first("launch_url", "launchUrl", "url")
            check(url.isNotBlank()) { "应用暂时无法打开，请重试。" }
            model.openUrl(url)
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
    )
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
) {
    var scope by rememberSaveable(accountKey) { mutableStateOf("all") }
    var selectedAgent by rememberSaveable(accountKey, initialAgent) { mutableStateOf(initialAgent) }
    var query by rememberSaveable(accountKey) { mutableStateOf("") }
    var menu by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf("") }
    val apps = resource.value.objects("apps")
    val selected = agents.find { it.id == selectedAgent }
    val visible = apps.filter { app ->
        (selectedAgent.isBlank() || app.first("source_agent_id", "agent", "agent_uid") == selectedAgent) &&
            (scope != "favorites" || applicationKey(app) in favorites) &&
            (scope != "manageable" || app.optBoolean("can_manage")) &&
            (query.isBlank() || listOf(app.first("title", "name"), app.first("source_agent_name", "owner_name"),
                app.first("description", "subtitle")).any { it.contains(query.trim(), ignoreCase = true) })
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
    val list = rememberLazyListState()
    LaunchedEffect(scope, selectedAgent, query) { list.scrollToItem(0); revealed = ""; menu = "" }
    LaunchedEffect(agents) {
        if (initialAgent.isBlank() && selectedAgent.isNotBlank() && selected == null) selectedAgent = ""
    }
    LaunchedEffect(list.isScrollInProgress) { if (list.isScrollInProgress) { revealed = ""; menu = "" } }
    Column(Modifier.fillMaxSize().imePadding()) {
        LibraryEmployeeSelector(selected?.name ?: "全部员工", listOf("" to "全部员工") + agents.map { it.id to it.name },
            selectedAgent, { selectedAgent = it }, "application-agent")
        CatsCoSidebarSearchField(query, { query = it }, backdrop,
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("application-search"), "搜索应用", glass = false)
        CatsCoSegmentedControl(listOf("all" to "全部应用", "favorites" to "收藏", "manageable" to "可管理"), scope,
            { scope = it }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("application-scope"), "application-filter")
        if (failure.isNotBlank()) Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(failure, Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CatsCoSecondaryButton(onClick = onRetry, enabled = !resource.loading) { Text("重试", fontSize = 13.sp) }
        }
        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().hazeSource(backdrop).testTag("application-list"), state = list,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
                if (resource.loading && apps.isEmpty()) item { ModuleLoading() }
                if (visible.isEmpty() && !resource.loading && failure.isBlank()) item {
                    ModuleEmpty(when {
                        query.isNotBlank() -> "没有找到匹配的应用"
                        scope == "favorites" -> "还没有收藏的应用"
                        scope == "manageable" -> "没有可管理的应用"
                        else -> "还没有分享的应用"
                    }, if (query.isNotBlank()) "换一个关键词，或清除搜索后查看全部。" else "Agent 分享的应用会显示在这里。")
                }
                items(visible, key = ::applicationKey) { app ->
                    val key = applicationKey(app)
                    ApplicationRow(app, key in favorites, revealed == key, menu == key, busy,
                        onReveal = { revealed = if (it) key else "" },
                        onMenu = { menu = key; revealed = "" }, onDismiss = { menu = "" },
                        onFavorite = { onFavorite(app); revealed = ""; menu = "" },
                        onOpen = { menu = ""; onOpen(app) }, onShare = { menu = ""; onShare(app) },
                        onBrowser = { menu = ""; onBrowser(app) })
                }
            }
        }
    }
}

@Composable
internal fun LibraryEmployeeSelector(label: String, options: List<Pair<String, String>>, selected: String,
    onSelect: (String) -> Unit, testTag: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("$testTag-row"),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("当前员工", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.widthIn(max = 184.dp).weight(1f, fill = false)) {
            ApplicationFilter(label, R.drawable.e_people, testTag, options, selected, onSelect)
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
private fun ApplicationFilter(label: String, icon: Int, testTag: String, options: List<Pair<String, String>>,
    selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .65f)) {
            Row(Modifier.heightIn(min = 48.dp).clickable(enabled = options.isNotEmpty()) { expanded = true }.testTag(testTag).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(icon), null, Modifier.size(CatsCoMobileSpec.actionIconSize), tint = MaterialTheme.colorScheme.onSurface)
                Text(label, Modifier.weight(1f, fill = false), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(painterResource(R.drawable.e_expand), null, Modifier.size(CatsCoMobileSpec.actionIconSize), tint = MaterialTheme.colorScheme.onSurface)
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
private fun ApplicationRow(app: JSONObject, favorite: Boolean, revealed: Boolean, menu: Boolean, busy: Boolean,
    onReveal: (Boolean) -> Unit, onMenu: () -> Unit, onDismiss: () -> Unit, onFavorite: () -> Unit,
    onOpen: () -> Unit, onShare: () -> Unit, onBrowser: () -> Unit) {
    val title = app.first("title", "name").ifBlank { "未命名应用" }
    val date = applicationTimestamp(app).takeIf { it > 0 }?.let {
        DateTimeFormatter.ofPattern("MM/dd").format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
    }
    val detail = listOfNotNull(app.first("source_agent_name", "owner_name").takeIf(String::isNotBlank), date?.let { "更新于 $it" }).joinToString(" · ")
    val density = LocalDensity.current
    val distance = with(density) { 76.dp.toPx() }
    val edge = with(density) { 24.dp.toPx() }
    var dragging by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    val settled by animateFloatAsState(if (revealed) distance else 0f, spring(dampingRatio = 1f, stiffness = 550f), label = "applicationSwipe")
    val revealCallback by rememberUpdatedState(onReveal)
    Column {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).testTag("application-row-${app.first("id")}")) {
            if (revealed || dragging || settled > 1f) CatsCoSecondaryButton(onClick = onFavorite,
                modifier = Modifier.align(Alignment.CenterStart).size(68.dp).testTag("application-swipe-favorite")) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(painterResource(R.drawable.e_star), null, Modifier.size(CatsCoMobileSpec.actionIconSize), tint = Mint)
                    Text(if (favorite) "取消收藏" else "收藏", fontSize = 12.sp)
                }
            }
            Row(Modifier.offset { IntOffset((if (dragging) drag else settled).roundToInt(), 0) }
                .fillMaxWidth().background(MaterialTheme.colorScheme.background)
                .pointerInput(revealed, distance) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // Reserve 40dp (16dp list inset + 24dp here) for the drawer,
                        // including space beyond Android's system back-gesture strip.
                        if (down.position.x < edge) return@awaitEachGesture
                        val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                            change.consume()
                            dragging = true
                            drag = ((if (revealed) distance else 0f) + over).coerceIn(0f, distance)
                        }
                        if (start != null) {
                            val finished = horizontalDrag(start.id) { change ->
                                drag = (drag + change.positionChange().x).coerceIn(0f, distance)
                                change.consume()
                            }
                            revealCallback(if (finished) drag > distance * .4f else revealed)
                            dragging = false
                        }
                    }
                }
                .heightIn(min = 76.dp).clickable(enabled = !busy) { if (revealed) onReveal(false) else onOpen() }
                .padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                LibraryItemText(title, detail, Modifier.weight(1f).padding(vertical = 14.dp))
                Box {
                    IconButton(onClick = onMenu, modifier = Modifier.size(48.dp).testTag("application-more-${app.first("id")}")) {
                        Icon(painterResource(R.drawable.e_more), "$title 的更多操作", Modifier.size(CatsCoMobileSpec.actionIconSize), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    CatsCoMenuPopup(menu, onDismiss, testTag = "application-action-menu", width = 208.dp) {
                        listOf(Triple("打开", R.drawable.e_apps, onOpen),
                            Triple(if (favorite) "取消收藏" else "收藏", R.drawable.e_star, onFavorite),
                            Triple("复制分享链接", R.drawable.message_copy, onShare),
                            Triple("在浏览器中打开", R.drawable.e_external, onBrowser)).forEachIndexed { index, (label, icon, action) ->
                            DropdownMenuItem(text = { Text(label, style = CatsCoMobileSpec.menuLabelStyle) },
                                contentPadding = CatsCoMobileSpec.menuContentPadding,
                                leadingIcon = { Icon(painterResource(icon), null, Modifier.size(CatsCoMobileSpec.menuIconSize),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (index != 0 || !busy) 1f else .38f)) },
                                modifier = Modifier.height(CatsCoMobileSpec.menuItemHeight).clip(CatsCoMobileSpec.radiusSmall), enabled = index != 0 || !busy,
                                onClick = action)
                        }
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 4.dp), thickness = .5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))
    }
}
