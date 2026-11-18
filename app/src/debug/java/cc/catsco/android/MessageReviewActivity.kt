package cc.catsco.android

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.catsco.android.data.chatMessage
import cc.catsco.android.data.presentMessages
import cc.catsco.android.ui.CatsCoTheme
import cc.catsco.android.ui.MessageTurnContent
import org.json.JSONArray
import org.json.JSONObject

/** Explicit debug gallery. It never creates a workspace model or accesses an account. */
class MessageReviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var dark by rememberSaveable { mutableStateOf(intent.getBooleanExtra("dark", false)) }
            var sample by rememberSaveable { mutableStateOf(intent.getStringExtra("sample") ?: "task") }
            val list = rememberLazyListState()
            val turns = remember(sample) { presentMessages(reviewMessages(sample).map(::chatMessage), "1", setOf("9")) }
            SideEffect {
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !dark
            }
            LaunchedEffect(sample) { list.scrollToItem(0) }
            CatsCoTheme(dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("消息组件预览 · 示例", Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { dark = !dark }) { Text(if (dark) "浅色" else "深色") }
                        }
                        Row(Modifier.padding(horizontal = 8.dp)) {
                            TextButton(onClick = { sample = "task" }) { Text("任务消息") }
                            TextButton(onClick = { sample = "text" }) { Text("正文排版") }
                            TextButton(onClick = { sample = "activity" }) { Text("生成动效") }
                        }
                        if (sample == "activity") ActivityReview()
                        else LazyColumn(state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                            items(turns, key = { "$sample:${it.key}" }) { turn ->
                                MessageTurnContent(turn, turn.senderId == "1", turn.senderName, false, true,
                                    onPreview = { Toast.makeText(this@MessageReviewActivity, "示例文件，仅展示组件", Toast.LENGTH_SHORT).show() },
                                    onDownload = { Toast.makeText(this@MessageReviewActivity, "示例文件，仅展示组件", Toast.LENGTH_SHORT).show() },
                                    onQuote = { Toast.makeText(this@MessageReviewActivity, "示例引用", Toast.LENGTH_SHORT).show() },
                                    onRegenerate = { Toast.makeText(this@MessageReviewActivity, "示例操作", Toast.LENGTH_SHORT).show() })
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun reviewMessages(sample: String): List<JSONObject> {
    fun record(id: Int, sender: Int, text: String) = JSONObject().put("id", id).put("from_uid", sender)
        .put("from_name", if (sender == 9) "理中客" else "用户").put("content", text)
        .put("metadata", JSONObject().put("turn_id", "review-$sample"))
    if (sample == "text") return listOf(
        record(1, 1, "请展示正文、标题、引用和表格的排版。"),
        record(2, 9, "这是一段消息正文，**重点内容通过字重突出**，并保持一致的阅读行距。\n\n---\n\n## 清晰的标题层级\n\n标题与正文保留明确间距，较长的内容可以自然换行。\n\n> 引用内容采用浅色引导线，文字保持完整可读。\n\n### 列表与代码\n\n- 工具过程可以展开查看\n- 计划始终保持可见\n- 文件提供预览和下载\n\n```kotlin\nval title = \"CatsCo\"\nprintln(title)\n```\n\n### 分项说明\n\n1. **标题与正文分层**\n   每一项的说明从正文起始位置向下排列，较长的文字换行后与上一行内容对齐。\n\n2. **段落之间留出空间**\n   独立观点采用独立段落，让连续阅读时能够清楚识别每一项内容。\n\n| 组件 | 字号 | 状态 |\n| --- | --- | --- |\n| 正文 | 16sp | 可选择复制 |\n| 计划 | 14sp | 保留进度 |"),
    )
    val records = mutableListOf(record(1, 1, "这些文献都用上了吗，你仔细检查一遍，是否有效，是否有引用依据"))
    val blocks = JSONArray()
    listOf("execute_shell", "execute_shell", "execute_shell", "grep", "read_file").forEachIndexed { index, name ->
        val description = listOf("读取文献目录", "核对引用位置", "生成核查报告", "搜索缺失引用", "读取证据对照表")[index]
        blocks.put(JSONObject().put("type", "tool_use").put("id", "tool-$index").put("name", name)
            .put("input", JSONObject().put("description", description).put(if (name == "read_file") "file_path" else "command", if (name == "read_file") "/workspace/references/evidence.md" else "python3 /workspace/references/check.py --part ${index + 1}")))
        blocks.put(JSONObject().put("type", "tool_result").put("tool_use_id", "tool-$index").put("content",
            if (index == 3) "Command completed\nstatus: failed\nexit_code: 1\nstdout:\n(empty)\nstderr:\n示例文件暂不可读取" else "示例核查结果\n条目已读取，引用位置已记录。"))
    }
    val steps = JSONArray()
    listOf("用 Europe PMC 核实关键训练变量", "按证据重排三分化方案", "读完 9 篇全文并修正 4 处引用", "逐条核查每处引用的依据是否成立、是否有文献未被使用", "补引用对照表并重发文件").forEachIndexed { index, text ->
        steps.put(JSONObject().put("text", text).put("status", if (index < 3) "completed" else if (index == 3) "in_progress" else "pending"))
    }
    blocks.put(JSONObject().put("type", "tool_use").put("id", "plan").put("name", "update_plan").put("input", JSONObject().put("steps", steps)))
    blocks.put(JSONObject().put("type", "tool_result").put("tool_use_id", "plan").put("content", "计划已更新"))
    blocks.put(JSONObject().put("type", "file").put("payload", JSONObject().put("name", "fitness-plan-v2-evidence.html").put("url", "https://example.invalid/review.html").put("mime_type", "text/html").put("size", 48640)))
    blocks.put(JSONObject().put("type", "text").put("text", "检查完了。**11 篇文献都用上了**（逐篇确认都在正文出现），但查出来 **6 处问题**，其中 1 处是事实错误。已经全部修正，文件重新发给你了。"))
    records.add(record(2, 9, "").put("content_blocks", blocks).put("metadata", JSONObject().put("turn_id", "review-task").put("execution_state", "completed")))
    return records
}
