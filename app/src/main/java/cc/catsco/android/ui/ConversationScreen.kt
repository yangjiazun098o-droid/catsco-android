package cc.catsco.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import cc.catsco.android.WorkspaceState
import cc.catsco.android.PendingSend
import cc.catsco.android.WorkspaceViewModel
import cc.catsco.android.conversationRunning
import cc.catsco.android.data.MessageTurn
import cc.catsco.android.data.MessagePresentationCache
import cc.catsco.android.data.projectMessages
import cc.catsco.core.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.tables.TablePlugin
import org.commonmark.node.Node
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.ThematicBreak
import org.commonmark.node.BulletList
import org.commonmark.node.OrderedList
import org.commonmark.node.Heading
import org.commonmark.node.Text as MarkdownLiteral
import org.commonmark.node.Code
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableCell

internal val LocalOpenSidebar = staticCompositionLocalOf<(() -> Unit)?> { null }

private data class ProjectionInput(val messages: List<ChatMessage>, val names: Map<String, String>, val followups: Set<Long>)
private data class ConversationProjection(val turns: List<MessageTurn>, val messages: List<ChatMessage>)

// CommonMark nodes contain content only. Keep them within the current conversation.
private class MarkdownDocuments {
    private val parser = org.commonmark.parser.Parser.builder()
        .extensions(listOf(org.commonmark.ext.gfm.tables.TablesExtension.create())).build()
    private val documents = object : android.util.LruCache<String, List<Node>>(192_000) {
        override fun sizeOf(key: String, value: List<Node>) = key.length.coerceAtLeast(1)
    }
    fun cached(text: String): List<Node>? = documents.get(text)
    fun parse(text: String): List<Node> = cached(text) ?: parser.parse(normalizeMessageMarkdown(text)).children()
        .also { documents.put(text, it) }
}
private val LocalMarkdownDocuments = staticCompositionLocalOf<MarkdownDocuments?> { null }

