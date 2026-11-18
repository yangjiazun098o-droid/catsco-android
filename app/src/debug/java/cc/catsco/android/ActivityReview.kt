package cc.catsco.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.catsco.android.data.MessageTurn
import cc.catsco.android.data.MessageWorkStep
import cc.catsco.android.ui.*
import kotlinx.coroutines.delay

/** Native component samples only. No account, message send or stop request is created here. */
@Composable
internal fun ColumnScope.ActivityReview() {
    var phase by rememberSaveable { mutableStateOf("thinking") }
    var draft by rememberSaveable { mutableStateOf("") }
    var replay by remember { mutableIntStateOf(0) }
    var previewText by remember { mutableStateOf("") }
    val answer = "先统一运行反馈，再核对输入栏的操作与停止状态。\n\n加载气泡会自然展开，正文接着流式出现。"
    val running = phase in setOf("thinking", "working", "writing")
    val stopping = phase == "stopping"
    LaunchedEffect(phase) {
        if (stopping) { delay(650); phase = "stopped" }
    }
    LaunchedEffect(replay) {
        if (replay > 0) {
            phase = "thinking"
            previewText = ""
            delay(1200)
            phase = "writing"
            for (end in 16..answer.length step 4) {
                previewText = answer.take(end)
                delay(75)
            }
            previewText = answer
            phase = "completed"
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf("thinking" to "思考", "working" to "执行", "writing" to "输出", "completed" to "完成").forEach { (value, label) ->
            TextButton(onClick = { replay = 0; previewText = answer; phase = value }) { Text(label, fontSize = 13.sp) }
        }
    }
    TextButton(onClick = { replay++ }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("重播气泡展开", fontSize = 13.sp) }
    Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Text("请整理今天的讨论。", fontSize = 16.sp)
        MessageTurnContent(
            turn = MessageTurn("activity-review", "9", "自迭代", true,
                steps = if (phase == "working") listOf(MessageWorkStep("review", "tool_use", "read_file", description = "核对讨论记录")) else emptyList(),
                plan = emptyList(), progress = emptyList(),
                text = if (phase in setOf("writing", "completed", "stopped", "stopping")) listOf(previewText.ifBlank { answer }) else emptyList(),
                files = emptyList(), executionState = if (running) "" else phase, streaming = phase == "writing"),
            mine = false, senderName = "自迭代", active = running,
            filesEnabled = false, onPreview = {}, onDownload = {}, stopping = stopping,
        )
    }
    Box(Modifier.padding(horizontal = 10.dp, vertical = 12.dp)) {
        CatsCoComposerFrame {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(draft, { draft = it }, Modifier.weight(1f).heightIn(min = 48.dp).padding(vertical = 11.dp),
                    textStyle = LocalTextStyle.current.copy(fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface),
                    decorationBox = { inner -> if (draft.isEmpty()) Text("示例输入", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); inner() })
                ComposerSendButton(running, stopping, draft.isNotBlank(),
                    onSend = { draft = ""; replay++; phase = "thinking" }, onStop = { replay = 0; phase = "stopping" })
            }
        }
    }
}
