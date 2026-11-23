package cc.catsco.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import cc.catsco.android.*
import cc.catsco.android.R
import cc.catsco.android.data.VoiceDestination
import cc.catsco.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

@Composable
internal fun workspaceBackdropBlur(drawer: DrawerState, drawerWidth: Int, agentBlur: State<Dp>): Modifier {
    if (android.os.Build.VERSION.SDK_INT < 31) return Modifier.clipToBounds()
    val blurred = rememberGraphicsLayer()
    val sampleScale = .35f
    val radius = with(LocalDensity.current) { 5.dp.toPx() * sampleScale }
    val effect = remember(radius) { BlurEffect(radius, radius, TileMode.Clamp) }
    val drawerActive by remember(drawer, drawerWidth) { derivedStateOf {
        drawerWidth > 0 && drawer.currentOffset.isFinite() && drawer.currentOffset > -drawerWidth + .5f
    } }
    // The page records a fixed-size, low-resolution backdrop when its content changes.
    // While the drawer moves, only the overlay opacity changes.
    return Modifier.clipToBounds().graphicsLayer {
        val agentRadius = agentBlur.value.toPx()
        renderEffect = if (agentRadius > .01f) BlurEffect(agentRadius, agentRadius, TileMode.Clamp) else null
    }.drawWithContent {
        drawContent()
        if (drawerActive) {
            blurred.alpha = (1f + drawer.currentOffset / drawerWidth).coerceIn(0f, 1f)
            scale(1f / sampleScale, 1f / sampleScale, pivot = Offset.Zero) { drawLayer(blurred) }
        }
    }.graphicsLayer().drawWithContent {
        drawContent()
        if (drawerActive) {
            val dimensions = IntSize((size.width * sampleScale).roundToInt().coerceAtLeast(1),
                (size.height * sampleScale).roundToInt().coerceAtLeast(1))
            blurred.record(size = dimensions) {
                scale(sampleScale, sampleScale, pivot = Offset.Zero) { this@drawWithContent.drawContent() }
            }
            blurred.renderEffect = effect
        }
    }
}

