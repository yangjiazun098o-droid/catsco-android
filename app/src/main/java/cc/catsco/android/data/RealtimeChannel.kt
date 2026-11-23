package cc.catsco.android.data

import cc.catsco.core.Session
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class RealtimeChannel(
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val onState: (String) -> Unit,
    private val onEvent: (JSONObject) -> Unit,
    private val onUnauthorized: () -> Unit,
) {
    @Volatile private var generation = 0
    @Volatile private var socket: WebSocket? = null
    private var retry: Job? = null
    @Volatile private var activeTopic = ""
    @Volatile private var connected = false
    @Volatile private var runScopedStop = false
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    fun connect(session: Session) {
        disconnect()
        val epoch = generation
        fun deliver(block: () -> Unit) {
            scope.launch { if (epoch == generation) block() }
        }
        fun open(attempt: Int) {
            if (epoch != generation) return
            val handshakeId = UUID.randomUUID().toString()
            runScopedStop = false
            onState(if (attempt == 0) "连接中" else "正在重连")
            val request = Request.Builder().url(session.server.value + "/v0/channels")
                .header("Authorization", "Bearer ${session.token}").build()
            socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (epoch != generation) { webSocket.cancel(); return }
                    deliver {
                        connected = true
                        webSocket.send(JSONObject().put("hi", JSONObject().put("id", handshakeId).put("ver", "0.1.0")
                            .put("visibility", "visible").put("focused", true).put("active_topic", activeTopic)).toString())
                        onState("已连接")
                        onEvent(JSONObject().put("_type", "ws_open"))
                    }
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (epoch != generation || text.length > 8 * 1024 * 1024) return
                    val event = runCatching { JSONObject(text) }.getOrNull() ?: return
                    deliver {
                        val control = event.optJSONObject("ctrl")
                        if (control?.optInt("code") == 401) {
                            disconnect(); onUnauthorized()
                        } else {
                            control?.let {
                                if (it.optString("id") == handshakeId && it.optInt("code") in 200..299) {
                                    val features = it.optJSONObject("params")?.optJSONArray("features")
                                    runScopedStop = features != null && (0 until features.length()).any { index ->
                                        features.optString(index) == "run_scoped_stop.v1"
                                    }
                                }
                                pending.remove(it.optString("id"))?.let { waiter ->
                                    if (it.optInt("code") >= 400) waiter.completeExceptionally(ApiFailure(it.optInt("code"))) else waiter.complete(it)
                                }
                            }
                            onEvent(event)
                        }
                    }
                }
                private fun reconnect(webSocket: WebSocket) {
                    deliver {
                        if (socket === webSocket) {
                            val retryAttempt = if (connected) 0 else attempt
                            connected = false
                            pending.values.forEach { it.completeExceptionally(IllegalStateException("实时连接已断开，请刷新任务状态。")) }
                            pending.clear()
                            onState("正在重连")
                            retry?.cancel()
                            retry = scope.launch { delay((1L shl retryAttempt.coerceAtMost(5)) * 1000); open(retryAttempt + 1) }
                        }
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (response?.code == 401) deliver { disconnect(); onUnauthorized() } else reconnect(webSocket)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = reconnect(webSocket)
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(if (code == 1005) 1000 else code, null)
                }
            })
        }
        open(0)
    }

    fun attention(topic: String) {
        activeTopic = topic
        socket?.send(JSONObject().put("note", JSONObject().put("what", "attention").put("visibility", "visible").put("focused", true).put("active_topic", topic)).toString())
    }

    fun read(topic: String, sequence: Long) {
        if (sequence > 0) socket?.send(JSONObject().put("note", JSONObject().put("topic", topic).put("what", "read").put("seq", sequence)).toString())
    }

    suspend fun stop(topic: String, agentId: String, expectedRunId: String): Boolean {
        if (!connected || (expectedRunId.isNotEmpty() && !runScopedStop)) return false
        val id = UUID.randomUUID().toString()
        val waiter = CompletableDeferred<JSONObject>()
        pending[id] = waiter
        val metadata = JSONObject().put("stream_id", "cancel-$id").put("stream_event", "cancel").put("control", "interrupt")
        agentId.toLongOrNull()?.let { metadata.put("target_bot_uid", it) }
        if (expectedRunId.isNotEmpty()) metadata.put("expected_run_id", expectedRunId)
        val message = JSONObject().put("pub", JSONObject().put("id", id).put("topic", topic).put("type", "stream_cancel")
            .put("msg_type", "stream_cancel").put("content", "").put("metadata", metadata))
        try {
            check(socket?.send(message.toString()) == true) { "停止请求未发送，请检查连接。" }
            val receipt = withTimeoutOrNull(10_000) { waiter.await() }
            val params = receipt?.optJSONObject("params")
            check(receipt?.optString("topic") == topic && params?.optString("stream_id") == "cancel-$id" &&
                (expectedRunId.isEmpty() || params.optString("expected_run_id") == expectedRunId)) {
                "停止请求尚未确认，请检查任务状态。"
            }
        } finally { pending.remove(id) }
        return true
    }

    fun disconnect() {
        generation++
        retry?.cancel(); retry = null
        socket?.cancel(); socket = null; connected = false
        pending.values.forEach { it.completeExceptionally(IllegalStateException("实时连接已断开，请检查任务状态。")) }; pending.clear()
        onState("未连接")
    }
}