@Composable
fun ConversationScreen(state: WorkspaceState, model: WorkspaceViewModel, modifier: Modifier = Modifier,
    onOpenSidebar: (() -> Unit)? = null, topContentPadding: Dp = 0.dp,
    onMessagesBehindHeader: ((Boolean) -> Unit)? = null) {
    val topic = state.page.removePrefix("chat/")
    val userId = state.session?.userId.orEmpty()
    val agents = state.workspace.employees
    val followups = state.conversationFollowups[topic]
    val followupIds = remember(state.messages, userId, followups) {
        val pendingIds = followups?.pendingClientIds.orEmpty()
        val sentIds = followups?.sentMessageIds.orEmpty()
        if (pendingIds.isEmpty()) sentIds else state.messages.mapNotNullTo(sentIds.toMutableSet()) {
            it.sequence.takeIf { sequence -> it.senderId == userId && sequence > 0 && it.clientId in pendingIds }
        }
    }
    val documents = remember(state.session?.server, userId, topic) { MarkdownDocuments() }
    val names = remember(agents) { agents.associate { it.id to it.name } }
    val input by rememberUpdatedState(ProjectionInput(state.messages, names, followupIds))
    val projection = key(state.session?.server, userId, topic) {
        produceState(ConversationProjection(emptyList(), emptyList())) {
            val cache = MessagePresentationCache()
            snapshotFlow { input }.conflate().collect { next ->
                value = withContext(Dispatchers.Default) {
                    val grouped = projectMessages(next.messages, userId, next.names.keys, next.names, next.followups, cache)
                    grouped.lastOrNull()?.takeIf { it.assistant }?.text?.filter { it.length >= 1200 }?.forEach(documents::parse)
                    ConversationProjection(grouped, next.messages)
                }
            }
        }.value
    }
    val turns = projection.turns
    val activeTask = state.conversationRunning && state.messageTarget == 0L
    val awaitingReply = topic in state.awaitingReplyAfter && state.messageTarget == 0L &&
        turns.lastOrNull()?.let { !it.assistant && it.senderId == userId } == true
    val stopping = state.stoppingTopic == topic
    val agentId = state.workspace.conversations.find { it.id == topic }?.agentId
    val agentName = agents.find { it.id == agentId }?.name.orEmpty()
    val displayTurns = remember(turns, activeTask, awaitingReply, agentId, agentName, followupIds) {
        conversationDisplayTurns(turns, activeTask, agentId.orEmpty(), agentName, awaitingReply, followupIds)
    }
    val currentPending = state.pendingSend?.takeIf { it.page == state.page }
    var previousPending by remember(topic, userId) { mutableStateOf<PendingSend?>(null) }
    SideEffect { if (currentPending != null) previousPending = currentPending }
    val delivered = previousPending?.takeIf { send -> send.clientId.isNotBlank() &&
        state.messages !== projection.messages && state.messages.any { it.senderId == userId && it.clientId == send.clientId } }
    val pending = (currentPending ?: delivered)?.takeIf { send ->
        send.page == state.page && (send.clientId.isEmpty() || projection.messages.none {
            it.senderId == userId && it.sequence > 0 && it.clientId == send.clientId
        })
    }
    val presentedTurns = remember(displayTurns, pending, userId) {
        if (pending == null) displayTurns else displayTurns + pending.asTurn(userId)
    }
    val currentReplyKey = displayTurns.lastOrNull { it.assistant }?.key
    val showConnection = state.connection in setOf("未连接", "连接已断开", "正在重连")
    val hasHeader = state.messageTarget > 0 || showConnection
    CompositionLocalProvider(LocalOpenSidebar provides onOpenSidebar, LocalMarkdownDocuments provides documents) {
        Column(modifier.fillMaxSize()) {
            if (hasHeader) Spacer(Modifier.height(topContentPadding))
            if (state.messageTarget > 0) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("搜索消息上下文", Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = model::showLatestMessages) { Text("回到最新") }
            }
            if (showConnection) {
                Text(state.connection, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ConversationTimeline(
                topic, presentedTurns, userId, state.loadingMessages || (turns.isEmpty() && state.messages.isNotEmpty()), state.hasMore,
                onLoadOlder = { model.loadMessages(older = true) },
                modifier = Modifier.weight(1f),
                emptyText = if (state.preview) "调试预览不读取真实消息，请使用账号登录。" else "还没有消息，输入内容开始任务。",
                targetMessageId = state.messageTarget,
                localSendRevision = state.localSendRevision,
                topContentPadding = if (hasHeader) 0.dp else topContentPadding,
                loadingOlder = state.loadingOlderMessages,
                olderLoadFailed = state.olderMessagesFailed,
                onMessagesBehindHeader = onMessagesBehindHeader,
            ) { turn ->
                    MessageTurnContent(
                        turn = turn,
                        mine = turn.senderId == userId,
                        senderName = turn.senderName.ifBlank { agents.find { it.id == turn.senderId }?.name.orEmpty() },
                        active = (activeTask || awaitingReply) && turn.key == currentReplyKey,
                        filesEnabled = !turn.pendingDelivery && !state.busy && state.exportFile == null,
                        onPreview = model::openAttachment,
                        onDownload = model::downloadAttachment,
                        attachmentFailure = state.attachmentFailure,
                        onQuote = model::quoteMessage,
                        onRegenerate = model::regenerate,
                        regenerateEnabled = !state.busy && !state.sending && !state.uploading && !state.preview,
                        stopping = stopping && turn.key == currentReplyKey,
                        showSentReceipt = turn.messageId == followups?.lastSentMessageId && turn.messageId > 0,
                    )
            }
        }
    }
}

internal fun PendingSend.asTurn(userId: String) = MessageTurn(
    key = "pending-send", senderId = userId, senderName = "", assistant = false,
    steps = emptyList(), plan = emptyList(), progress = emptyList(), text = listOf(draft.text).filter(String::isNotBlank),
    files = draft.attachments, executionState = "", streaming = false, quote = draft.quote, pendingDelivery = true,
)

/** Keep the live response's UI identity through waiting, streaming and persisted history. */
internal fun conversationDisplayTurns(turns: List<MessageTurn>, active: Boolean, agentId: String, agentName: String,
    awaitingReply: Boolean = false, followupMessageIds: Set<Long> = emptySet()): List<MessageTurn> {
    var responseKey = "response:initial"
    var firstReply = true
    var pendingAfter = -1
    val displayed = turns.mapIndexed { index, turn ->
        if (!turn.assistant) {
            val followup = turn.followup || turn.messageId in followupMessageIds
            // A follow-up can arrive before the original reply has produced its first frame.
            if (!followup || !firstReply) {
                responseKey = "response:${turn.key}"
                firstReply = true
            }
            if (!followup) pendingAfter = index
            turn
        } else {
            pendingAfter = -1
            if (firstReply) {
                firstReply = false
                turn.copy(key = responseKey)
            } else turn
        }
    }
    if ((!active && !awaitingReply) || (pendingAfter < 0 && turns.isNotEmpty())) return displayed
    // A presentation-only waiting slot. It carries no message ID, body or persisted record.
    return displayed.toMutableList().apply {
        add(pendingAfter + 1, MessageTurn(responseKey, agentId, agentName, true, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            if (active) "" else "awaiting_reply", false))
    }
}

@Composable
fun MarkdownContent(text: String) {
    val context = LocalContext.current
    val foreground = markdownForeground().toArgb()
    val link = MaterialTheme.colorScheme.primary.toArgb()
    val border = MaterialTheme.colorScheme.outlineVariant.toArgb()
    val code = MaterialTheme.colorScheme.onSurface.copy(alpha = .055f).toArgb()
    val density = LocalDensity.current.density
    val renderer = remember(context, foreground, link, border, code, density) {
        Markwon.builder(context).usePlugin(TablePlugin.create(context)).usePlugin(SoftBreakAddsNewLinePlugin.create()).usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureTheme(builder: MarkwonTheme.Builder) {
                builder.headingTextSizeMultipliers(floatArrayOf(1.25f, 1.125f, 1.0625f, 1f, 1f, 1f))
                    .headingTypeface(CatsCoFonts.medium(context))
                    .codeTypeface(CatsCoFonts.regular(context))
                    .codeBlockTypeface(CatsCoFonts.regular(context))
                    .headingBreakHeight(0)
                    .linkColor(link)
                    .blockMargin((16 * density).toInt())
                    .blockQuoteWidth((2 * density).toInt())
                    .blockQuoteColor(border)
                    .codeTextColor(foreground)
                    .codeBackgroundColor(code)
                    .codeBlockTextColor(foreground)
                    .codeBlockBackgroundColor(code)
                    .thematicBreakColor(border)
                    .thematicBreakHeight(maxOf(1, density.toInt()))
            }
        }).build()
    }
    val docs = LocalMarkdownDocuments.current ?: remember { MarkdownDocuments() }
    val ready = remember(docs, text) { docs.cached(text) ?: if (text.length < 1200) docs.parse(text) else null }
    if (ready != null) {
        MarkdownBlocks(renderer, ready)
    } else {
        val parsed by produceState<List<Node>?>(null, docs, text) {
            value = withContext(Dispatchers.Default) { docs.parse(text) }
        }
        parsed?.let { MarkdownBlocks(renderer, it) } ?: Spacer(Modifier.height(25.dp))
    }
}

