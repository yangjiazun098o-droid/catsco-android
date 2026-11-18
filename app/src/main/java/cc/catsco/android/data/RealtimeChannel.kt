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
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    fun connect(session: Session) {
        disconnect()
        val epoch = generation
        fun deliver(block: () -> Unit) {
            scope.launch { if (epoch == generation) block() }
        }
        fun open(attempt: Int) {
            if (epoch != generation) return
            onState(if (attempt == 0) "连接中" else "重新连接中")
            val request = Request.Builder().url(session.server.value + "/v0/channels")
                .header("Authorization", "Bearer ${session.token}").build()
            socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (epoch != generation) { webSocket.cancel(); return }
                    deliver {
                        connected = true
                        webSocket.send(JSONObject().put("hi", JSONObject().put("id", UUID.randomUUID().toString()).put("ver", "0.1.0")
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
                            connected = false
                            pending.values.forEach { it.completeExceptionally(IllegalStateException("实时连接已断开，请刷新任务状态。")) }
                            pending.clear()
                            onState("重新连接中")
                            retry?.cancel()
                            retry = scope.launch { delay((1L shl attempt.coerceAtMost(5)) * 1000); open(attempt + 1) }
                        }
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (response?.code == 401) deliver { disconnect(); onUnauthorized() } else reconnect(webSocket)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = reconnect(webSocket)
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

    suspend fun stop(topic: String, agentId: String): Boolean {
        if (!connected) return false
        val id = UUID.randomUUID().toString()
        val waiter = CompletableDeferred<JSONObject>()
        pending[id] = waiter
        val metadata = JSONObject().put("stream_id", "cancel-$id").put("stream_event", "cancel").put("control", "interrupt")
        agentId.toLongOrNull()?.let { metadata.put("target_bot_uid", it) }
        val message = JSONObject().put("pub", JSONObject().put("id", id).put("topic", topic).put("type", "stream_cancel")
            .put("msg_type", "stream_cancel").put("content", "").put("metadata", metadata))
        try {
            check(socket?.send(message.toString()) == true) { "停止请求未发送，请检查连接。" }
            withTimeout(10_000) { waiter.await() }
        } finally { pending.remove(id) }
        return true
    }

    fun disconnect() {
        generation++
        retry?.cancel(); retry = null
        socket?.cancel(); socket = null; connected = false
        pending.values.forEach { it.cancel() }; pending.clear()
        onState("未连接")
    }
}