@Composable fun Brand(width: Int = 88, wordmark: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Image(painterResource(R.drawable.catsco_mark), "CatsCo", Modifier.width(width.dp).height((width * .46f).dp), colorFilter = ColorFilter.tint(Mint))
        if (wordmark) CatsCoWordmark(Modifier.width(88.dp).height(22.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable fun CatsCoApp(
    state: WorkspaceState,
    model: WorkspaceViewModel,
    composerNotice: (@Composable () -> Unit)? = null,
    notificationUnreadCount: Int? = null,
    showNotificationBadge: Boolean = false,
    onNotifications: (() -> Unit)? = null,
    notificationBanner: (@Composable BoxScope.() -> Unit)? = null,
) {
    val checkForUpdate = rememberPreviewUpdateAction(state.session != null && !state.preview && !state.busy)
    val drawer = rememberDrawerState(DrawerValue.Closed)
    var projectStripTouchActive by remember { mutableStateOf(false) }
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
    var homeAgentPressActive by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val imeTarget = WindowInsets.imeAnimationTarget
    val imeVisible = WindowInsets.isImeVisible
    val navigationInsets = WindowInsets.navigationBars
    val homeBottomInsets = remember(navigationInsets) {
        navigationInsets.only(WindowInsetsSides.Bottom)
    }
    val imeHasSpace by remember(imeInsets, imeTarget, density) {
        derivedStateOf { imeInsets.getBottom(density) > 0 || imeTarget.getBottom(density) > 0 }
    }
    // Focus arrives before the IME publishes its first insets.
    var composerFocusRequest by remember { mutableIntStateOf(0) }
    var settledComposerFocus by remember { mutableIntStateOf(0) }
    LaunchedEffect(composerFocusRequest) {
        if (composerFocusRequest != settledComposerFocus) {
            delay(400)
            settledComposerFocus = composerFocusRequest
        }
    }
    val composerKeyboardActive = imeVisible || imeHasSpace || composerFocusRequest != settledComposerFocus
    val pageStates = key(state.session?.server, state.session?.userId, state.localDataEpoch) { rememberSaveableStateHolder() }
    val agentSheet = key(state.agentSettings != null) {
        rememberModalBottomSheetState(skipPartiallyExpanded = false)
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val wallpaper = remember(context.applicationContext) { ConversationWallpaperStore(context.applicationContext) }
    val bubblePreferences = remember(context.applicationContext) {
        context.applicationContext.getSharedPreferences("conversation_bubble_tones", android.content.Context.MODE_PRIVATE)
    }
    var bubbleTones by remember(bubblePreferences) {
        mutableStateOf(ConversationBubbleTones(
            user = BubbleTone.fromId(bubblePreferences.getString("user", null)),
            agent = BubbleTone.fromId(bubblePreferences.getString("agent", null)),
        ))
    }
    val wallpaperContrast = rememberWallpaperContrast(wallpaper)
    val media = rememberMediaActions(state, model)
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
    val sidebarServer = state.session?.server?.value.orEmpty()
    val projectPreferenceKey = "project-pins:$sidebarServer"
    val pinKey = "pinned:$sidebarServer"
    val hiddenKey = "hidden:$sidebarServer"
    val sidebarPrefs = remember(context, userId, sidebarServer, state.preview) {
        val prefs = context.getSharedPreferences("sidebar_$userId", android.content.Context.MODE_PRIVATE)
        if (userId.isNotBlank() && sidebarServer.isNotBlank() && !state.preview) {
            val edit = prefs.edit()
            listOf("pinned" to pinKey, "hidden" to hiddenKey).forEach { (old, scoped) ->
                if (prefs.contains(old)) {
                    val merged = prefs.getStringSet(scoped, emptySet()).orEmpty() + prefs.getStringSet(old, emptySet()).orEmpty()
                    edit.putStringSet(scoped, merged).remove(old)
                }
            }
            edit.apply()
        }
        prefs
    }
    var pinnedProjects by remember(sidebarPrefs, projectPreferenceKey) {
        mutableStateOf(sidebarPrefs.getStringSet(projectPreferenceKey, emptySet()).orEmpty().toSet())
    }
    var projectFilter by rememberSaveable(userId, projectPreferenceKey) { mutableStateOf("") }
    var showProjects by rememberSaveable(userId, projectPreferenceKey) { mutableStateOf(false) }
    var movingTaskId by rememberSaveable(userId, projectPreferenceKey) { mutableStateOf("") }
    val projectNames = remember(state.workspace.projects) { state.workspace.projects.associate { it.id to it.name } }
    LaunchedEffect(state.projectsLoaded, projectNames) {
        if (state.projectsLoaded) {
            val available = pinnedProjects.intersect(projectNames.keys)
            if (available != pinnedProjects) {
                pinnedProjects = available
                sidebarPrefs.edit().putStringSet(projectPreferenceKey, available).apply()
            }
            if (projectFilter !in available) projectFilter = ""
        }
    }
    var pinnedConversationIds by remember(sidebarPrefs, pinKey) { mutableStateOf(sidebarPrefs.getStringSet(pinKey, emptySet()).orEmpty().toSet()) }
    var hiddenConversationIds by remember(sidebarPrefs, hiddenKey) { mutableStateOf(sidebarPrefs.getStringSet(hiddenKey, emptySet()).orEmpty().toSet()) }
    var selectedConversationIds by remember(sidebarPrefs, hiddenKey) { mutableStateOf(emptySet<String>()) }
    var showHiddenTasks by remember(sidebarPrefs, hiddenKey) { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var deleteTargets by remember { mutableStateOf<List<Conversation>?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var headerMenuExpanded by remember { mutableStateOf(false) }
    val headerMenuTransition = updateTransition(headerMenuExpanded, label = "headerActionMenu")
    val isConversation = state.page.startsWith("chat/")
    val libraryPage = state.page == "apps" || state.page == "skills"
    val libraryContent = libraryPage || state.page.startsWith("agent-apps/")
    val taskOutputs = state.page.startsWith("task-outputs/")
    val sidebarPage = state.page == "home" || libraryPage || isConversation
    val showTaskMenu = state.page == "home" || isConversation
    val headerMenuVisible = showTaskMenu && (headerMenuExpanded || headerMenuTransition.currentState || headerMenuTransition.isRunning)
    LaunchedEffect(state.page) { headerMenuExpanded = false }
    var historyQuery by rememberSaveable(userId) { mutableStateOf("") }
    LaunchedEffect(state.agentSettings?.agentId, state.workspace.employees.map { it.id }) {
        val id = state.agentSettings?.agentId
        if (id != null && state.workspace.employees.none { it.id == id }) model.dismissAgentSettings()
    }
    val draftTaskTitle = state.newTaskTitle.ifBlank { "新任务" }
    val voiceTargets = remember { VoiceDropTargets() }
    LaunchedEffect(state.voiceCapture == null) {
        if (state.voiceCapture == null) voiceTargets.reset()
    }
    val startVoice = {
        val started = media.voice(if (state.page == "home") draftTaskTitle else "")
        if (started) {
            keyboard?.hide()
            focusManager.clearFocus(force = true)
        }
        started
    }
    var renameDraftTitleOpen by remember { mutableStateOf(false) }
    var homeComposerFrameHeight by remember { mutableIntStateOf(0) }
    var homeTrayExpanded by remember { mutableStateOf(false) }
    val conversations = state.workspace.conversations
    val visibleConversations = remember(conversations, hiddenConversationIds, pinnedConversationIds) {
        conversations.filterNot { it.id in hiddenConversationIds }.sortedByDescending { it.id in pinnedConversationIds }
    }
    val historyZone = java.time.ZoneId.systemDefault()
    val historyDay = java.time.LocalDate.now(historyZone)
    val historySections = remember(visibleConversations, historyQuery, historyZone, historyDay, projectFilter) {
        val query = historyQuery.trim()
        visibleConversations.asSequence()
            .filter { (projectFilter.isBlank() || (it.task && it.projectId == projectFilter)) && it.name.contains(query, ignoreCase = true) }
            .groupBy { historySection(it.date, historyZone, historyDay) }
            .toSortedMap()
    }
    LaunchedEffect(historySections) {
        if (selectedConversationIds.isNotEmpty()) selectedConversationIds = selectedConversationIds.intersect(
            historySections.values.flatten().mapTo(mutableSetOf()) { it.id })
    }
    val titles = mapOf("home" to "新任务", "skills" to "SkillHub", "apps" to "应用", "settings" to "设置", "wallpaper" to "会话壁纸")
    val drawerVisible by remember(drawer) { derivedStateOf {
        drawer.isOpen || drawer.targetValue == DrawerValue.Open ||
            (drawerWidth > 0 && drawer.currentOffset.isFinite() && drawer.currentOffset > -drawerWidth + .5f)
    } }
    var projectRefreshAt by remember(userId, sidebarServer) { mutableLongStateOf(0) }
    LaunchedEffect(drawer.currentValue, userId, sidebarServer) {
        if (drawer.currentValue == DrawerValue.Open) {
            if (System.currentTimeMillis() - state.lastSyncedAt >= 3_000) model.refreshConversations()
            val now = android.os.SystemClock.elapsedRealtime()
            if (!state.projectsLoaded || now - projectRefreshAt >= 10_000) {
                projectRefreshAt = now
                model.loadProjects()
            }
        }
    }
    BackHandler(enabled = state.session != null && (headerMenuExpanded || drawerVisible || state.page != "home")) {
        when {
            headerMenuExpanded -> headerMenuExpanded = false
            drawerVisible -> scope.launch { drawer.close() }
            else -> model.back()
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (state.session == null) {
            Login(state, model)
        } else Box(Modifier.fillMaxSize()) {
          val voiceBackdrop = rememberHazeState()
          val voiceVisibility = remember { MutableTransitionState(false) }
          voiceVisibility.targetState = state.voiceCapture != null
          val voiceOverlayShowing = voiceVisibility.currentState || voiceVisibility.targetState ||
              !voiceVisibility.isIdle
          ModalNavigationDrawer(drawerState = drawer,
              modifier = if (voiceOverlayShowing) Modifier.hazeSource(voiceBackdrop) else Modifier,
              gesturesEnabled = !projectStripTouchActive && state.voiceCapture == null && !state.busy && (sidebarPage || drawerVisible), drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.widthIn(max = 340.dp).fillMaxWidth(.86f).onSizeChanged { drawerWidth = it.width },
                drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                drawerContentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = CatsCoMobileSpec.sidebarGutter), verticalAlignment = Alignment.CenterVertically) {
                    CatsCoWordmark(Modifier.offset(x = 3.dp).width(88.dp).height(22.dp))
                    Spacer(Modifier.weight(1f))
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
                        val actionTarget = Modifier.size(width = 40.dp, height = CatsCoMobileSpec.minimumTouchTarget)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!state.preview || onNotifications != null) {
                                CatsCoBoundedIconButton(onClick = { showProjects = true }, modifier = actionTarget, pressOpacity = .028f) {
                                    Icon(painterResource(R.drawable.ic_project_outline), "项目", Modifier.size(CatsCoMobileSpec.actionIconSize))
                                }
                            }
                            CatsCoBoundedIconButton(onClick = { scope.launch { drawer.close() } }, modifier = actionTarget) {
                                Icon(painterResource(R.drawable.e_sidebar_active), "关闭侧栏", Modifier.size(CatsCoMobileSpec.actionIconSize))
                            }
                        }
                    }
                }
                val sidebarBackdrop = rememberHazeState()
                val historyList = rememberLazyListState()
                val historyOverlapsFields by remember(historyList) { derivedStateOf {
                    historyList.firstVisibleItemIndex > 0 || historyList.firstVisibleItemScrollOffset > 0
                } }
                val sidebarRefresh = rememberCatsCoPullRefresh(drawerVisible, state.refreshingConversations) {
                    model.refreshConversations(manual = true)
                }
                LaunchedEffect(projectFilter) { historyList.scrollToItem(0) }
                val density = LocalDensity.current
                var sidebarHeaderHeight by remember(density) { mutableStateOf(162.dp) }
                val sidebarProjects = if (state.preview) emptyList() else state.workspace.projects.filter { it.id in pinnedProjects }
                val sidebarHeader: @Composable (Modifier) -> Unit = { headerModifier ->
                    Surface(modifier = headerModifier, color = MaterialTheme.colorScheme.surfaceContainerLow, contentColor = MaterialTheme.colorScheme.onSurface) {
                        Column(
                            Modifier.fillMaxWidth()
                                .onSizeChanged { sidebarHeaderHeight = with(density) { it.height.toDp() } },
                        ) {
                            CompositionLocalProvider(LocalSidebarHistoryBackdrop provides historyOverlapsFields) {
                            CatsCoSidebarSearchField(
                                query = historyQuery,
                                onQueryChange = { historyQuery = it },
                                backdrop = sidebarBackdrop,
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp),
                            )
                            if (sidebarProjects.isNotEmpty()) {
                                ProjectFilters(sidebarProjects, projectFilter, sidebarBackdrop, onTouchActive = { projectStripTouchActive = it }) { id ->
                                    projectFilter = if (projectFilter == id) "" else id
                                    selectedConversationIds = emptySet()
                                }
                                Spacer(Modifier.height(12.dp))
                            }
                            }
                            CatsCoSidebarShortcut(R.drawable.e_new_task, "新建任务") {
                                model.startProjectTask(projectFilter)
                                scope.launch { drawer.close() }
                            }
                            CatsCoSidebarShortcut(R.drawable.e_apps, "应用") {
                                model.navigate("apps")
                                scope.launch { drawer.close() }
                            }
                        }
                    }
                }
                BoxWithConstraints(
                    Modifier.weight(1f).clipToBounds().padding(horizontal = CatsCoMobileSpec.sidebarGutter)
                        .catsCoPullRefresh(sidebarRefresh, enabled = drawerVisible),
                ) {
                    val scrollSidebarHeader = maxHeight < sidebarHeaderHeight + CatsCoMobileSpec.historyRowHeight * 3
                    // Short windows scroll navigation with history so the fixed header cannot cover every task.
                    LazyColumn(
                        state = historyList,
                        modifier = Modifier.fillMaxSize()
                            .then(if (scrollSidebarHeader) Modifier else Modifier.catsCoRefreshTranslation(sidebarRefresh))
                            .then(if (drawerVisible && historyOverlapsFields && !scrollSidebarHeader) Modifier.hazeSource(sidebarBackdrop) else Modifier)
                            .testTag("sidebar-history"),
                        contentPadding = PaddingValues(top = if (scrollSidebarHeader) 0.dp else sidebarHeaderHeight + 2.dp, bottom = 8.dp),
                    ) {
                        if (scrollSidebarHeader) {
                            item(key = "sidebar-navigation") { sidebarHeader(Modifier.padding(bottom = 2.dp)) }
                            item(key = "sidebar-refresh") {
                                if (sidebarRefresh.indicatorVisible) {
                                    CatsCoRefreshIndicator(sidebarRefresh, "sidebar-refresh-indicator", Modifier.layout { measurable, constraints ->
                                        val indicator = measurable.measure(constraints)
                                        val visibleHeight = (indicator.height * sidebarRefresh.progress).roundToInt()
                                        layout(indicator.width, visibleHeight) {
                                            indicator.place(0, (visibleHeight - indicator.height) / 2)
                                        }
                                    }.clipToBounds())
                                }
                            }
                        }
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
                                TextButton(onClick = { deleteTargets = historySections.values.flatten().filter { it.id in selectedConversationIds } }) {
                                    Text("隐藏")
                                }
                                TextButton(onClick = { selectedConversationIds = emptySet() }) { Text("完成") }
                            }
                        }
                        if (historySections.isEmpty()) item(key = "history-search-empty") {
                            Text(
                                when {
                                    historyQuery.isNotBlank() -> "没有找到匹配的任务"
                                    projectFilter.isNotBlank() -> "此项目还没有任务"
                                    else -> "还没有历史任务"
                                },
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
                        historySections.forEach { (section, conversations) ->
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
                                        sidebarPrefs.edit().putStringSet(pinKey, pinnedConversationIds).apply()
                                    },
                                    onSelectMore = { selectedConversationIds = setOf(chat.id) },
                                    onDelete = { deleteTargets = listOf(chat) },
                                    onMoveToProject = if (chat.task && !state.preview) ({ movingTaskId = chat.id }) else null,
                                )
                            }
                        }
                    }
                    if (!scrollSidebarHeader) {
                        CatsCoRefreshSlot(sidebarRefresh, "sidebar-refresh-indicator", sidebarHeaderHeight)
                        sidebarHeader(Modifier)
                    }
                }
                CatsCoAccountFooter(state.session, model.accountImages) {
                    model.navigate("settings")
                    scope.launch { drawer.close() }
                }
                if (state.preview) Text("调试示例 · 不会发送请求", Modifier.padding(16.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, scrimColor = Color.Black.copy(alpha = if (darkBackground) .12f else .16f)) {
            val agentOverlayVisible = homeAgentPickerVisible || state.agentSettings != null
            // Home overlays already dim the background through their own window or sheet scrim.
            val agentBlurVisible = state.page != "home" && state.agentSettings != null && agentSheet.targetValue != SheetValue.Hidden
            val agentBlur = androidx.compose.animation.core.animateDpAsState(
                targetValue = if (agentBlurVisible) 3.dp else 0.dp,
                animationSpec = androidx.compose.animation.core.tween(if (agentBlurVisible) 120 else 80),
                label = "agentSettingsContentBlur",
            )
            val agentBlurSettled by remember(agentBlur) { derivedStateOf { agentBlur.value == 0.dp } }
            val headerBackdrop = rememberHazeState()
            key(state.session?.server, state.session?.userId, state.localDataEpoch, state.appearance) {
            SwipeBackPage(state.page, model.backDestination,
                enabled = !sidebarPage && !drawerVisible && !headerMenuVisible && !agentOverlayVisible && !voiceOverlayShowing,
                onBack = model::back,
                inSettings = model.inSettingsFlow,
                homeTrayExpanded = homeTrayExpanded,
                contentReady = !state.resource.loading || state.resource.loaded,
                captureEnabled = !drawerVisible && !headerMenuVisible && !agentOverlayVisible && !homeAgentPressActive &&
                    !composerKeyboardActive && !voiceOverlayShowing && agentBlurSettled && !state.sending && !state.conversationRunning &&
                    state.page.removePrefix("chat/") !in state.awaitingReplyAfter,
                modifier = workspaceBackdropBlur(drawer, drawerWidth, agentBlur),
            ) {
            val showWallpaper = showTaskMenu && wallpaper.revision != null
            val hasHeaderBackdrop = state.page != "home" && !libraryContent
            var messagesBehindHeader by remember(state.page) { mutableStateOf(false) }
            val backgroundInk = wallpaperContrast.takeIf { showWallpaper }
            WallpaperSystemBars(backgroundInk.takeIf { !drawerVisible && !agentOverlayVisible && !messagesBehindHeader && !composerKeyboardActive })
            val headerInk = if (backgroundInk == null || messagesBehindHeader) MaterialTheme.colorScheme.onSurface
                else if (backgroundInk.headerDarkInk) Color.Black else Color.White
            if (showWallpaper) {
                ConversationWallpaperBackdrop(wallpaper, composerKeyboardActive, Modifier.matchParentSize()
                    .then(if (hasHeaderBackdrop) Modifier.hazeSource(headerBackdrop, zIndex = -1f) else Modifier))
            }
            Scaffold(Modifier.fillMaxSize(),
                containerColor = if (showWallpaper) Color.Transparent else MaterialTheme.colorScheme.background, topBar = {
                Box {
                    if (hasHeaderBackdrop && isConversation) {
                        CatsCoConversationHeaderGlass(headerBackdrop, messagesBehindHeader, Modifier.matchParentSize(), showWallpaper)
                    } else if (hasHeaderBackdrop) {
                        CatsCoHeaderGlass(headerBackdrop, Modifier.matchParentSize(), overWallpaper = showWallpaper)
                    }
                    Row(Modifier.fillMaxWidth().statusBarsPadding().height(CatsCoMobileSpec.headerHeight).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (state.page == "settings") {
                            Spacer(Modifier.width(CatsCoMobileSpec.headerButtonTarget))
                            Text("设置", Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                            CatsCoHeaderAction(R.drawable.e_close, "关闭设置") { model.navigate("home") }
                        } else {
                            if (sidebarPage) {
                                CompositionLocalProvider(LocalContentColor provides headerInk) {
                                    CatsCoHeaderAction(R.drawable.e_sidebar, "打开侧栏", decorated = false) { scope.launch { drawer.open() } }
                                }
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
                            val titleInteraction = remember { MutableInteractionSource() }
                            Box(
                                modifier = Modifier.weight(1f).then(
                                    if (canRenameTitle) Modifier.combinedClickable(
                                        interactionSource = titleInteraction,
                                        indication = null,
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
                                contentAlignment = if (sidebarPage) Alignment.CenterStart else Alignment.Center,
                            ) {
                                Text(
                                    title,
                                    modifier = if (canRenameTitle) Modifier
                                        .offset(x = if (sidebarPage) (-4).dp else 0.dp)
                                        .indication(titleInteraction, CatsCoPressFeedback(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(8.dp)))
                                        .padding(horizontal = 4.dp, vertical = 4.dp)
                                    else Modifier,
                                    color = headerInk,
                                    style = if (state.page == "home" || isConversation) {
                                        MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
                                    } else MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            when {
                                libraryPage -> CatsCoHeaderAction(R.drawable.e_close, "关闭应用") { model.back() }
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
                        onVoice = { startVoice() },
                        onSend = { model.send(if (state.page == "home") draftTaskTitle else "") },
                        onTrayChanged = if (state.page == "home") ({ homeTrayExpanded = it }) else null,
                        onFrameHeightChanged = if (state.page == "home") {
                            { height -> homeComposerFrameHeight = height }
                        } else null,
                        onInputFocused = { composerFocusRequest++ },
                        aboveInput = composerNotice,
                        projectName = if (state.page == "home" && state.newTaskProjectId.isNotBlank()) {
                            projectNames[state.newTaskProjectId] ?: "项目任务"
                        } else null,
                        voiceHold = VoiceHoldCallbacks(
                            start = { startVoice().also { voiceTargets.holding = it } },
                            move = { voiceTargets.selected = voiceTargets.hit(it) },
                            release = {
                                val destination = voiceTargets.hit(it) ?: VoiceDestination.Edit
                                voiceTargets.reset()
                                model.completeVoiceInput(destination)
                            },
                            cancel = { voiceTargets.reset(); model.discardVoiceInput() },
                        ),
                    )
                }
            }) { padding ->
                // The composer follows the IME. Keep the greeting anchored to the resting frame.
                val composerFrameHeight = with(LocalDensity.current) {
                    homeComposerFrameHeight.takeIf { it > 0 }?.toDp() ?: CatsCoMobileSpec.composerFrameMinHeight
                }
                val homeBodyPadding = Modifier
                    .padding(top = padding.calculateTopPadding(), bottom = composerFrameHeight + CatsCoMobileSpec.composerVerticalInset * 2)
                    .padding(homeBottomInsets.asPaddingValues())
                val bodyPadding = if (state.page == "home") homeBodyPadding else {
                    val contentPadding = remember(padding, isConversation) {
                        object : PaddingValues {
                            override fun calculateLeftPadding(layoutDirection: LayoutDirection) = 0.dp
                            override fun calculateRightPadding(layoutDirection: LayoutDirection) = 0.dp
                            override fun calculateTopPadding() = if (isConversation) 0.dp else padding.calculateTopPadding()
                            override fun calculateBottomPadding() = padding.calculateBottomPadding()
                        }
                    }
                    Modifier.padding(contentPadding)
                }
                val dismissKeyboardOnBlankTap = if (keyboard != null && showTaskMenu && (imeVisible || imeHasSpace) &&
                    !drawerVisible && !headerMenuVisible && !agentOverlayVisible) {
                    Modifier.pointerInput(keyboard, padding) {
                        awaitEachGesture {
                            // Observe after child controls and scroll gestures have handled the event.
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                            val inputTop = size.height - padding.calculateBottomPadding().toPx() +
                                CatsCoMobileSpec.composerVerticalInset.toPx()
                            if (down.isConsumed || down.position.y >= inputTop) return@awaitEachGesture
                            while (true) {
                                val touch = awaitPointerEvent(PointerEventPass.Final).changes.singleOrNull() ?: break
                                if (touch.id != down.id || touch.isConsumed ||
                                    (touch.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                                if (!touch.pressed) {
                                    if (touch.changedToUp() && touch.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                                        keyboard.hide()
                                    }
                                    break
                                }
                            }
                        }
                    }
                } else Modifier
                Box(Modifier.fillMaxSize().then(dismissKeyboardOnBlankTap)) {
                val pendingHomeSend = state.pendingSend?.takeIf { state.page == "home" && it.page == "home" }
                if (state.page == "home" || isConversation) {
                    AnimatedVisibility(
                        visible = state.page == "home" && pendingHomeSend == null,
                        modifier = Modifier.matchParentSize().then(homeBodyPadding),
                        enter = fadeIn(tween(140, easing = LinearOutSlowInEasing)),
                        exit = fadeOut(tween(150, easing = FastOutLinearInEasing)),
                    ) {
                        pageStates.SaveableStateProvider("home-welcome") {
                            val canRefresh = state.page == "home" && !drawerVisible && !headerMenuVisible &&
                                !agentOverlayVisible && !voiceOverlayShowing && !state.busy &&
                                !composerKeyboardActive && pendingHomeSend == null
                            val homeRefresh = rememberCatsCoPullRefresh(state.page == "home" && !drawerVisible, state.refreshingConversations) {
                                model.refreshConversations(manual = true)
                            }
                            BoxWithConstraints(
                                Modifier.fillMaxSize().clipToBounds().catsCoPullRefresh(homeRefresh, enabled = canRefresh),
                            ) {
                                TaskHome(
                                    state,
                                    model::selectEmployee,
                                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState(), enabled = canRefresh)
                                        .height(maxHeight).catsCoRefreshTranslation(homeRefresh).testTag("home-refresh-surface"),
                                    foreground = backgroundInk?.let { if (it.centerDarkInk) Color.Black else Color.White },
                                    onEmployeeSettings = model::showAgentSettings,
                                    imageLoader = model.accountImages,
                                    onPickerVisibility = { homeAgentPickerVisible = it },
                                    onPressActive = { homeAgentPressActive = it },
                                )
                                CatsCoRefreshSlot(homeRefresh, "home-refresh-indicator")
                            }
                        }
                    }
                }
                if (pendingHomeSend != null) {
                    Box(Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp)
                        .padding(top = padding.calculateTopPadding() + 10.dp, bottom = padding.calculateBottomPadding())) {
                        CompositionLocalProvider(LocalConversationBubbleTones provides bubbleTones) {
                            MessageTurnContent(
                                pendingHomeSend.asTurn(userId), mine = true, senderName = "", active = false,
                                filesEnabled = false, onPreview = {}, onDownload = {},
                            )
                        }
                    }
                }
                Column(Modifier.fillMaxSize().then(bodyPadding)
                    .then(if (hasHeaderBackdrop) Modifier.hazeSource(headerBackdrop) else Modifier)) {
                    if (state.busy && state.stoppingTopic.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.notice?.let { notice -> TextButton(onClick = model::dismissError, modifier = Modifier.padding(horizontal = 8.dp)) { Text(notice, fontSize = 14.sp) } }
                    if (state.taskCreationUncertain && state.page == "home") {
                        TextButton(onClick = { dialog = "retry-task" }) { Text("检查历史任务后重新创建") }
                    }
                    if (libraryContent) {
                        CatsCoLibraryTabs(state.page == "skills", model::selectLibraryTab)
                    } else if (taskOutputs) {
                        // Keep the indicator composed while each tab restores its own list state.
                        TaskOutputsTabs(state.page.startsWith("task-outputs/apps/"), model::selectTaskOutputTab)
                    }
                    pageStates.SaveableStateProvider(state.page) {
                    when (state.page) {
                        "home" -> Unit
                        "wallpaper" -> WallpaperSettings(wallpaper)
                        "appearance" -> CompositionLocalProvider(LocalConversationBubbleTones provides bubbleTones) {
                            PersonalizationSettings(state.appearance, wallpaper, model::appearance,
                                onWallpaper = { model.navigate("wallpaper") },
                                onBubbleSelected = { mine, tone ->
                                    bubbleTones = if (mine) bubbleTones.copy(user = tone) else bubbleTones.copy(agent = tone)
                                    bubblePreferences.edit().putString(if (mine) "user" else "agent", tone.id).apply()
                                },
                            )
                        }
                        "settings" -> NativeSettings(state, model.accountImages, hasHiddenTasks = hiddenConversationIds.isNotEmpty()) { entry ->
                            when (entry) {
                                "logout" -> dialog = "logout"
                                "检查更新" -> checkForUpdate()
                                HiddenTasksSettingsLabel -> showHiddenTasks = true
                                "反馈" -> {
                                    val origin = cc.catsco.android.BuildConfig.FEEDBACK_ORIGIN
                                    if (origin.isEmpty()) {
                                        model.navigate("feedback")
                                    } else {
                                        val uri = android.net.Uri.parse(origin).buildUpon().appendPath("submit")
                                            .appendQueryParameter("platform", "App")
                                            .appendQueryParameter("version", cc.catsco.android.BuildConfig.VERSION_NAME).build()
                                        try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) }
                                        catch (_: android.content.ActivityNotFoundException) { model.navigate("feedback") }
                                    }
                                }
                                "打开 WebApp" -> state.session?.server?.value?.let(model::openUrl)
                                "appearance" -> model.navigate("appearance")
                                else -> model.navigate(mapOf("个人资料" to "profile", "套餐与权益" to "commercial", "消息通知" to "notifications", "实时活动与组件" to "surfaces", "我的电脑" to "devices", "云托管" to "cloud-workers", "联系人与群组" to "contacts", "离线与分享草稿" to "offline", "定制 Agent" to "service-agents", "员工管理" to "agents", "模型用量" to "usage", "模型访问凭证" to "relay-key", "服务器" to "server", "反馈" to "feedback")[entry] ?: "settings")
                            }
                        }
                        else -> if (isConversation) {
                            CompositionLocalProvider(LocalConversationBubbleTones provides bubbleTones) {
                                ConversationScreen(state, model, Modifier.weight(1f),
                                    onOpenSidebar = { if (!state.busy && drawer.isClosed && drawer.targetValue == DrawerValue.Closed) scope.launch { drawer.open() } },
                                    topContentPadding = padding.calculateTopPadding(),
                                    onMessagesBehindHeader = { messagesBehindHeader = it })
                            }
                        } else WorkspaceModuleScreen(state, model, historyQuery)
                    }
                    }
                }
                }
            }
            notificationBanner?.invoke(this)
            if (showTaskMenu) {
                CatsCoHeaderActionMenu(
                    expanded = headerMenuExpanded,
                    onExpandedChange = { headerMenuExpanded = it },
                    onComputer = {
                        val topic = state.page.removePrefix("chat/")
                        model.navigate(if (isConversation && model.agentFor(topic).isNotBlank()) "select-device/$topic" else "devices")
                    },
                    onNewTask = { model.startProjectTask() },
                    onCloudFiles = model::openTaskOutputs,
                    onRefresh = {
                        if (state.page == "home") model.refreshConversations(manual = true)
                        else model.retryCurrent()
                    },
                    transition = headerMenuTransition,
                    notificationUnreadCount = notificationUnreadCount,
                    showNotificationBadge = showNotificationBadge,
                    onNotifications = onNotifications,
                )
            }
          }
          }
          }
          AnimatedVisibility(
              visibleState = voiceVisibility,
              enter = fadeIn(tween(140, easing = LinearOutSlowInEasing)),
              exit = fadeOut(tween(110)),
          ) {
              var lastVoiceFrame by remember { mutableStateOf(state) }
              val active = state.voiceCapture != null
              if (active) SideEffect { lastVoiceFrame = state }
              val frame = if (active) state else lastVoiceFrame
              frame.voiceCapture?.let { phase ->
                  VoiceInputOverlay(phase, frame.voicePreview, frame.voiceLevel, frame.voiceLimit, frame.voiceWarning,
                      frame.canSendVoice && frame.voiceWarning == null, voiceTargets, model::completeVoiceInput, model::discardVoiceInput,
                      startedAt = frame.voiceStartedAt, stoppedAt = frame.voiceStoppedAt, backdrop = voiceBackdrop, interactive = active)
              }
          }
        }
    }
    state.agentSettings?.let { details ->
        state.workspace.employees.find { it.id == details.agentId }?.let { employee ->
            AgentSettingsSheet(employee, state, model, agentSheet)
        }
    }
    val movingTask = state.workspace.conversations.firstOrNull { it.id == movingTaskId }
    if (state.session != null && (showProjects || movingTask != null)) {
        val closeProjects = { showProjects = false; movingTaskId = "" }
        ProjectsSheet(state, model, pinnedProjects, visibleConversations,
            onTogglePin = { id ->
                pinnedProjects = if (id in pinnedProjects) pinnedProjects - id else pinnedProjects + id
                if (projectFilter == id && id !in pinnedProjects) projectFilter = ""
                sidebarPrefs.edit().putStringSet(projectPreferenceKey, pinnedProjects).apply()
            },
            onDismiss = closeProjects,
            onNewTask = { id ->
                closeProjects()
                model.startProjectTask(id)
                scope.launch { drawer.close() }
            },
            onOpenTask = { id ->
                closeProjects()
                model.navigate("chat/$id")
                scope.launch { drawer.close() }
            },
            movingTask = movingTask,
        )
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
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("会话名称") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            },
            confirmButton = {
                CatsCoButton(
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
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("任务名称") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            },
            confirmButton = {
                CatsCoButton(
                    enabled = renameDraft.isNotBlank() && renameDraft.trim() != draftTaskTitle,
                    onClick = { model.renameNewTask(renameDraft); renameDraftTitleOpen = false },
                ) { Text("保存") }
            },
            dismissButton = { CatsCoSecondaryButton(onClick = { renameDraftTitleOpen = false }) { Text("取消") } },
        )
    }
    if (showHiddenTasks) CatsCoAlertDialog(
        onDismissRequest = { showHiddenTasks = false },
        title = { Text(HiddenTasksSettingsLabel) },
        text = {
            val hidden = conversations.filter { it.id in hiddenConversationIds }
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(hidden, key = { it.id }) { task ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(task.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = {
                            hiddenConversationIds = hiddenConversationIds - task.id
                            sidebarPrefs.edit().putStringSet(hiddenKey, hiddenConversationIds).apply()
                            if (hiddenConversationIds.isEmpty()) showHiddenTasks = false
                        }) { Text("恢复") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showHiddenTasks = false }) { Text("完成") } },
        dismissButton = { TextButton(onClick = {
            hiddenConversationIds = emptySet()
            sidebarPrefs.edit().remove(hiddenKey).apply()
            showHiddenTasks = false
        }) { Text("全部恢复") } },
    )
    deleteTargets?.let { targets ->
        CatsCoAlertDialog(
            onDismissRequest = { deleteTargets = null },
            title = { Text(if (targets.size == 1) "在本机隐藏此任务？" else "在本机隐藏 ${targets.size} 个任务？") },
            text = { Text("可以在「设置 → 应用偏好 → $HiddenTasksSettingsLabel」中恢复。") },
            confirmButton = {
                CatsCoSecondaryButton(onClick = {
                    hiddenConversationIds = hiddenConversationIds + targets.map { it.id }
                    sidebarPrefs.edit().putStringSet(hiddenKey, hiddenConversationIds).apply()
                    if (state.page.removePrefix("chat/") in targets.map { it.id }) model.navigate("home")
                    selectedConversationIds = emptySet()
                    deleteTargets = null
                }) { Text("隐藏") }
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
    onEmployeeSettings: (String) -> Unit = {}, imageLoader: coil.ImageLoader? = null, onPickerVisibility: (Boolean) -> Unit = {},
    onPressActive: (Boolean) -> Unit = {}, foreground: Color? = null) {
    CompositionLocalProvider(LocalContentColor provides (foreground ?: LocalContentColor.current)) {
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
                    compact = compact, onPickerVisibility = onPickerVisibility, onPressActive = onPressActive)
                if (availableHeight >= 180.dp) {
                    CatsCoHomeGreeting(state.session?.name.orEmpty(), Modifier.offset(y = (-6).dp).padding(bottom = 12.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun Composer(
    state: WorkspaceState,
    model: WorkspaceViewModel,
    onAttachmentSelected: (String) -> Unit,
    onVoice: () -> Unit,
    onSend: () -> Unit,
    onFrameHeightChanged: ((Int) -> Unit)? = null,
    onTrayChanged: ((Boolean) -> Unit)? = null,
    onInputFocused: () -> Unit = {},
    voiceHold: VoiceHoldCallbacks? = null,
    projectName: String? = null,
    aboveInput: (@Composable () -> Unit)? = null,
) {
    var attachmentsExpanded by rememberSaveable { mutableStateOf(false) }
    val reportTray by rememberUpdatedState(onTrayChanged)
    var switchingFromKeyboard by remember { mutableStateOf(false) }
    var attachmentTrayHeight by remember { mutableIntStateOf(0) }
    BackHandler(enabled = attachmentsExpanded) { attachmentsExpanded = false }
    val inputFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    var inputFocused by remember { mutableStateOf(false) }
    val imeInsets = rememberComposerImeInsets()
    val navigationInsets = WindowInsets.navigationBars
    val imeTarget = WindowInsets.imeAnimationTarget
    val imeVisible = WindowInsets.isImeVisible
    val density = LocalDensity.current
    val imeHasSpace by remember(imeInsets, imeTarget, density) {
        derivedStateOf { imeInsets.getBottom(density) > 0 || imeTarget.getBottom(density) > 0 }
    }
    val keyboardActive = imeVisible || imeHasSpace
    val awaitingKeyboard = attachmentsExpanded && inputFocused
    LaunchedEffect(awaitingKeyboard, imeInsets, navigationInsets, density) {
        if (awaitingKeyboard) {
            // Keep the tray's footprint until the incoming keyboard can take over.
            withTimeoutOrNull(600L) {
                snapshotFlow {
                    attachmentTrayHeight > 0 &&
                        imeInsets.getBottom(density) - navigationInsets.getBottom(density) >= attachmentTrayHeight
                }.first { it }
            }
            attachmentsExpanded = false
        }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val running = state.voiceCapture == null && state.conversationRunning && !state.preview
    val stopping = state.voiceCapture == null && state.page.startsWith("chat/") && state.stoppingTopic == state.page.removePrefix("chat/")
    val hasDraft = state.draft.isNotBlank() || state.attachments.isNotEmpty()
    val canSend = hasDraft && !state.busy && !state.sending &&
        !state.uploading && !state.preview && !stopping && (state.page != "home" || state.employeeId.isNotBlank())
    val voiceMode = !hasDraft && !state.sending
    val voiceEnabled = state.voiceCapture != null || (!state.busy && !state.uploading && !state.sending && !state.preview)
    val canHoldInput = voiceHold != null && state.draft.isEmpty() && voiceMode && voiceEnabled &&
        !running && !stopping && !keyboardActive && !attachmentsExpanded && state.voiceCapture == null
    val inputGesture = if (voiceHold != null) Modifier.holdToTalk(
        voiceHold, onTap = {
            if (!inputFocused) inputFocus.requestFocus()
            keyboard?.show()
        }, enabled = canHoldInput,
    ) else Modifier
    val submit = {
        if (canSend) {
            onSend()
            if (!running) {
                keyboard?.hide()
                focusManager.clearFocus(force = true)
            }
            attachmentsExpanded = false
        }
    }
    LaunchedEffect(state.quote?.messageId) {
        if (state.quote != null) {
            val alreadyFocused = inputFocused
            inputFocus.requestFocus()
            if (alreadyFocused) keyboard?.show()
        }
    }
    val attachmentTransition = updateTransition(attachmentsExpanded, label = "attachmentTray")
    val attachmentProgress = attachmentTransition.animateFloat(
        transitionSpec = {
            if (targetState) tween(160, easing = LinearOutSlowInEasing)
            else tween(120, easing = FastOutLinearInEasing)
        },
        label = "attachmentExpansion",
    ) { if (it) 1f else 0f }
    val traySettled = attachmentTransition.currentState == attachmentsExpanded && !attachmentTransition.isRunning
    LaunchedEffect(traySettled, attachmentsExpanded) {
        if (traySettled) reportTray?.invoke(attachmentsExpanded)
    }
    val closingAttachmentTray = !attachmentsExpanded && (attachmentTransition.currentState || attachmentTransition.isRunning)
    val showAttachmentTray = if (attachmentsExpanded) !keyboardActive || awaitingKeyboard else closingAttachmentTray
    val switchToKeyboard = inputFocused && (awaitingKeyboard || closingAttachmentTray)
    val switchFromKeyboard = attachmentsExpanded && switchingFromKeyboard && !switchToKeyboard
    Column(Modifier.navigationBarsPadding().windowInsetsPadding(imeInsets)
        .padding(horizontal = 10.dp, vertical = CatsCoMobileSpec.composerVerticalInset)) {
        aboveInput?.invoke()
        if (projectName != null) Text(projectName, Modifier.padding(start = 14.dp, bottom = 6.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        ComposerAttachmentLayout(
            attachmentProgress, switchFromKeyboard = switchFromKeyboard, switchToKeyboard = switchToKeyboard,
            showTray = showAttachmentTray, imeInsets = imeInsets, navigationInsets = navigationInsets,
        ) {
            CatsCoComposerFrame(
                modifier = if (onFrameHeightChanged != null) Modifier.onSizeChanged { onFrameHeightChanged(it.height) } else Modifier,
            ) {
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
                    Row(Modifier.padding(horizontal = 8.dp, vertical = CatsCoMobileSpec.composerRowVerticalInset), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        CatsCoBoundedIconButton(onClick = {
                            attachmentsExpanded = !attachmentsExpanded
                            if (attachmentsExpanded) {
                                switchingFromKeyboard = keyboardActive
                                if (inputFocused) focusManager.clearFocus(force = true)
                                keyboard?.hide()
                            }
                        }, modifier = Modifier.size(48.dp), visualSize = 40.dp) {
                            Icon(
                                painterResource(R.drawable.e_add),
                                if (attachmentsExpanded) "收起附件" else "添加附件",
                                Modifier.size(CatsCoMobileSpec.actionIconSize).graphicsLayer { rotationZ = 45f * attachmentProgress.value },
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        BasicTextField(state.draft, model::draft, modifier = Modifier.weight(1f).then(inputGesture).focusRequester(inputFocus)
                                .onFocusChanged {
                                    inputFocused = it.isFocused
                                    if (it.isFocused) onInputFocused()
                                }.heightIn(min = 48.dp).padding(vertical = 11.dp), maxLines = 6,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { submit() }),
                                textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(Mint), decorationBox = { inner ->
                                    if (state.draft.isEmpty()) ComposerPlaceholder(
                                        label = when {
                                            state.page == "home" -> "描述你的任务"
                                            running -> "补充要求"
                                            else -> "发送消息"
                                        },
                                        cycle = canHoldInput && !inputFocused,
                                    )
                                    inner()
                                })
                        if (state.draft.isNotBlank() && voiceEnabled && !running && !stopping) {
                            CatsCoBoundedIconButton(
                                onClick = onVoice,
                                visualSize = 36.dp,
                                modifier = Modifier.size(44.dp)
                                    .then(if (voiceHold != null) Modifier.holdToTalk(voiceHold, onVoice) else Modifier),
                            ) {
                                Icon(painterResource(R.drawable.e_mic), "继续语音输入",
                                    Modifier.size(CatsCoMobileSpec.actionIconSize),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        ComposerSendButton(
                            running = running,
                            stopping = stopping,
                            sendEnabled = canSend,
                            hasDraft = hasDraft,
                            sending = state.sending,
                            emphasized = state.draft.isNotBlank(),
                            voiceMode = voiceMode,
                            voiceEnabled = voiceEnabled,
                            onVoice = onVoice,
                            voiceHold = voiceHold,
                            onSend = submit,
                            onStop = model::stop,
                            stopEnabled = !state.busy,
                        )
                    }
                }
            }
            if (showAttachmentTray || (attachmentsExpanded && switchingFromKeyboard)) {
                Row(
                    Modifier.fillMaxWidth().onSizeChanged { attachmentTrayHeight = it.height }
                        .padding(start = 8.dp, end = 8.dp, top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val actions = listOf(
                        "拍照" to R.drawable.e_attachment_camera,
                        "照片" to R.drawable.e_attachment_photo,
                        "文件" to R.drawable.e_attachment_folder,
                    )
                    actions.forEach { (label, icon) ->
                        Column(
                            Modifier.weight(1f).heightIn(min = 112.dp)
                                .clickable(enabled = attachmentsExpanded && showAttachmentTray && !awaitingKeyboard) {
                                    attachmentsExpanded = false
                                    onAttachmentSelected(label)
                                }
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
                            Text(label, fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
            }
        }
        if (state.preview) Text("调试示例 · 不会发送请求", Modifier.fillMaxWidth().padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun ComposerPlaceholder(label: String, cycle: Boolean) {
    var showVoiceHint by remember(label, cycle) { mutableStateOf(false) }
    LaunchedEffect(cycle, label) {
        if (cycle) {
            while (true) {
                delay(3_500)
                showVoiceHint = !showVoiceHint
            }
        }
    }
    Crossfade(
        targetState = if (showVoiceHint) "长按语音转文字" else label,
        animationSpec = tween(if (cycle) 120 else 0),
        label = "composerPlaceholder",
    ) { text ->
        Text(text, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ComposerAttachmentLayout(
    progress: State<Float>,
    switchFromKeyboard: Boolean,
    switchToKeyboard: Boolean,
    showTray: Boolean,
    imeInsets: WindowInsets,
    navigationInsets: WindowInsets,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = Modifier.fillMaxWidth().clipToBounds()) { measurables, constraints ->
        val rowConstraints = constraints.copy(minHeight = 0)
        val composer = measurables[0].measure(rowConstraints)
        val availableHeight = rowConstraints.maxHeight - composer.height
        val trayConstraints = rowConstraints.copy(maxHeight = if (switchToKeyboard || (switchFromKeyboard && !showTray)) Constraints.Infinity
            else if (rowConstraints.hasBoundedHeight) availableHeight else Constraints.Infinity)
        val tray = measurables.getOrNull(1)?.measure(trayConstraints)
        val trayHeight = tray?.height ?: 0
        // During the handoff, reserve only the tray space that the retreating keyboard
        // no longer occupies. The composer stops at the tray height instead of the screen edge.
        val keyboardSpace = if (switchFromKeyboard || switchToKeyboard) {
            (imeInsets.getBottom(this) - navigationInsets.getBottom(this)).coerceAtLeast(0)
        } else 0
        val reservedHeight = when {
            switchFromKeyboard -> (trayHeight - keyboardSpace).coerceIn(0, availableHeight)
            switchToKeyboard -> ((trayHeight * progress.value).toInt() - keyboardSpace).coerceIn(0, availableHeight)
            else -> trayHeight
        }

        layout(composer.width, composer.height + reservedHeight) {
            composer.placeRelativeWithLayer(0, 0) {
                translationY = if (switchFromKeyboard || switchToKeyboard) 0f else (1f - progress.value) * trayHeight
            }
            if (trayHeight > 0 && (showTray || (switchFromKeyboard && reservedHeight > 0))) tray?.placeRelativeWithLayer(0, composer.height) {
                if (switchFromKeyboard || switchToKeyboard) {
                    // Keep the tray visible only in the space left by the keyboard.
                    alpha = LinearOutSlowInEasing.transform((reservedHeight.toFloat() / trayHeight).coerceIn(0f, 1f))
                } else {
                    val expansion = progress.value
                    translationY = (1f - expansion) * trayHeight
                    alpha = expansion
                }
            }
        }
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
    onMoveToProject: (() -> Unit)? = null,
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
            Text(conversation.name, modifier = Modifier.weight(1f), fontSize = CatsCoMobileSpec.historyText,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            onMoveToProject = onMoveToProject,
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
