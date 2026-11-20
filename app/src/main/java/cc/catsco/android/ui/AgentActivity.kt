package cc.catsco.android.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.catsco.android.R
import cc.catsco.android.data.MessageTurn

internal fun agentActivityLabel(turn: MessageTurn, active: Boolean, stopping: Boolean): String? {
    if (!turn.assistant) return null
    if (stopping) return "正在停止"
    if (!active && !turn.streaming) return null
    return when (turn.executionState) {
        "failed", "completed", "replied", "cancelled", "stopped", "paused", "waiting_for_input", "disconnected" -> null
        "stopping", "stop-requested" -> "正在停止"
        "queued", "waiting" -> "等待执行"
        "awaiting_reply" -> "等待回复"
        else -> when {
            turn.text.isNotEmpty() -> "正在生成"
            turn.hasProcess -> "正在执行"
            turn.streaming -> "正在生成"
            else -> "正在思考"
        }
    }
}

/** The same two dots exchange places together before the next pair takes over. */
internal fun exchangeDotPositions(progress: Float): List<Offset> {
    val time = progress.coerceIn(0f, 1f) * 3.2f
    val order = IntArray(5) { it }
    val positions = MutableList(5) { Offset(it * 6f, 0f) }
    for (step in 0 until 8) {
        val upper = step < 4
        val pair = if (upper) step else 7 - step
        val start = if (upper) .16f + step * .32f else 1.76f + (step - 4) * .32f
        if (time < start) break
        if (time >= start + .32f) {
            val left = order[pair]
            order[pair] = order[pair + 1]
            order[pair + 1] = left
            positions[order[pair]] = Offset(pair * 6f, 0f)
            positions[order[pair + 1]] = Offset((pair + 1) * 6f, 0f)
            continue
        }
        val fraction = (time - start) / .32f
        val u = fraction * fraction * (3f - 2f * fraction)
        val v = 1f - u
        val from = (if (upper) pair + 1 else pair) * 6f
        val to = (if (upper) pair else pair + 1) * 6f
        val hook = if (upper) 4f else -4f
        val arcId = order[if (upper) pair + 1 else pair]
        val baselineId = order[if (upper) pair else pair + 1]
        positions[arcId] = Offset(
            v * v * v * from + 3f * v * v * u * (from + hook) + 3f * v * u * u * (to - hook) + u * u * u * to,
            (if (upper) -24f else 24f) * u * v,
        )
        positions[baselineId] = Offset(to + (from - to) * u, 0f)
        break
    }
    return positions
}

@Composable
internal fun AgentMessageHeader(name: String, status: String?) {
    val spokenStatus = status?.takeIf { it == "等待回复" || it == "正在思考" || it == "正在生成" }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Text(name, Modifier.weight(1f, fill = false).then(
            if (spokenStatus != null) Modifier.semantics { stateDescription = spokenStatus } else Modifier
        ), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
        if (status != null && spokenStatus == null) Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun AgentLoadingBubble() {
    AgentReplyBubble(loading = true)
}

@Composable
internal fun AgentExchangeDots() {
    val transition = rememberInfiniteTransition(label = "agentExchange")
    val progress = transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "exchangePhase",
    )
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(39.1.dp, 20.7.dp)) {
        val scale = size.width / 34f
        exchangeDotPositions(progress.value).forEach { point ->
            drawCircle(tint, radius = 2f * scale, center = Offset((5f + point.x) * scale, (9f + point.y) * scale))
        }
    }
}

@Composable
internal fun AgentPendingReply(name: String, stopping: Boolean) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AgentMessageHeader(name.ifBlank { "Agent" }, if (stopping) "正在停止" else "正在思考")
        if (!stopping) AgentLoadingBubble()
    }
}

@Composable
internal fun ComposerSendButton(running: Boolean, stopping: Boolean, sendEnabled: Boolean, onSend: () -> Unit, onStop: () -> Unit,
    hasDraft: Boolean,
    sending: Boolean = false, emphasized: Boolean = false, voiceMode: Boolean = false, voiceEnabled: Boolean = true, onVoice: () -> Unit = {},
    voiceHold: VoiceHoldCallbacks? = null, stopEnabled: Boolean = true) {
    val showStop = running || stopping
    val primaryStop = showStop && !hasDraft && !sending
    val canStop = running && !stopping && stopEnabled
    val useVoice = voiceMode && !showStop && !sending
    val enabled = when {
        primaryStop -> canStop
        sending || stopping -> false
        useVoice -> voiceEnabled
        else -> sendEnabled
    }
    val stopLabel = if (stopping) "正在停止" else "停止生成"
    val label = when {
        sending -> "正在发送"
        primaryStop -> stopLabel
        useVoice -> "语音输入"
        showStop -> "发送补充消息"
        else -> "发送任务"
    }
    val icon = when {
        primaryStop -> R.drawable.e_stop
        useVoice -> R.drawable.ic_composer_mic
        emphasized -> R.drawable.e_send_active
        else -> R.drawable.e_send
    }
    val hold = if (useVoice && enabled && voiceHold != null) Modifier.holdToTalk(voiceHold, onVoice) else Modifier
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (showStop && !primaryStop) {
            val tint = MaterialTheme.colorScheme.onSurfaceVariant
            CatsCoBoundedIconButton(
                onClick = onStop, enabled = canStop,
                visualSize = 36.dp,
                modifier = Modifier.size(48.dp).testTag("composer-stop-action").semantics {
                    contentDescription = stopLabel
                },
            ) {
                Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                    contentAlignment = Alignment.Center) {
                    if (stopping) CircularProgressIndicator(Modifier.size(15.dp), color = tint, strokeWidth = 1.5.dp)
                    else Icon(painterResource(R.drawable.e_stop), null, Modifier.size(18.dp), tint = tint)
                }
            }
        }
        CatsCoBoundedIconButton(
            onClick = when { primaryStop -> onStop; useVoice -> onVoice; else -> onSend }, enabled = enabled,
            visualSize = 44.dp, feedbackColor = Color.Black,
            modifier = Modifier.size(48.dp).then(hold).testTag("composer-primary-action").semantics { contentDescription = label },
        ) {
            Box(Modifier.size(44.dp).background(Mint.copy(alpha = if (enabled || sending) 1f else .45f), CircleShape),
                contentAlignment = Alignment.Center) {
                val visual = if (sending || (primaryStop && stopping)) null else icon
                Crossfade(visual, modifier = Modifier.size(24.dp), animationSpec = tween(150), label = "composerAction") { resource ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (resource == null) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Icon(painterResource(resource), null, Modifier.size(CatsCoMobileSpec.actionIconSize), tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}
