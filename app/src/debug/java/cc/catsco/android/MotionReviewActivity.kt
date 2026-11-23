package cc.catsco.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import cc.catsco.android.data.MessagePlanStep
import cc.catsco.android.data.MessageTurn
import cc.catsco.android.data.MessageWorkStep
import cc.catsco.android.ui.*
import cc.catsco.core.Employee
import cc.catsco.core.Project
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Motion samples are local and do not access account data or send requests. */
class MotionReviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !dark
            }
            CatsCoTheme(dark) { MotionGallery() }
        }
    }
}

private val motionScenes = listOf("发送与键盘", "侧栏与刷新", "筛选与切页", "详情与计划", "历史与定位", "菜单与面板", "通知与复制")

@Composable
private fun MotionGallery() {
    var scene by rememberSaveable { mutableIntStateOf(-1) }
    BackHandler(scene >= 0) { scene = -1 }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (scene >= 0) CatsCoHeaderAction(R.drawable.e_next, "返回动效总览", decorated = false, iconRotation = 180f) { scene = -1 }
                Text(if (scene < 0) "CatsCo 动效预览" else motionScenes[scene], style = MaterialTheme.typography.titleMedium)
            }
            if (scene < 0) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                    item { Text("本地示例 · 直接点击或拖动", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(motionScenes.size) { index ->
                        ModuleActionRow(motionScenes[index], trailing = true) { scene = index }
                        HorizontalDivider(thickness = .5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            } else Box(Modifier.weight(1f).fillMaxWidth().testTag("motion-scene").clipToBounds()) {
                when (scene) {
                    0 -> SendMotion()
                    1 -> DrawerMotion()
                    2 -> TabsMotion()
                    3 -> DisclosureMotion()
                    4 -> HistoryMotion()
                    5 -> PanelsMotion()
                    6 -> NoticeMotion()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SendMotion() {
    var replay by remember { mutableIntStateOf(0) }
    var stage by remember { mutableIntStateOf(0) }
    var draft by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("帮我整理今天的讨论。") }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val send: () -> Unit = {
        submitted = draft.ifBlank { "帮我整理今天的讨论。" }
        draft = ""
        keyboard?.hide()
        focus.clearFocus()
        replay++
    }
    LaunchedEffect(replay) {
        if (replay > 0) {
            if (stage != 0) {
                stage = 0
                delay(160)
            }
            stage = 1
            delay(350)
            stage = 2
            delay(600)
            stage = 3
            delay(420)
            stage = 4
        }
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(rememberComposerImeInsets())) {
        TextButton(onClick = send, modifier = Modifier.align(Alignment.End)) { Text("重播发送") }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SendWelcome(stage == 0)
            if (stage != 0) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)) {
                SampleMessage(sampleTurn(1, false, submitted).copy(pendingDelivery = stage == 1))
                if (stage >= 2) SampleMessage(sampleTurn(2, true, when (stage) {
                    2 -> ""
                    3 -> "我已整理出讨论要点。"
                    else -> "我已整理出讨论要点。下一步是核对任务负责人和交付日期。"
                }).copy(streaming = stage == 3), active = stage in 2..3)
            }
        }
        CatsCoComposerFrame(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(draft, { draft = it }, Modifier.weight(1f).heightIn(min = 48.dp).padding(12.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    decorationBox = { inner ->
                        if (draft.isEmpty()) Text("点击输入框，查看键盘与输入栏", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        inner()
                    })
                ComposerSendButton(false, false, draft.isNotBlank(), hasDraft = draft.isNotBlank(), onSend = send, onStop = {})
            }
        }
    }
}

@Composable
private fun SendWelcome(visible: Boolean) {
    AnimatedVisibility(visible, exit = fadeOut(tween(150))) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("需要为您做什么？", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrawerMotion() {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var width by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val inDrawer = drawer.isOpen || drawer.targetValue == DrawerValue.Open
    val refresh: () -> Unit = {
        refreshing = true
        scope.launch { delay(650); revision++; refreshing = false }
    }
    val sidebarRefresh = rememberCatsCoPullRefresh(inDrawer, refreshing, refresh)
    val homeRefresh = rememberCatsCoPullRefresh(!inDrawer, refreshing, refresh)
    val backdrop = rememberHazeState()
    val agentBlur = rememberUpdatedState(0.dp)
    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet(Modifier.fillMaxWidth(.8f).onSizeChanged { width = it.width }) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                CatsCoWordmark(Modifier.width(88.dp).height(22.dp))
                Spacer(Modifier.weight(1f))
                CatsCoHeaderAction(R.drawable.e_sidebar_active, "关闭侧栏", decorated = false) { scope.launch { drawer.close() } }
            }
            Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
                CatsCoSidebarSearchField("", {}, backdrop, glass = false)
                CatsCoSidebarShortcut(R.drawable.e_apps, "应用") {}
                Box(Modifier.weight(1f).clipToBounds().catsCoPullRefresh(sidebarRefresh, enabled = inDrawer)) {
                    LazyColumn(Modifier.fillMaxSize().catsCoRefreshTranslation(sidebarRefresh)) {
                        if (revision > 0) item { ListItem(headlineContent = { Text("刚同步的任务 $revision") }) }
                        items(18) { ListItem(headlineContent = { Text("历史任务 ${it + 1}") }) }
                    }
                    CatsCoRefreshSlot(sidebarRefresh, "sidebar-refresh-indicator")
                }
            }
        }
    }) {
        Column(Modifier.fillMaxSize().then(workspaceBackdropBlur(drawer, width, agentBlur))) {
            CatsCoHeaderAction(R.drawable.e_sidebar, "打开侧栏", decorated = false) { scope.launch { drawer.open() } }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds().catsCoPullRefresh(homeRefresh, enabled = !inDrawer)) {
                val height = maxHeight
                Box(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).height(height)
                    .catsCoRefreshTranslation(homeRefresh), contentAlignment = Alignment.Center) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("新建任务页", style = MaterialTheme.typography.titleMedium)
                        Text("在这里下拉，或打开侧栏再下拉。", style = MaterialTheme.typography.bodyMedium)
                        if (revision > 0) Text("已刷新 $revision 次", style = MaterialTheme.typography.bodySmall)
                    }
                }
                CatsCoRefreshSlot(homeRefresh, "home-refresh-indicator")
            }
        }
    }
}

