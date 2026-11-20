package cc.catsco.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.catsco.android.R
import cc.catsco.android.WorkspaceState
import cc.catsco.android.WorkspaceViewModel
import cc.catsco.core.Conversation
import cc.catsco.core.Project
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch

@Composable
internal fun ProjectFilters(
    projects: List<Project>,
    selected: String,
    backdrop: HazeState,
    onTouchActive: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
) {
    if (projects.isEmpty()) return
    val neutral = if (MaterialTheme.colorScheme.surface.luminance() > .5f) Color.Black else Color.White
    val shape = CatsCoMobileSpec.searchShape
    val reportTouch by rememberUpdatedState(onTouchActive)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val chipMaxWidth = maxWidth
        LazyRow(
            Modifier.fillMaxWidth().testTag("sidebar-project-filters").pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    reportTouch(true)
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                        } while (event.changes.any { it.pressed })
                    } finally {
                        reportTouch(false)
                    }
                }
            },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(projects, key = { it.id }) { project ->
                val active = project.id == selected
                CatsCoSidebarFieldSurface(
                    backdrop = backdrop,
                    modifier = Modifier.widthIn(max = chipMaxWidth),
                    shape = shape,
                ) {
                    Box(
                        Modifier.heightIn(min = 30.dp)
                            .background(if (active) neutral.copy(alpha = .05f) else Color.Transparent)
                            .selectable(active, interactionSource = null, indication = CatsCoPressFeedback(neutral, shape, pressOpacity = .028f),
                                role = Role.Button, onClick = { onSelect(project.id) })
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (active) 1f else .62f))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectsSheet(
    state: WorkspaceState,
    model: WorkspaceViewModel,
    pinned: Set<String>,
    conversations: List<Conversation>,
    onTogglePin: (String) -> Unit,
    onDismiss: () -> Unit,
    onOpenTask: (String) -> Unit,
    onNewTask: (String) -> Unit,
    movingTask: Conversation? = null,
) {
    val shortWindow = LocalConfiguration.current.screenHeightDp < 500
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = shortWindow)
    val scope = rememberCoroutineScope()
    val backdrop = rememberHazeState()
    var query by rememberSaveable { mutableStateOf("") }
    var projectId by rememberSaveable { mutableStateOf("") }
    var sortByName by rememberSaveable { mutableStateOf(false) }
    var creationOpen by rememberSaveable { mutableStateOf(false) }
    var draftName by rememberSaveable { mutableStateOf("") }
    val expanded = shortWindow || sheet.targetValue == SheetValue.Expanded
    val selected = state.workspace.projects.firstOrNull { it.id == projectId }
    val byProject = remember(conversations) { conversations.filter { it.task && it.projectId.isNotBlank() }.groupBy { it.projectId } }
    val projects = remember(state.workspace.projects, byProject, sortByName, query) {
        val matching = state.workspace.projects.filter { it.name.contains(query.trim(), ignoreCase = true) }
        if (sortByName) matching.sortedBy { it.name }
        else matching.sortedByDescending { byProject[it.id]?.maxOfOrNull(Conversation::date).orEmpty() }
    }
    val list = rememberLazyListState()
    LaunchedEffect(query) { list.scrollToItem(0) }
    LaunchedEffect(Unit) { model.loadProjects() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        shape = CatsCoMobileSpec.sheetShape,
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = Color.Black.copy(alpha = .16f),
    ) {
        Column(Modifier.fillMaxHeight().fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selected != null) CatsCoHeaderAction(R.drawable.e_next, "返回项目列表", decorated = false, iconRotation = 180f) { projectId = "" }
                Text(selected?.name ?: if (movingTask == null) "项目" else "移到项目",
                    Modifier.weight(1f).padding(start = 4.dp), fontSize = 18.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (selected == null && movingTask == null) {
                    CatsCoHeaderAction(R.drawable.e_add, "新建项目", decorated = false, enabled = !state.busy && !state.preview) {
                        draftName = ""
                        creationOpen = true
                    }
                } else {
                    CatsCoHeaderAction(R.drawable.e_close, "关闭项目", decorated = false, onClick = onDismiss)
                }
            }
            if (selected == null) {
                CatsCoSidebarSearchField(query, { query = it }, backdrop,
                    Modifier.fillMaxWidth().padding(vertical = 8.dp), placeholder = "搜索项目", glass = false)
                if (expanded && movingTask == null) {
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${projects.size} 个项目", Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { sortByName = !sortByName }) {
                            Text(if (sortByName) "按名称排列" else "按最近任务排列", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (state.projectsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.projectsError?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    TextButton(onClick = model::loadProjects) { Text("重试") }
                }
            }
            state.error?.let {
                Text(it, Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            if (selected == null) {
                val favorites = projects.filter { it.id in pinned && movingTask == null }
                val regular = projects.filterNot { it in favorites }
                val visible = if (!expanded && query.isBlank() && movingTask == null) regular.take(5) else regular
                LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(bottom = 16.dp)) {
                    if (movingTask != null && movingTask.projectId.isNotBlank() && query.isBlank()) item(key = "unassigned") {
                        ProjectRow("移出项目", icon = R.drawable.e_attachment_folder, enabled = !state.busy, onOpen = {
                            model.moveToProject(movingTask.id, "", onDismiss)
                        })
                    }
                    if (favorites.isNotEmpty()) item(key = "pinned") { ProjectCaption("已置顶") }
                    items(favorites, key = { it.id }) { project ->
                        ProjectRow(project.name, count = byProject[project.id]?.size ?: 0, pinned = true,
                            onPin = { onTogglePin(project.id) }, onOpen = {
                                projectId = project.id
                                scope.launch { sheet.expand() }
                            })
                    }
                    if (visible.isNotEmpty() && movingTask == null) item(key = "recent") {
                        ProjectCaption(if (!expanded && query.isBlank()) "最近使用" else "项目列表")
                    }
                    items(visible, key = { it.id }) { project ->
                        val choose: () -> Unit = {
                            if (movingTask == null) {
                                projectId = project.id
                                scope.launch { sheet.expand() }
                            } else if (movingTask.projectId == project.id) onDismiss()
                            else model.moveToProject(movingTask.id, project.id, onDismiss)
                        }
                        ProjectRow(project.name, count = if (movingTask == null) byProject[project.id]?.size ?: 0 else null,
                            marker = if (movingTask?.projectId == project.id) R.drawable.e_select else null,
                            pinned = false, enabled = !state.busy,
                            onPin = if (movingTask == null) ({ onTogglePin(project.id) }) else null, onOpen = choose)
                    }
                    if (projects.isEmpty() && !state.projectsLoading && state.projectsError == null) item(key = "empty") {
                        ProjectCaption(if (query.isBlank()) "还没有项目" else "没有找到匹配的项目")
                    }
                }
            } else {
                val tasks = byProject[selected.id].orEmpty()
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${tasks.size} 个任务", Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CatsCoSecondaryButton(onClick = { onNewTask(selected.id) },
                        enabled = !state.sending && !state.uploading && !state.taskCreationUncertain) { Text("新建任务") }
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(tasks, key = { it.id }) { item ->
                        ProjectRow(item.name, icon = R.drawable.e_chat, onOpen = { onOpenTask(item.id) })
                    }
                    if (tasks.isEmpty()) item { ProjectCaption("此项目还没有任务") }
                }
            }
        }
    }
    if (creationOpen) CatsCoAlertDialog(
        onDismissRequest = { creationOpen = false },
        title = { Text("新建项目") },
        text = { ModuleTextField(draftName, { draftName = it.take(128) }, Modifier.fillMaxWidth(),
            label = { Text("项目名称") }, singleLine = true, enabled = !state.busy) },
        dismissButton = { CatsCoSecondaryButton(onClick = { creationOpen = false }) { Text("取消") } },
        confirmButton = { CatsCoButton(enabled = draftName.isNotBlank() && !state.busy, onClick = {
            model.createProject(draftName) { project ->
                creationOpen = false
                projectId = project.id
                scope.launch { sheet.expand() }
            }
        }) { Text("创建") } },
    )
}


