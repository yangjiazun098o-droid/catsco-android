package cc.catsco.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import cc.catsco.android.*
import cc.catsco.android.R
import cc.catsco.core.*
import kotlinx.coroutines.launch
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

@Composable fun Brand(width: Int = 88, wordmark: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Image(painterResource(R.drawable.catsco_mark), "CatsCo", Modifier.width(width.dp).height((width * .46f).dp), colorFilter = ColorFilter.tint(Mint))
        if (wordmark) CatsCoWordmark(Modifier.width(88.dp).height(22.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable fun CatsCoApp(state: WorkspaceState, model: WorkspaceViewModel) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(drawer.targetValue) {
        if (drawer.targetValue == DrawerValue.Open) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    var drawerWidth by remember { mutableIntStateOf(0) }
    val darkBackground = MaterialTheme.colorScheme.background.luminance() < .5f
    var homeAgentPickerVisible by remember { mutableStateOf(false) }
    val pageStates = key(state.session?.server, state.session?.userId, state.localDataEpoch) { rememberSaveableStateHolder() }
    val agentSheet = key(state.agentSettings?.agentId) {
        rememberModalBottomSheetState(skipPartiallyExpanded = false)
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val media = rememberMediaActions(model)
    var exportLaunched by rememberSaveable { mutableStateOf<String?>(null) }
    val saveFile = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        model.saveAttachment(if (result.resultCode == android.app.Activity.RESULT_OK) result.data?.data else null)
    }
    LaunchedEffect(state.exportFile) {
        val file = state.exportFile
        if (file == null) exportLaunched = null
        else if (exportLaunched != file.uri.toString()) {
            exportLaunched = file.uri.toString()
            try {
                saveFile.launch(android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType(file.mime)
                    .putExtra(android.content.Intent.EXTRA_TITLE, file.name))
            } catch (_: Exception) {
                model.saveAttachment(null)
                model.notice("没有可用于保存文件的系统应用。")
            }
        }
    }
    LaunchedEffect(state.openUrl) {
        state.openUrl?.let { url ->
            try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            catch (_: Exception) { model.notice("没有可用于打开链接的浏览器。") }
            model.urlOpened()
        }
    }
    LaunchedEffect(state.localFile) {
        state.localFile?.let { file ->
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(file.uri, file.mime).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(android.content.Intent.createChooser(intent, "打开附件"))
            } catch (_: Exception) { model.notice("没有可打开此格式的应用。") }
            model.fileOpened()
        }
    }
    val userId = state.session?.userId.orEmpty()
    val sidebarPrefs = remember(context, userId) { context.getSharedPreferences("sidebar_$userId", android.content.Context.MODE_PRIVATE) }
    var pinnedConversationIds by remember(userId) { mutableStateOf(sidebarPrefs.getStringSet("pinned", emptySet())?.toSet().orEmpty()) }
    var hiddenConversationIds by remember(userId) { mutableStateOf(sidebarPrefs.getStringSet("hidden", emptySet())?.toSet().orEmpty()) }
    var selectedConversationIds by remember(userId) { mutableStateOf(emptySet<String>()) }
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var deleteTargets by remember { mutableStateOf<List<Conversation>?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var morphMenuExpanded by remember { mutableStateOf(false) }
    val isConversation = state.page.startsWith("chat/")
    val sidebarPage = state.page in setOf("home", "apps", "skills") || isConversation
    val showTaskMenu = state.page == "home" || isConversation
    LaunchedEffect(state.page) { morphMenuExpanded = false }
    var historyQuery by rememberSaveable(userId) { mutableStateOf("") }
    LaunchedEffect(state.agentSettings?.agentId, state.workspace.employees.map { it.id }) {
        val id = state.agentSettings?.agentId
        if (id != null && state.workspace.employees.none { it.id == id }) model.dismissAgentSettings()
    }
    var draftTaskTitle by rememberSaveable(userId) { mutableStateOf("新任务") }
    var renameDraftTitleOpen by remember { mutableStateOf(false) }
    val visibleConversations = state.workspace.conversations
        .filterNot { it.id in hiddenConversationIds }
        .sortedByDescending { it.id in pinnedConversationIds }
    val titles = mapOf("home" to "新任务", "skills" to "SkillHub", "apps" to "应用", "settings" to "设置")
    val drawerVisible by remember(drawer) { derivedStateOf {
        drawer.isOpen || drawer.targetValue == DrawerValue.Open ||
            (drawerWidth > 0 && drawer.currentOffset.isFinite() && drawer.currentOffset > -drawerWidth + .5f)
    } }
    BackHandler(enabled = state.session != null && (morphMenuExpanded || drawerVisible || state.page != "home")) {
        when {
            morphMenuExpanded -> morphMenuExpanded = false
            drawerVisible -> scope.launch { drawer.close() }
            else -> model.back()
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (state.session == null) {
            Login(state, model)
        } else Box(Modifier.fillMaxSize()) {
          ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = !state.busy && (sidebarPage || drawerVisible), drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.widthIn(max = 340.dp).fillMaxWidth(.86f).onSizeChanged { drawerWidth = it.width },
                drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                drawerContentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = CatsCoMobileSpec.space8), verticalAlignment = Alignment.CenterVertically) {
                    CatsCoWordmark(Modifier.width(88.dp).height(22.dp))
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { scope.launch { drawer.close() } },
                        modifier = Modifier.offset(x = CatsCoMobileSpec.space6),
                    ) { Icon(painterResource(R.drawable.e_sidebar_active), "关闭侧栏", Modifier.size(CatsCoMobileSpec.actionIconSize)) }
                }
                val sidebarBackdrop = rememberHazeState()
                val density = LocalDensity.current
                var sidebarHeaderHeight by remember(density) { mutableStateOf(162.dp) }
                val sidebarHeader: @Composable (Modifier) -> Unit = { headerModifier ->
                    Surface(modifier = headerModifier, color = MaterialTheme.colorScheme.surfaceContainerLow, contentColor = MaterialTheme.colorScheme.onSurface) {
                        Column(
                            Modifier.fillMaxWidth()
                                .onSizeChanged { sidebarHeaderHeight = with(density) { it.height.toDp() } },
                        ) {
                            CatsCoSidebarSearchField(
                                query = historyQuery,
                                onQueryChange = { historyQuery = it },
                                backdrop = sidebarBackdrop,
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp),
                            )
                            CatsCoSidebarShortcut(R.drawable.e_new_task, "新建任务") {
                                model.navigate("home")
                                scope.launch { drawer.close() }
                            }
                            CatsCoSidebarShortcut(R.drawable.e_apps, "应用") {
                                model.navigate("apps")
                                scope.launch { drawer.close() }
                            }
                        }
                    }
                }
                BoxWithConstraints(Modifier.weight(1f).clipToBounds().padding(horizontal = CatsCoMobileSpec.space8)) {
                    val scrollSidebarHeader = maxHeight < sidebarHeaderHeight + CatsCoMobileSpec.historyRowHeight * 3
                    // Short windows scroll navigation with history so the fixed header cannot cover every task.
                    LazyColumn(
                        modifier = Modifier.fillMaxSize()
                            .then(if (scrollSidebarHeader) Modifier else Modifier.hazeSource(sidebarBackdrop))
                            .testTag("sidebar-history"),
                        contentPadding = PaddingValues(top = if (scrollSidebarHeader) 0.dp else sidebarHeaderHeight + 6.dp, bottom = 8.dp),
                    ) {
                        if (scrollSidebarHeader) item(key = "sidebar-navigation") { sidebarHeader(Modifier.padding(bottom = 6.dp)) }
                        if (historyQuery.isNotBlank()) item(key = "search-message-content") {
                            TextButton(
                                onClick = {
                                    pageStates.removeState("message-search")
                                    model.saveModuleQuery("message-search", historyQuery)
                                    model.navigate("message-search")
                                    scope.launch { drawer.close() }
                                },
                                modifier = Modifier.padding(start = CatsCoMobileSpec.sidebarHistoryInset),
                            ) { Text("搜索消息内容", fontSize = 14.sp) }
                        }
                        if (selectedConversationIds.isNotEmpty()) item(key = "history-selection-actions") {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = CatsCoMobileSpec.minimumTouchTarget),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("已选 ${selectedConversationIds.size} 项", Modifier.weight(1f), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { deleteTargets = visibleConversations.filter { it.id in selectedConversationIds } }) {
                                    Icon(painterResource(R.drawable.e_delete), null, Modifier.size(CatsCoMobileSpec.actionIconSize)); Spacer(Modifier.width(4.dp)); Text("删除")
                                }
                                TextButton(onClick = { selectedConversationIds = emptySet() }) { Text("完成") }
                            }
                        }
                        val matchingConversations = visibleConversations.filter {
                            it.name.contains(historyQuery.trim(), ignoreCase = true)
                        }
                        if (matchingConversations.isEmpty()) item(key = "history-search-empty") {
                            Text(
                                if (historyQuery.isBlank()) "还没有历史任务" else "没有找到匹配的任务",
                                Modifier.padding(
                                    start = CatsCoMobileSpec.sidebarHistoryInset,
                                    end = CatsCoMobileSpec.sidebarContentInset,
                                    top = 18.dp,
                                    bottom = 18.dp,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp,
                            )
                        }
                        matchingConversations.groupBy { historySection(it.date) }.toSortedMap().forEach { (section, conversations) ->
                            item(key = "history-section:${section.name}") {
                                Text(
                                    section.title,
                                    Modifier.fillMaxWidth().padding(
                                        start = CatsCoMobileSpec.sidebarHistoryInset,
                                        end = CatsCoMobileSpec.sidebarContentInset,
                                        top = 14.dp,
                                        bottom = 5.dp,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            items(conversations, key = { "chat:${it.id}" }) { chat ->
                                HistoryEntry(
                                    conversation = chat,
                                    pinned = chat.id in pinnedConversationIds,
                                    selected = chat.id in selectedConversationIds,
                                    selectionMode = selectedConversationIds.isNotEmpty(),
                                    onClick = {
                                        if (selectedConversationIds.isNotEmpty()) {
                                            selectedConversationIds = if (chat.id in selectedConversationIds) selectedConversationIds - chat.id else selectedConversationIds + chat.id
                                        } else {
                                            model.navigate("chat/${chat.id}")
                                            scope.launch { drawer.close() }
                                        }
                                    },
                                    onLongPress = { selectedConversationIds = selectedConversationIds + chat.id },
                                    onRename = { renameTarget = chat; renameDraft = chat.name },
                                    onTogglePin = {
                                        pinnedConversationIds = if (chat.id in pinnedConversationIds) pinnedConversationIds - chat.id else pinnedConversationIds + chat.id
                                        sidebarPrefs.edit().putStringSet("pinned", pinnedConversationIds.toSet()).apply()
                                    },
                                    onSelectMore = { selectedConversationIds = setOf(chat.id) },
                                    onDelete = { deleteTargets = listOf(chat) },
                                )
                            }
                        }
                    }
                    if (!scrollSidebarHeader) sidebarHeader(Modifier)
                }
                CatsCoAccountFooter(state.session, model.accountImages) {
                    model.navigate("settings")
                    scope.launch { drawer.close() }
                }
                if (state.preview) Text("调试示例 · 不会发送请求", Modifier.padding(16.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, scrimColor = Color.Black.copy(alpha = if (darkBackground) .12f else .16f)) {
            val agentBlurVisible = homeAgentPickerVisible || (state.agentSettings != null && agentSheet.targetValue != SheetValue.Hidden)
            val agentBlur = androidx.compose.animation.core.animateDpAsState(
                targetValue = if (agentBlurVisible) 3.dp else 0.dp,
                animationSpec = androidx.compose.animation.core.tween(if (agentBlurVisible) 120 else 80),
                label = "agentSettingsContentBlur",
            )
            val headerBackdrop = rememberHazeState()
            key(state.session?.server, state.session?.userId, state.localDataEpoch, state.appearance) {
            SwipeBackPage(state.page, model.backDestination,
                enabled = !sidebarPage && !drawerVisible && state.agentSettings == null,
                onBack = model::back,
                captureEnabled = !drawerVisible && !morphMenuExpanded && !agentBlurVisible,
                modifier = Modifier.graphicsLayer {
                // Read animation state in the layer so Markdown and the composer stay composed.
                val drawerOffset = drawer.currentOffset
                val drawerProgress = if (drawerWidth > 0 && drawerOffset.isFinite()) {
                    (1f + drawerOffset / drawerWidth).coerceIn(0f, 1f)
                } else if (drawer.isOpen) 1f else 0f
                val radius = (5.dp * drawerProgress + agentBlur.value).toPx()
                renderEffect = if (android.os.Build.VERSION.SDK_INT >= 31 && radius > .01f)
                    androidx.compose.ui.graphics.BlurEffect(radius, radius, androidx.compose.ui.graphics.TileMode.Clamp) else null
                clip = true
            }) {
            Scaffold(Modifier.fillMaxSize(), containerColor = MaterialTheme.colorScheme.background, topBar = {
                Box {
                    CatsCoHeaderGlass(headerBackdrop, Modifier.matchParentSize())
                    Row(Modifier.fillMaxWidth().statusBarsPadding().height(CatsCoMobileSpec.headerHeight).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (state.page == "settings") {
                            Spacer(Modifier.width(CatsCoMobileSpec.headerButtonTarget))
                            Text("设置", Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                            CatsCoHeaderAction(R.drawable.e_close, "关闭设置") { model.navigate("home") }
                        } else {
                            if (sidebarPage) {
                                CatsCoHeaderAction(R.drawable.e_sidebar, "打开侧栏", decorated = false) { scope.launch { drawer.open() } }
                            } else {
                                CatsCoHeaderAction(R.drawable.e_next, "返回", iconRotation = 180f, onClick = model::back)
                            }
                            val titleConversation = state.workspace.conversations.find { "chat/${it.id}" == state.page }
                            val title = when {
                                state.page == "home" -> draftTaskTitle
                                state.page == "skills" -> "应用"
                                else -> titles[state.page] ?: titleConversation?.name ?: moduleTitle(state.page)
                            }
                            val canRenameTitle = state.page == "home" || titleConversation != null
                            Text(
                                title,
                                Modifier.weight(1f).then(
                                    if (canRenameTitle) Modifier.combinedClickable(
                                        onClick = {},
                                        onLongClickLabel = "重命名",
                                        onLongClick = {
                                            if (titleConversation != null) {
                                                renameTarget = titleConversation
                                                renameDraft = titleConversation.name
                                            } else {
                                                renameDraft = draftTaskTitle
                                                renameDraftTitleOpen = true
                                            }
                                        },
                                    ) else Modifier,
                                ),
                                style = if (state.page == "home" || isConversation) {
                                    MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
                                } else MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                textAlign = if (sidebarPage) androidx.compose.ui.text.style.TextAlign.Start else androidx.compose.ui.text.style.TextAlign.Center,
                                overflow = TextOverflow.Ellipsis,
                            )
                            when {
                                state.page == "agents" -> EmployeeAddAction(state.busy, model)
                                state.page == "contacts" -> ContactsAddAction(state, model)
                                !sidebarPage -> Spacer(Modifier.width(CatsCoMobileSpec.headerButtonTarget))
                            }
                            if (showTaskMenu) {
                                Spacer(Modifier.width(96.dp))
                            }
                        }
                    }
                }
            }, snackbarHost = {
                WorkspaceErrorFeedback(state.error, model::consumeError)
            }, bottomBar = {
                if (state.page == "home" || state.page.startsWith("chat/")) pageStates.SaveableStateProvider("composer:${state.page}") {
                    Composer(
                        state,
                        model,
                        onAttachmentSelected = { label -> when (label) { "拍照" -> media.camera(); "照片" -> media.photo(); else -> media.file() } },
                        onVoice = media.voice,
                    ) { model.send(if (state.page == "home") draftTaskTitle else "") }
                }
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(
                    top = if (isConversation) 0.dp else padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding(),
                ).hazeSource(headerBackdrop)) {
                    if (state.busy && state.stoppingTopic.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.notice?.let { notice -> TextButton(onClick = model::dismissError, modifier = Modifier.padding(horizontal = 8.dp)) { Text(notice, fontSize = 14.sp) } }
                    if (state.taskCreationUncertain && state.page == "home") {
                        TextButton(onClick = { dialog = "retry-task" }) { Text("检查历史任务后重新创建") }
                    }
                    if (state.page in setOf("apps", "skills") || state.page.startsWith("agent-apps/")) {
                        CatsCoLibraryTabs(state.page == "skills", model::selectLibraryTab)
                    }
                    pageStates.SaveableStateProvider(state.page) {
                    when (state.page) {
                        "home" -> TaskHome(state, { model.selectEmployee(it) }, Modifier.weight(1f), model::showAgentSettings,
                            model.accountImages, { homeAgentPickerVisible = it })
                        "settings" -> NativeSettings(state, model.accountImages) { entry ->
                            when (entry) {
                                "logout" -> dialog = "logout"
                                "打开 WebApp" -> state.session?.server?.value?.let(model::openUrl)
                                "appearance/system", "appearance/light", "appearance/dark" -> model.appearance(entry.substringAfter('/'))
                                else -> model.navigate(mapOf("个人资料" to "profile", "套餐与权益" to "commercial", "外观" to "appearance", "消息通知" to "notifications", "实时活动与组件" to "surfaces", "我的电脑" to "devices", "云托管" to "cloud-workers", "联系人与群组" to "contacts", "离线与分享草稿" to "offline", "定制 Agent" to "service-agents", "员工管理" to "agents", "模型用量" to "usage", "模型访问凭证" to "relay-key", "服务器" to "server", "反馈" to "feedback")[entry] ?: "settings")
                            }
                        }
                        else -> if (isConversation) ConversationScreen(state, model, Modifier.weight(1f),
                            onOpenSidebar = { if (!state.busy && drawer.isClosed && drawer.targetValue == DrawerValue.Closed) scope.launch { drawer.open() } },
                            topContentPadding = padding.calculateTopPadding()) else WorkspaceModuleScreen(state, model, historyQuery)
                    }
                    }
                }
            }
            if (showTaskMenu) {
                CatsCoMorphingMenu(
                    expanded = morphMenuExpanded,
                    onExpandedChange = { morphMenuExpanded = it },
                    onComputer = {
                        val topic = state.page.removePrefix("chat/")
                        model.navigate(if (isConversation && model.agentFor(topic).isNotBlank()) "select-device/$topic" else "devices")
                    },
                    onNewTask = { model.navigate("home") },
                    onCloudFiles = { model.navigate(if (isConversation) "topic-files/${state.page.removePrefix("chat/")}" else "files") },
                    onRefresh = { model.retryCurrent() },
                )
            }
          }
          }
          }
        }
    }
    state.agentSettings?.let { details ->
        state.workspace.employees.find { it.id == details.agentId }?.let { employee ->
            AgentSettingsSheet(employee, state, model, agentSheet)
        }
    }
    if (dialog == "search") {
        ModalBottomSheet(onDismissRequest = { dialog = null }, shape = CatsCoMobileSpec.sheetShape, containerColor = MaterialTheme.colorScheme.background) {
            when (dialog) {
                "search" -> {
                    var query by remember { mutableStateOf("") }
                    CatsCoSidebarSearchField(query, { query = it }, rememberHazeState(), Modifier.fillMaxWidth().padding(horizontal = 16.dp), placeholder = "搜索聊天记录")
                    visibleConversations.filter { it.name.contains(query) }.forEach { chat -> Entry(chat.name, R.drawable.e_chat) { model.navigate("chat/${chat.id}"); dialog = null; scope.launch { drawer.close() } } }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    } else if (dialog == "retry-task") {
        CatsCoAlertDialog(onDismissRequest = { dialog = null }, title = { Text("重新创建任务？") }, text = { Text("上次创建结果尚未确认。请先检查历史列表，重新创建可能出现两个任务。") },
            confirmButton = { CatsCoSecondaryButton(onClick = { model.acknowledgeTaskCreation(); dialog = null }) { Text("重新创建") } },
            dismissButton = { CatsCoSecondaryButton(onClick = { dialog = null; model.refresh() }) { Text("先刷新历史") } })
    } else dialog?.let { message -> CatsCoAlertDialog(onDismissRequest = { dialog = null }, title = { Text(if (message == "logout") "退出登录？" else "CatsCo") },
        text = { Text(if (message == "logout") "会清除本机登录凭证和当前输入内容。" else message) },
        confirmButton = { CatsCoSecondaryButton(onClick = { if (message == "logout") model.logout(); dialog = null }) { Text("确定") } },
        dismissButton = if (message == "logout") { { CatsCoSecondaryButton(onClick = { dialog = null }) { Text("取消") } } } else null) }

    renameTarget?.let { conversation ->
        CatsCoAlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名会话") },
            text = {
                ModuleTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it.take(80) },
                    singleLine = true,
                    label = { Text("会话名称") },
                )
            },
            confirmButton = {
                CatsCoSecondaryButton(
                    enabled = renameDraft.isNotBlank() && renameDraft.trim() != conversation.name,
                    onClick = { model.renameConversation(conversation.id, renameDraft.trim()); renameTarget = null },
                ) { Text("保存") }
            },
            dismissButton = { CatsCoSecondaryButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }
    if (renameDraftTitleOpen) {
        CatsCoAlertDialog(
            onDismissRequest = { renameDraftTitleOpen = false },
            title = { Text("重命名任务") },
            text = {
                ModuleTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it.take(80) },
                    singleLine = true,
                    label = { Text("任务名称") },
                )
            },
            confirmButton = {
                CatsCoSecondaryButton(
                    enabled = renameDraft.isNotBlank() && renameDraft.trim() != draftTaskTitle,
                    onClick = { draftTaskTitle = renameDraft.trim(); renameDraftTitleOpen = false },
                ) { Text("保存") }
            },
            dismissButton = { CatsCoSecondaryButton(onClick = { renameDraftTitleOpen = false }) { Text("取消") } },
        )
    }
    deleteTargets?.let { targets ->
        CatsCoAlertDialog(
            onDismissRequest = { deleteTargets = null },
            title = { Text(if (targets.size == 1) "删除会话？" else "删除 ${targets.size} 个会话？") },
            text = { Text("这些会话将从此设备的历史列表中隐藏，服务器消息不会删除。") },
            confirmButton = {
                CatsCoSecondaryButton(onClick = {
                    hiddenConversationIds = hiddenConversationIds + targets.map { it.id }
                    sidebarPrefs.edit().putStringSet("hidden", hiddenConversationIds.toSet()).apply()
                    if (state.page.removePrefix("chat/") in targets.map { it.id }) model.navigate("home")
                    selectedConversationIds = emptySet()
                    deleteTargets = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { CatsCoSecondaryButton(onClick = { deleteTargets = null }) { Text("取消") } },
        )
    }
}

@Composable private fun Login(state: WorkspaceState, model: WorkspaceViewModel) {
    CatsCoAuthScreen(
        state = state,
        initialServer = model.serverAddress,
        onDismissFeedback = model::dismissError,
        onLogin = model::login,
        onRegister = model::register,
        onResetPassword = model::resetPassword,
        onSendCode = model::sendCode,
        onPreview = if (DebugEntry.available) ({ model.enterPreview() }) else null,
    )
}

@Composable fun TaskHome(state: WorkspaceState, onEmployeeSelected: (String) -> Unit, modifier: Modifier = Modifier,
    onEmployeeSettings: (String) -> Unit = {}, imageLoader: coil.ImageLoader? = null, onPickerVisibility: (Boolean) -> Unit = {}) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val availableHeight = maxHeight
        val compact = availableHeight < 260.dp
        val upwardOffset = if (compact) 0.dp else availableHeight * .11f
        Column(
            Modifier.offset(y = -upwardOffset).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
        ) {
            CatsCoHomeAgent(state.workspace.employees, state.employeeId, onEmployeeSelected,
                onSettings = onEmployeeSettings, session = state.session, imageLoader = imageLoader,
                compact = compact, onPickerVisibility = onPickerVisibility)
            if (availableHeight >= 180.dp) CatsCoHomeGreeting(state.session?.name.orEmpty(), Modifier.padding(bottom = 12.dp))
        }
    }
}

@Composable internal fun Composer(state: WorkspaceState, model: WorkspaceViewModel, onAttachmentSelected: (String) -> Unit, onVoice: () -> Unit, onSend: () -> Unit) {
    var attachmentsExpanded by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = attachmentsExpanded) { attachmentsExpanded = false }
    val inputFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val canSend = (state.draft.isNotBlank() || state.attachments.isNotEmpty()) && !state.busy && !state.sending &&
        !state.uploading && !state.preview && (state.page != "home" || state.employeeId.isNotBlank())
    val submit = {
        if (canSend && !state.conversationRunning) {
            keyboard?.hide()
            focusManager.clearFocus(force = true)
            attachmentsExpanded = false
            onSend()
        }
    }
    LaunchedEffect(state.quote?.messageId) {
        if (state.quote != null) {
            inputFocus.requestFocus()
            keyboard?.show()
        }
    }
    val attachmentIconRotation by animateFloatAsState(
        targetValue = if (attachmentsExpanded) 45f else 0f,
        animationSpec = spring(dampingRatio = 1.12f, stiffness = 420f),
        label = "attachmentButtonRotation",
    )
    Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 10.dp, vertical = 12.dp)) {
        CatsCoComposerFrame {
            Column {
                state.quote?.let { quote ->
                    Box(Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 8.dp)) {
                        MessageQuotePreview(quote, model::clearQuote)
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                }
                if (state.attachments.isNotEmpty()) ComposerAttachments(
                    state.attachments, state.session, model.accountImages, previewEnabled = !state.busy,
                    onPreview = model::openAttachment, onRemove = model::removeAttachment,
                )
                if (state.uploading) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.padding(horizontal = 8.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconButton(onClick = { attachmentsExpanded = !attachmentsExpanded }, modifier = Modifier.size(48.dp)) {
                        Icon(
                            painterResource(R.drawable.e_add),
                            if (attachmentsExpanded) "收起附件" else "添加附件",
                            Modifier.size(CatsCoMobileSpec.actionIconSize).graphicsLayer { rotationZ = attachmentIconRotation },
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    BasicTextField(state.draft, model::draft, modifier = Modifier.weight(1f).focusRequester(inputFocus).heightIn(min = 48.dp).padding(vertical = 11.dp), maxLines = 6,
                        keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { submit() }),
                        textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Mint), decorationBox = { inner ->
                            if (state.draft.isEmpty()) Text(if (state.page == "home") "描述你的任务" else "发送消息", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                            inner()
                        })
                    ComposerSendButton(
                        running = state.conversationRunning && !state.preview,
                        stopping = state.page.startsWith("chat/") && state.stoppingTopic == state.page.removePrefix("chat/"),
                        sendEnabled = canSend,
                        sending = state.sending,
                        emphasized = state.draft.isNotBlank(),
                        voiceMode = state.draft.isBlank() && state.attachments.isEmpty() && !state.sending,
                        voiceEnabled = !state.busy && !state.uploading && !state.preview,
                        onVoice = onVoice,
                        onSend = submit,
                        onStop = model::stop,
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = attachmentsExpanded,
            enter = expandVertically(expandFrom = Alignment.Top, animationSpec = spring(dampingRatio = 1.12f, stiffness = 420f)) + fadeIn(spring(dampingRatio = 1.12f, stiffness = 420f)),
            exit = shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = spring(dampingRatio = 1.12f, stiffness = 420f)) + fadeOut(spring(dampingRatio = 1.12f, stiffness = 420f)),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("拍照" to R.drawable.e_attachment_camera, "照片" to R.drawable.e_attachment_photo, "文件" to R.drawable.e_attachment_folder).forEach { (label, icon) ->
                    Column(
                        Modifier.weight(1f).heightIn(min = 112.dp)
                            .clickable { attachmentsExpanded = false; onAttachmentSelected(label) }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Box(
                            Modifier.size(64.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(painterResource(icon), null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        if (state.preview) Text("调试示例 · 不会发送请求", Modifier.fillMaxWidth().padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun HistoryEntry(
    conversation: Conversation,
    pinned: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onSelectMore: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember(conversation.id) { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = CatsCoMobileSpec.historyRowHeight)
                .combinedClickable(onClick = onClick, onLongClick = { if (!selectionMode) menuExpanded = true else onLongPress() })
                .padding(
                    start = CatsCoMobileSpec.sidebarHistoryInset,
                    end = CatsCoMobileSpec.sidebarContentInset,
                    top = 5.dp,
                    bottom = 5.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CatsCoMobileSpec.sidebarLabelGap),
        ) {
            Text(
                conversation.name,
                modifier = Modifier.weight(1f),
                fontSize = CatsCoMobileSpec.historyText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (conversation.active) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Mint, strokeWidth = 2.dp)
            }
            if (selectionMode) {
                Checkbox(selected, { onClick() }, modifier = Modifier.size(CatsCoMobileSpec.sidebarLeadingSize))
            }
        }
        CatsCoConversationActionMenu(
            expanded = menuExpanded,
            pinned = pinned,
            onDismiss = { menuExpanded = false },
            onRename = onRename,
            onTogglePin = onTogglePin,
            onSelectMore = onSelectMore,
            onDelete = onDelete,
        )
    }
}

@Composable private fun Entry(title: String, icon: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = CatsCoMobileSpec.minimumTouchTarget).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(icon), null, Modifier.size(CatsCoMobileSpec.actionIconSize), tint = MaterialTheme.colorScheme.onSurface)
        Text(title, Modifier.weight(1f), fontSize = CatsCoMobileSpec.bodyText)
    }
}
@Preview(name = "CatsCo home", showBackground = true)
@Composable private fun HomePreview() {
    CatsCoTheme {
        Surface {
            TaskHome(
                WorkspaceState(workspace = Workspace(employees = listOf(Employee("preview-agent", "CatsCo 助手", true)))),
                {},
                Modifier.height(600.dp),
            )
        }
    }
}