@Composable
private fun TabsMotion() {
    var outputs by rememberSaveable { mutableStateOf(false) }
    var alternate by rememberSaveable { mutableStateOf(false) }
    var project by rememberSaveable { mutableStateOf("") }
    val holder = rememberSaveableStateHolder()
    val backdrop = rememberHazeState()
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = { outputs = !outputs; alternate = false }) { Text(if (outputs) "查看应用与 SkillHub" else "查看文件与应用") }
        if (outputs) TaskOutputsTabs(alternate) { alternate = it } else CatsCoLibraryTabs(alternate) { alternate = it }
        Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            ProjectFilters(listOf(Project("work", "产品设计"), Project("research", "调研")), project, backdrop, {}) {
                project = if (project == it) "" else it
            }
        }
        val section = if (outputs) if (alternate) "应用" else "文件" else if (alternate) "技能" else "应用"
        holder.SaveableStateProvider("$outputs/$alternate/$project") {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(),
                state = rememberLazyListState(), contentPadding = PaddingValues(16.dp)) {
                items(if (project.isEmpty()) 18 else 5) { index -> ListItem(headlineContent = { Text("$section ${index + 1}") }) }
            }
        }
    }
}

@Composable
private fun DisclosureMotion() {
    var completed by remember { mutableStateOf(false) }
    val quota = remember { JSONObject().put("quota_configured", true).put("remaining_percent", 72).put("model", "示例模型").put("provider", "共享服务") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ModelUsageCard(quota, "套餐与权益")
        TextButton(onClick = { completed = !completed }) { Text(if (completed) "重置计划状态" else "完成当前步骤") }
        SampleMessage(sampleTurn(3, true, "").copy(
            steps = listOf(MessageWorkStep("read", "tool", "read_file", description = "整理讨论记录", result = "已核对示例记录。")),
            plan = listOf(MessagePlanStep("整理讨论记录", if (completed) "completed" else "in_progress"), MessagePlanStep("确认交付时间", "pending")),
            executionState = if (completed) "completed" else "running",
        ), active = false)
    }
}