@Composable
private fun ProjectCaption(text: String) {
    Text(text, Modifier.padding(start = 8.dp, top = 14.dp, bottom = 5.dp),
        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ProjectRow(
    name: String,
    icon: Int = R.drawable.e_attachment_folder,
    count: Int? = null,
    pinned: Boolean = false,
    enabled: Boolean = true,
    marker: Int? = null,
    onPin: (() -> Unit)? = null,
    onOpen: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).heightIn(min = 52.dp)
                .catsCoActionClickable(enabled = enabled, shape = CatsCoMobileSpec.radiusControl, onClick = onOpen)
                .padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(painterResource(icon), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                count?.let { Text("$it 个任务", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            marker?.let { Icon(painterResource(it), null, Modifier.size(20.dp)) }
        }
        if (onPin != null) {
            val neutral = if (MaterialTheme.colorScheme.surface.luminance() > .5f) Color.Black else Color.White
            val label = if (pinned) "取消置顶 $name" else "置顶 $name"
            CatsCoBoundedIconButton(onClick = onPin, enabled = enabled, feedbackColor = neutral, pressOpacity = .028f,
                modifier = Modifier.size(46.dp).semantics { contentDescription = label; stateDescription = if (pinned) "已置顶" else "未置顶" }) {
                Surface(shape = RoundedCornerShape(20.dp), color = if (pinned) neutral.copy(alpha = .05f) else Color.Transparent) {
                    Icon(painterResource(R.drawable.e_pin), null, Modifier.padding(8.dp).size(18.dp),
                        tint = if (pinned) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
