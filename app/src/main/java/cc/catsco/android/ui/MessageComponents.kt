package cc.catsco.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.IntOffset
import cc.catsco.android.R
import cc.catsco.android.data.*
import cc.catsco.core.Attachment
import cc.catsco.core.MessageQuote

@Composable
fun MessageTurnContent(
    turn: MessageTurn,
    mine: Boolean,
    senderName: String,
    active: Boolean,
    filesEnabled: Boolean,
    onPreview: (Attachment) -> Unit,
    onDownload: (Attachment) -> Unit,
    onQuote: ((MessageTurn) -> Unit)? = null,
    onRegenerate: ((MessageTurn) -> Unit)? = null,
    regenerateEnabled: Boolean = true,
    stopping: Boolean = false,
    showSentReceipt: Boolean = false,
) {
    if (mine) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            Surface(
                Modifier.widthIn(max = 320.dp),
                shape = RoundedCornerShape(16.dp),
                color = if (MaterialTheme.colorScheme.background.luminance() < .5f) Color(0xFF2C2C2E) else Color(0xFFE6E6E9),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    turn.quote?.let { MessageQuotePreview(it) }
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            turn.text.forEach {
                                Text(it, fontSize = 16.sp, lineHeight = 25.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .86f))
                            }
                        }
                    }
                    turn.files.forEach { MessageFileCard(it, filesEnabled, onPreview, onDownload) }
                }
            }
            if (showSentReceipt) {
                Text("已发送", Modifier.padding(top = 4.dp, end = 4.dp), fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    val activity = agentActivityLabel(turn, active, stopping)
    val loadingReply = activity != null && activity != "正在停止" && turn.text.isEmpty()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (senderName.isNotBlank() || activity != null) AgentMessageHeader(senderName.ifBlank { "Agent" }, activity)
        turn.progress.forEach { MessageReplyBody(listOf(it)) }
        if (turn.hasProcess) MessageWorkingProcess(turn, active)
        turn.files.forEach { MessageFileCard(it, filesEnabled, onPreview, onDownload) }
        if (turn.text.isNotEmpty() || loadingReply) {
            val reply: @Composable (Modifier) -> Unit = { modifier ->
                AgentReplyBubble(loadingReply, modifier) {
                    if (turn.text.isNotEmpty()) MessageReplyContent(turn.text)
                }
            }
            if (turn.assistant && onQuote != null && onRegenerate != null) {
                MessageActionReply(turn, regenerateEnabled && !active, onQuote, onRegenerate,
                    enabled = turn.messageId > 0 && !turn.streaming && turn.text.isNotEmpty(), content = reply)
            } else reply(Modifier)
        }
        if (!turn.hasOutput && !turn.hasProcess && turn.progress.isEmpty() && !turn.streaming && activity == null) {
            Text("此消息暂无可显示内容", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MessageReplyBody(paragraphs: List<String>, modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        MessageReplyContent(paragraphs)
    }
}

@Composable
private fun MessageReplyContent(paragraphs: List<String>) {
    Column(Modifier.padding(horizontal = 15.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        paragraphs.forEach { MarkdownContent(it) }
    }
}

@Composable
private fun MessageActionReply(turn: MessageTurn, regenerateEnabled: Boolean, onQuote: (MessageTurn) -> Unit,
    onRegenerate: (MessageTurn) -> Unit, enabled: Boolean, content: @Composable (Modifier) -> Unit) {
    var expanded by remember(turn.key, turn.messageId) { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<IntOffset?>(null) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val translation by animateFloatAsState(dragOffset, spring(dampingRatio = 1.12f, stiffness = 650f), label = "replySwipe")
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
    val maxTranslation = with(LocalDensity.current) { 24.dp.toPx() }
    val context = LocalContext.current
    val view = LocalView.current
    Box(Modifier.testTag("message-reply-${turn.messageId}")
        .onGloballyPositioned { coordinates = it }
        .semantics {
            customActions = if (enabled) listOf(CustomAccessibilityAction("消息操作") { anchor = null; expanded = true; true }) else emptyList()
        }
        .pointerInput(turn.key, turn.messageId, threshold, enabled) {
            if (!enabled) return@pointerInput
            try {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var distance = 0f
                    val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                        // A rightward drag belongs to the navigation drawer.
                        if (overSlop < 0f) {
                            distance = overSlop
                            change.consume()
                        }
                    } ?: return@awaitEachGesture
                    val released = horizontalDrag(start.id) { change ->
                        distance += change.positionChange().x
                        dragOffset = (distance * .3f).coerceIn(-maxTranslation, 0f)
                        coordinates?.takeIf { it.isAttached }?.localToWindow(change.position)?.let { point ->
                            anchor = IntOffset(point.x.toInt(), point.y.toInt())
                        }
                        change.consume()
                    }
                    if (released && distance <= -threshold) expanded = true
                    dragOffset = 0f
                }
            } finally {
                dragOffset = 0f
            }
        }) {
        content(Modifier.graphicsLayer { translationX = translation })
        CatsCoMessageActionMenu(
            expanded, anchor ?: IntOffset(view.width / 2, view.height / 2), regenerateEnabled,
            onDismiss = { expanded = false },
            onQuote = { onQuote(turn) },
            onCopy = {
                runCatching {
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("CatsCo", turn.text.joinToString("\n\n")))
                }.onFailure { android.widget.Toast.makeText(context, "暂时无法复制，请重试。", android.widget.Toast.LENGTH_SHORT).show() }
            },
            onRegenerate = { onRegenerate(turn) },
        )
    }
}

@Composable
internal fun MessageQuotePreview(quote: MessageQuote, onClear: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(2.dp).height(32.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(quote.senderName.ifBlank { "引用的消息" }, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(quote.text, fontSize = 13.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onClear != null) IconButton(onClick = onClear, modifier = Modifier.size(40.dp)) {
            Icon(painterResource(R.drawable.e_close), "取消引用", Modifier.size(CatsCoMobileSpec.actionIconSize))
        }
    }
}

@Composable
private fun MessageWorkingProcess(turn: MessageTurn, active: Boolean) {
    var expanded by rememberSaveable(turn.key) { mutableStateOf(false) }
    val running = if (turn.executionState.isNotBlank()) turn.executionState == "running" else active || turn.streaming
    val label = when (turn.executionState) {
        "failed" -> "执行失败"
        "cancelled", "stopped" -> "已停止"
        "waiting_for_input" -> "等待输入"
        "paused" -> "已暂停"
        "queued", "waiting" -> "等待执行"
        "disconnected" -> "连接已断开"
        "stopping" -> "正在停止"
        "stop-requested" -> "等待停止确认"
        "completed", "replied" -> "已完成"
        else -> if (running) "正在执行" else if (turn.hasOutput || turn.plan.isNotEmpty() && turn.plan.all { it.status == "completed" }) "已完成" else "执行记录"
    }
    val count = if (turn.plan.isNotEmpty()) "${turn.plan.count { it.status == "completed" }}/${turn.plan.size}" else "${turn.steps.size} 步"
    val failures = turn.steps.count { it.error }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp)
                .catsCoDisclosureClickable(
                    expanded = expanded,
                    enabled = turn.steps.isNotEmpty(),
                    onClickLabel = if (expanded) "收起执行过程" else "展开执行过程",
                ) { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "已展开" else "已收起" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (turn.steps.isNotEmpty()) CatsCoDisclosureChevron(expanded, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (label == "执行失败") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(count, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (failures > 0) Text("${failures} 步异常", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
        AnimatedVisibility(
            expanded,
            enter = expandVertically(spring(dampingRatio = 1.1f, stiffness = 450f)) + fadeIn(),
            exit = shrinkVertically(spring(dampingRatio = 1.1f, stiffness = 450f)) + fadeOut(),
        ) {
            val line = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)
            Column(
                Modifier.fillMaxWidth().padding(start = 7.dp).drawBehind {
                    drawLine(line, Offset.Zero, Offset(0f, size.height), strokeWidth = 1.5.dp.toPx())
                }.padding(start = 13.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                turn.steps.forEach { MessageWorkingStep(it) }
            }
        }
        if (turn.plan.isNotEmpty()) MessagePlanCard(turn.plan)
    }
}

@Composable
private fun MessageWorkingStep(step: MessageWorkStep) {
    var expanded by rememberSaveable(step.id) { mutableStateOf(false) }
    val accent = if (step.error) MaterialTheme.colorScheme.error else Mint
    val title = step.description.ifBlank {
        when (step.name) {
            "execute_shell" -> "执行命令"
            "read_file" -> "读取文件"
            "write_file" -> "写入文件"
            "edit_file" -> "修改文件"
            "glob" -> "查找文件"
            "grep" -> "搜索内容"
            "web_search", "search_web" -> "搜索网页"
            "web_fetch", "fetch_url" -> "读取网页"
            "update_plan" -> "更新计划"
            else -> step.name
        }
    }
    Column(Modifier.fillMaxWidth().animateContentSize(spring(dampingRatio = 1.1f, stiffness = 450f))) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).catsCoDisclosureClickable(expanded) { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "已展开" else "已收起" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CatsCoDisclosureChevron(expanded, tint = accent)
            Icon(painterResource(if (step.kind == "subagent") R.drawable.e_people else if (step.kind == "thinking") R.drawable.e_chat else R.drawable.message_terminal), null, Modifier.size(16.dp), tint = accent.copy(alpha = .8f))
            Text(title, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = accent, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (step.error) Text("失败", fontSize = 12.sp, color = accent)
        }
        if (step.error && step.failureReason.isNotBlank()) {
            Text(step.failureReason, Modifier.padding(start = 35.dp, bottom = 6.dp), fontSize = 12.sp, lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step.kind == "tool") Text(step.name, fontSize = 12.sp, fontFamily = CatsCoFontFamily, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (step.kind == "thinking" && step.summary.isNotBlank()) MarkdownContent(step.summary)
                if (step.input.isNotBlank()) {
                    Text("输入", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MessageCodeBlock(step.input)
                }
                step.result?.let { result ->
                    Text(if (step.error) "工具错误" else "执行结果 · ${result.length} 字符", fontSize = 12.sp, color = if (step.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    MessageCodeBlock(result.ifEmpty { "（空结果）" })
                }
                step.children.forEach { MessageWorkingStep(it) }
                if (step.kind == "tool" && step.result == null) Text("尚未收到工具结果", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun MessageCodeBlock(text: String, fontSize: TextUnit = 12.sp) {
    Surface(shape = CatsCoMobileSpec.codeShape, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .045f)) {
        Box(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(10.dp)) {
            SelectionContainer { Text(text, fontFamily = CatsCoFontFamily, fontSize = fontSize, lineHeight = (fontSize.value + 7).sp, color = MaterialTheme.colorScheme.onSurface) }
        }
    }
}

@Composable
private fun MessagePlanCard(steps: List<MessagePlanStep>) {
    Surface(
        Modifier.fillMaxWidth(), shape = CatsCoMobileSpec.contentCardShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.message_document), null, Modifier.size(18.dp), tint = Mint.copy(alpha = .8f))
                Text("计划", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("${steps.count { it.status == "completed" }}/${steps.size} 已完成", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            steps.forEach { step ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    PlanStatusIcon(step.status, Modifier.padding(top = 2.dp).size(17.dp))
                    Text(step.text, Modifier.weight(1f), fontSize = 14.sp, lineHeight = 21.sp,
                        fontWeight = if (step.status == "in_progress") FontWeight.Medium else FontWeight.Normal,
                        color = if (step.status == "pending") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = .83f))
                }
            }
        }
    }
}

@Composable
private fun PlanStatusIcon(status: String, modifier: Modifier) {
    val color = if (status == "completed") Mint else if (status == "in_progress") MaterialTheme.colorScheme.onSurface.copy(alpha = .75f) else MaterialTheme.colorScheme.onSurfaceVariant
    val icon = when (status) { "completed" -> R.drawable.e_check_circle; "in_progress" -> R.drawable.e_progress_circle; else -> R.drawable.e_circle }
    Icon(painterResource(icon), null,
        modifier.semantics { stateDescription = when (status) { "completed" -> "已完成"; "in_progress" -> "进行中"; else -> "待处理" } }, tint = color)
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun MessageFileCard(file: Attachment, enabled: Boolean, onPreview: (Attachment) -> Unit, onDownload: (Attachment) -> Unit) {
    var details by remember { mutableStateOf(false) }
    if (details) CatsCoAlertDialog(onDismissRequest = { details = false }, title = { Text("文件信息") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SelectionContainer { Text(file.name, fontSize = 16.sp, lineHeight = 24.sp) }
            Text(attachmentDescription(file), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { CatsCoButton(onClick = { details = false; onPreview(file) }, enabled = enabled) { Text("预览") } },
        dismissButton = { CatsCoSecondaryButton(onClick = { details = false }) { Text("关闭") } })
    Surface(
        Modifier.fillMaxWidth(), shape = CatsCoMobileSpec.contentCardShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(start = 9.dp, end = 3.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).combinedClickable(enabled = enabled, role = Role.Button, onClickLabel = "预览 ${file.name}",
                onLongClickLabel = "文件信息", onLongClick = { details = true }, onClick = { onPreview(file) }), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .045f)) {
                    Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                        Icon(painterResource(if (file.mime.startsWith("image/")) R.drawable.e_image else R.drawable.message_document), null, Modifier.size(CatsCoMobileSpec.actionIconSize), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .75f))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(file.name, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(attachmentDescription(file), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = { onPreview(file) }, enabled = enabled, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.message_eye), "预览 ${file.name}", Modifier.size(CatsCoMobileSpec.actionIconSize), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f))
            }
            IconButton(onClick = { onDownload(file) }, enabled = enabled, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.e_download), "下载 ${file.name}", Modifier.size(CatsCoMobileSpec.actionIconSize), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f))
            }
        }
    }
}