@Composable
private fun HistoryMotion() {
    var older by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var overlap by remember { mutableStateOf(false) }
    val backdrop = rememberHazeState()
    val messages = remember(older) { ((if (older) 1 else 9)..25).map { sampleTurn(it, it % 2 == 0, "示例消息 $it · 可向上翻阅") } }
    LaunchedEffect(loading) {
        if (loading) {
            delay(650)
            older = true
            loading = false
        }
    }
    Box(Modifier.fillMaxSize()) {
        ConversationTimeline("motion-history", messages, "1", loading, !older, { loading = true },
            Modifier.fillMaxSize().hazeSource(backdrop), topContentPadding = 48.dp, loadingOlder = loading,
            onMessagesBehindHeader = { overlap = it }) { SampleMessage(it) }
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            CatsCoConversationHeaderGlass(backdrop, overlap, Modifier.matchParentSize())
            Text("向上翻阅后，点击「回到最新」", Modifier.align(Alignment.Center), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PanelsMotion() {
    var menu by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf("") }
    var selectedAgent by rememberSaveable { mutableStateOf("9") }
    val agents = remember { listOf(Employee("9", "示例 Agent", online = true), Employee("10", "资料助手", online = true)) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CatsCoHomeAgent(agents, selectedAgent, onSelected = { selectedAgent = it }, onSettings = { sheet = "Agent" })
            TextButton(onClick = { sheet = "Agent" }) { Text("打开 Agent 面板") }
            TextButton(onClick = { sheet = "项目" }) { Text("打开项目面板") }
            Text("横滑或长按切换 Agent，也可以拖动面板和打开更多。", style = MaterialTheme.typography.bodySmall)
        }
        CatsCoHeaderActionMenu(menu, { menu = it }, onComputer = { sheet = "电脑连接" },
            onNewTask = { sheet = "Agent" }, onCloudFiles = { sheet = "项目" }, onRefresh = {})
    }
    if (sheet.isNotEmpty()) {
        val state = rememberModalBottomSheetState()
        val content: @Composable ColumnScope.() -> Unit = {
            Text(sheet, Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
            listOf("产品设计", "资料整理", "最近任务", "项目文件").forEach { name ->
                ListItem(headlineContent = { Text(name) })
            }
            TextButton(onClick = { sheet = "" }, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
        }
        if (sheet == "项目") CatsCoProjectSheet(state, { sheet = "" }) { Column(Modifier.height(540.dp), content = content) }
        else AgentSettingsLayout(state, { sheet = "" }, content)
    }
}

@Composable
private fun NoticeMotion() {
    var notice by remember { mutableStateOf<WorkspaceNotification?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var trigger by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    LaunchedEffect(trigger, expanded) {
        if (notice != null && !expanded) { delay(3000); notice = null }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            TextButton(onClick = {
                trigger++
                expanded = false
                notice = WorkspaceNotification("motion", WorkspaceNotificationKind.Completed, "整理讨论记录",
                    "讨论记录已整理完成。", "刚刚", "已整理出讨论要点、后续负责人和交付时间。", key = "$trigger")
            }) { Text("播放任务完成通知") }
            TextButton(onClick = { copyMessageText(context, "CatsCo 示例文字") }) { Text("复制示例文字") }
        }
        WorkspaceNotificationBanner(notice, expanded, null, onExpand = { expanded = true },
            onOpen = { notice = null }, onDismiss = { notice = null },
            modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

private fun sampleTurn(id: Int, assistant: Boolean, text: String) = MessageTurn(
    "motion-$id", if (assistant) "9" else "1", if (assistant) "示例 Agent" else "", assistant,
    emptyList(), emptyList(), emptyList(), listOf(text).filter(String::isNotEmpty), emptyList(), "", false, id.toLong(),
)

@Composable
private fun SampleMessage(turn: MessageTurn, active: Boolean = false) {
    MessageTurnContent(turn, !turn.assistant, turn.senderName, active, false, {}, {})
}