@Composable
private fun markdownForeground() = MaterialTheme.colorScheme.onSurface.copy(
    alpha = if (MaterialTheme.colorScheme.background.luminance() < .5f) .86f else 1f,
)

@Composable
private fun MarkdownBlocks(renderer: Markwon, blocks: List<Node>, spacing: Dp = 14.dp) {
    Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
        blocks.forEachIndexed { index, block ->
            if (block is ThematicBreak) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp), thickness = .7.dp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f))
            } else if (block is FencedCodeBlock) {
                MessageCodeBlock(block.literal.trimEnd(), fontSize = 14.sp)
            } else if (block is IndentedCodeBlock) {
                MessageCodeBlock(block.literal.trimEnd(), fontSize = 14.sp)
            } else if (block is BulletList || block is OrderedList) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    block.children().forEachIndexed { itemIndex, item ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            val prefix = if (block is OrderedList) "${block.startNumber + itemIndex}." else "•"
                            Text(
                                text = prefix,
                                modifier = Modifier.widthIn(min = if (block is OrderedList) 23.dp else 13.dp),
                                fontSize = 16.sp,
                                lineHeight = 25.sp,
                                color = markdownForeground(),
                                style = TextStyle(
                                    fontFamily = CatsCoFontFamily,
                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Top, LineHeightStyle.Trim.FirstLineTop),
                                ),
                            )
                            Box(Modifier.weight(1f)) { MarkdownBlocks(renderer, item.children(), spacing = 8.dp) }
                        }
                    }
                }
            } else if (block is TableBlock) {
                MarkdownTable(renderer, block)
            } else {
                val headingSpacing = if (block is Heading && index > 0) Modifier.padding(top = 9.dp, bottom = 2.dp) else Modifier
                MarkdownText(renderer, block, Modifier.fillMaxWidth().then(headingSpacing))
            }
        }
    }
}

