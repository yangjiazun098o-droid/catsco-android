package cc.catsco.android.ui

import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import cc.catsco.android.R
import cc.catsco.core.Employee
import cc.catsco.core.Session
import coil.ImageLoader
import coil.imageLoader
import kotlin.math.roundToInt

@Composable
internal fun CatsCoHomeGreeting(name: String = "", modifier: Modifier = Modifier) {
    val greeting = if (name.isBlank()) "需要为您做什么？" else "需要为您做什么，${name.trim()}？"
    val density = LocalDensity.current
    val context = LocalContext.current
    val textMetrics = remember(greeting, density, context) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = with(density) { 24.sp.toPx() }
            typeface = CatsCoFonts.medium(context)
        }
        val bounds = Rect()
        paint.getTextBounds(greeting, 0, greeting.length, bounds)
        val width = paint.measureText(greeting)
        width to ((width - bounds.right - bounds.left) / 2).roundToInt()
    }
    BoxWithConstraints(modifier.widthIn(max = 333.dp), contentAlignment = Alignment.Center) {
        val wraps = textMetrics.first > constraints.maxWidth
        val text = if (wraps && name.isNotBlank()) "需要为您做什么，\n${name.trim()}？" else greeting
        Text(text, Modifier.offset { IntOffset(if (wraps) 0 else textMetrics.second, 0) },
            fontSize = 24.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge.copy(lineBreak = LineBreak.Heading))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CatsCoHomeAgent(
    employees: List<Employee>,
    selectedId: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    onSettings: (String) -> Unit = {},
    session: Session? = null,
    imageLoader: ImageLoader? = null,
    compact: Boolean = false,
    onPickerVisibility: (Boolean) -> Unit = {},
    onPressActive: (Boolean) -> Unit = {},
) {
    val selectedIndex = employees.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val employee = employees.getOrNull(selectedIndex)
    val images = imageLoader ?: LocalContext.current.imageLoader
    var expanded by rememberSaveable { mutableStateOf(false) }
    val visibility by rememberUpdatedState(onPickerVisibility)
    DisposableEffect(expanded) {
        visibility(expanded)
        onDispose { if (expanded) visibility(false) }
    }
    LaunchedEffect(employee) { if (employee == null) expanded = false }
    if (employee == null) {
        Text("暂无可用 Agent", modifier.padding(16.dp), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val employeeIds = remember(employees) { employees.map { it.id } }
    val pager = rememberPagerState(initialPage = selectedIndex) { employees.size }
    val select by rememberUpdatedState(onSelected)
    LaunchedEffect(pager, employeeIds, employee.id) {
        if (pager.settledPage != selectedIndex) pager.scrollToPage(selectedIndex)
        snapshotFlow { pager.settledPage }.collect { page ->
            employeeIds.getOrNull(page)?.takeIf { it != employee.id }?.let(select)
        }
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press = updateTransition(pressed, label = "homeAgentPress")
    val opacity by press.animateFloat(transitionSpec = { tween(90) }) { if (it) .72f else 1f }
    val pressActive = pressed || press.currentState || press.isRunning || pager.isScrollInProgress
    val reportPress by rememberUpdatedState(onPressActive)
    DisposableEffect(pressActive) {
        reportPress(pressActive)
        onDispose { if (pressActive) reportPress(false) }
    }
    HorizontalPager(
        state = pager,
        modifier = modifier.widthIn(max = 280.dp).fillMaxWidth().testTag("home-agent-pager"),
        key = { employees[it].id },
        userScrollEnabled = employees.size > 1 && !expanded,
        flingBehavior = PagerDefaults.flingBehavior(state = pager, snapPositionalThreshold = .24f),
    ) { page ->
        val agent = employees[page]
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            AgentIdentity(agent, session, images, compact,
                Modifier.graphicsLayer { alpha = if (expanded) 0f else opacity }
                    .testTag(if (page == pager.settledPage) "home-agent-card" else "home-agent-card-${agent.id}")
                    .combinedClickable(
                        interactionSource = interaction, indication = null,
                        role = Role.Button,
                        onClickLabel = "Agent 设置", onClick = { onSettings(agent.id) },
                        onLongClickLabel = "切换 Agent", onLongClick = { expanded = true },
                    ).padding(8.dp))
        }
    }
    if (expanded) AgentPicker(employees, employee, session, images,
        onDismiss = { expanded = false },
        onSelected = { id -> expanded = false; if (id != selectedId) onSelected(id) },
        onSettings = { expanded = false; onSettings(employee.id) })
}

@Composable
private fun AgentIdentity(employee: Employee, session: Session?, images: ImageLoader, compact: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(if (compact) 14.dp else 20.dp)
    val avatar: @Composable () -> Unit = {
        Box(Modifier.size(if (compact) 48.dp else 80.dp)) {
            CatsCoAccountAvatar(session?.copy(avatarUrl = employee.avatarUrl), images,
                Modifier.fillMaxSize().shadow(6.dp, shape), shape, R.drawable.e_agent)
            Box(Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp).size(16.dp)
                .background(MaterialTheme.colorScheme.background, CircleShape).padding(3.dp)
                .background(if (employee.online) Mint else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
        }
    }
    val labels: @Composable (TextAlign) -> Unit = { align ->
        Text(employee.name, fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.Normal,
            textAlign = align, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (compact) Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        avatar()
        Column(Modifier.weight(1f, fill = false)) { labels(TextAlign.Start) }
    } else Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        avatar()
        Spacer(Modifier.height(12.dp))
        labels(TextAlign.Center)
    }
}

@Composable
internal fun AgentPicker(
    employees: List<Employee>, employee: Employee, session: Session?, images: ImageLoader,
    onDismiss: () -> Unit, onSelected: (String) -> Unit, onSettings: (() -> Unit)? = null,
) {
    val parentDensity = LocalDensity.current
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        CompositionLocalProvider(LocalDensity provides parentDensity) {
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            val lightSystemBars = MaterialTheme.colorScheme.background.luminance() > .5f
            SideEffect {
                window?.let {
                    it.setDimAmount(.22f)
                    WindowCompat.getInsetsController(it, it.decorView).apply {
                        isAppearanceLightStatusBars = lightSystemBars
                        isAppearanceLightNavigationBars = lightSystemBars
                    }
                }
            }
            val outsideInteraction = remember { MutableInteractionSource() }
            Box(Modifier.fillMaxSize().testTag("agent-picker-overlay")) {
                Box(Modifier.matchParentSize().testTag("agent-picker-dismiss")
                    .semantics { contentDescription = "关闭员工切换" }
                    .clickable(interactionSource = outsideInteraction, indication = null, onClick = onDismiss))
                BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
                    val availableHeight = maxHeight
                    val alignment = BiasAlignment(0f, if (availableHeight >= 520.dp) .6f else 0f)
                    Column(Modifier.align(alignment).widthIn(max = 264.dp).fillMaxWidth().heightIn(max = availableHeight),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        if (availableHeight >= 520.dp && onSettings != null) {
                            AgentIdentity(employee, session, images, false,
                                Modifier.widthIn(max = 280.dp).clickable(onClickLabel = "Agent 设置", onClick = onSettings).padding(8.dp))
                            Spacer(Modifier.height(24.dp))
                        }
                        Surface(Modifier.weight(1f, fill = false).heightIn(max = 460.dp).testTag("agent-picker-menu").catsCoFloatingShadow(CatsCoMobileSpec.dialogShape),
                            shape = CatsCoMobileSpec.dialogShape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            border = androidx.compose.foundation.BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Column(Modifier.pointerInput(Unit) { detectTapGestures {} }) {
                                Text("员工", Modifier.fillMaxWidth().padding(start = 48.dp, top = 16.dp, bottom = 8.dp),
                                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val list = rememberLazyListState(employees.indexOfFirst { it.id == employee.id }.coerceAtLeast(0))
                                LazyColumn(Modifier.weight(1f, fill = false).selectableGroup().testTag("agent-picker-list"),
                                    state = list, contentPadding = PaddingValues(bottom = 8.dp)) {
                                    items(employees, key = { it.id }) { item ->
                                        val selected = item.id == employee.id
                                        Row(Modifier.fillMaxWidth().testTag("agent-option-${item.id}")
                                            .selectable(selected, role = Role.RadioButton, onClick = { onSelected(item.id) })
                                            .heightIn(min = 64.dp).padding(start = 16.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
                                                if (selected) Icon(painterResource(R.drawable.e_select), null, Modifier.size(CatsCoMobileSpec.actionIconSize))
                                            }
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                Text(item.name, fontSize = 16.sp, lineHeight = 23.sp,
                                                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
                                                Text(if (item.online) "在线" else "离线", fontSize = 13.sp, lineHeight = 19.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