@Composable
private fun MarkdownTable(renderer: Markwon, block: TableBlock) {
    val rows = remember(block) {
        block.children().flatMap { section ->
            val children = section.children()
            val entries = if (children.firstOrNull() is TableCell) listOf(section) else children
            entries.map { (section is TableHead) to it.children() }
        }
    }
    var visibleRows by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(24) }
    val density = LocalDensity.current
    val fontPixels = with(density) { 15.sp.toPx() }
    val context = LocalContext.current
    val baseWidths = remember(rows, fontPixels, density.density, context) {
        val paint = android.text.TextPaint().apply { textSize = fontPixels; typeface = CatsCoFonts.regular(context) }
        val columns = rows.maxOfOrNull { it.second.size } ?: 0
        List(columns) { column ->
            val measured = rows.take(20).maxOfOrNull { (_, cells) ->
                paint.measureText(cells.getOrNull(column)?.plainText().orEmpty().take(300)) / density.density + 24f
            } ?: 64f
            measured.coerceIn(64f, 280f)
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val measured = if (baseWidths.sum() > maxWidth.value && baseWidths.size > 2) {
            baseWidths.mapIndexed { index, width -> if (index == 0) width.coerceAtMost(maxWidth.value * .58f) else width }
        } else baseWidths
        val widths = tableColumnWidths(measured, maxWidth.value).map { it.dp }
        val total = widths.fold(0.dp) { sum, width -> sum + width }
        val overflows = total > maxWidth
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            if (overflows) Text("左右滑动查看完整表格", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                rows.take(visibleRows).forEach { (header, cells) ->
                    Row(Modifier.width(total).background(if (header) MaterialTheme.colorScheme.onSurface.copy(alpha = .06f) else MaterialTheme.colorScheme.surface)) {
                        cells.forEachIndexed { column, cell ->
                            MarkdownText(renderer, cell, Modifier.width(widths[column]).padding(horizontal = 10.dp, vertical = 8.dp))
                        }
                    }
                    HorizontalDivider(Modifier.width(total), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            if (visibleRows < rows.size) TextButton(onClick = { visibleRows += 24 }) {
                Text("显示接下来的 ${minOf(24, rows.size - visibleRows)} 行")
            }
        }
    }
}

/** Keep short fields at their intrinsic width and give wrapping prose the remaining space. */
internal fun tableColumnWidths(natural: List<Float>, available: Float): List<Float> {
    if (natural.isEmpty()) return emptyList()
    val minimum = natural.map { it.coerceAtMost(128f) }
    val floorTotal = minimum.sum()
    if (floorTotal > available) return natural
    val total = natural.sum()
    if (total <= available) {
        val widest = natural.indices.maxBy { natural[it] }
        return natural.mapIndexed { index, width -> width + if (index == widest) available - total else 0f }
    }
    val spare = available - floorTotal
    val flexible = total - floorTotal
    return natural.mapIndexed { index, width -> minimum[index] + spare * (width - minimum[index]) / flexible }
}

private fun Node.plainText(): String = when (this) {
    is MarkdownLiteral -> literal
    is Code -> literal
    else -> children().joinToString("") { it.plainText() }
}

private val searchSnippetParser = org.commonmark.parser.Parser.builder().build()

internal fun searchSnippetText(text: String): String = searchSnippetParser.parse(text).children()
    .joinToString(" ") { it.plainText() }.replace(Regex("\\s+"), " ").trim()

private fun Node.children(): List<Node> = buildList {
    var child = firstChild
    while (child != null) {
        add(child)
        child = child.next
    }
}

@Composable
private fun MarkdownText(renderer: Markwon, node: Node, modifier: Modifier) {
    val context = LocalContext.current
    val styled = remember(renderer, node, context) {
        val rendered = if (node is TableCell) android.text.SpannableStringBuilder().apply {
            node.children().forEach { append(renderer.render(it)) }
        } else renderer.render(node)
        readableMessageSpans(rendered.trim(), context)
    }
    val color = markdownForeground().toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val lineHeight = with(LocalDensity.current) { (if (node is TableCell) 22.sp else 25.sp).roundToPx() }
    val openSidebar = if (node is TableCell) null else LocalOpenSidebar.current
    AndroidView(
        modifier = modifier,
        factory = { context -> MessageTextView(context).apply {
            textSize = 16f
            includeFontPadding = false
            setTextIsSelectable(true)
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            setPadding(0, 0, 0, 0)
        } },
        update = { view ->
            view.onOpenSidebar = openSidebar
            view.textSize = if (node is TableCell) 15f else 16f
            view.setTextColor(color)
            view.setLinkTextColor(linkColor)
            androidx.core.widget.TextViewCompat.setLineHeight(view, lineHeight)
            view.typeface = if (node is TableCell && node.isHeader) CatsCoFonts.bold(view.context) else CatsCoFonts.regular(view.context)
            if (view.tag !== styled) {
                val content = android.text.SpannableStringBuilder(styled)
                android.text.util.Linkify.addLinks(content, android.text.util.Linkify.WEB_URLS)
                view.text = content
                view.tag = styled
            }
        },
    )
}

/** Allow the surrounding timeline/table to scroll while retaining native selection and links. */
internal class MessageTextView(context: android.content.Context) : android.widget.TextView(context) {
    var onOpenSidebar: (() -> Unit)? = null
    private var touchX = 0f
    private var touchY = 0f
    private var dragged = false
    private var verticalDrag = false
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    // TextView's onTouchEvent implements native click, link and selection accessibility.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            touchX = event.x
            touchY = event.y
            dragged = false
            verticalDrag = false
        }
        val deltaX = event.x - touchX
        val deltaY = event.y - touchY
        if (!dragged && kotlin.math.abs(deltaY) > touchSlop && kotlin.math.abs(deltaY) > kotlin.math.abs(deltaX)) verticalDrag = true
        dragged = dragged || kotlin.math.abs(event.x - touchX) > touchSlop || kotlin.math.abs(event.y - touchY) > touchSlop
        if (event.actionMasked == android.view.MotionEvent.ACTION_UP && !verticalDrag && !hasSelection() &&
            deltaX >= 48f * resources.displayMetrics.density && deltaX > kotlin.math.abs(deltaY) * 1.3f && onOpenSidebar != null) {
            val cancel = android.view.MotionEvent.obtain(event).apply { action = android.view.MotionEvent.ACTION_CANCEL }
            try { super.onTouchEvent(cancel) } finally { cancel.recycle() }
            onOpenSidebar?.invoke()
            return true
        }
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == android.view.MotionEvent.ACTION_MOVE && dragged && !hasSelection()) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return handled
    }
}

internal fun readableMessageSpans(text: CharSequence, context: android.content.Context): CharSequence {
    val result = android.text.SpannableStringBuilder(text)
    val emphasis = result.getSpans(0, result.length, Any::class.java).filter { span ->
        span is io.noties.markwon.core.spans.StrongEmphasisSpan ||
            span is android.text.style.StyleSpan && span.style == android.graphics.Typeface.BOLD
    }
    emphasis.forEach { span ->
        val start = result.getSpanStart(span)
        val end = result.getSpanEnd(span)
        if (end - start >= 48) {
            val flags = result.getSpanFlags(span)
            result.removeSpan(span)
            result.setSpan(CatsCoTypefaceSpan(CatsCoFonts.medium(context)), start, end, flags)
        } else {
            result.setSpan(CatsCoTypefaceSpan(CatsCoFonts.bold(context)), start, end, result.getSpanFlags(span))
        }
    }
    return result
}
