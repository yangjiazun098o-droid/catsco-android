package cc.catsco.android.data

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.annotation.RequiresPermission
import cc.catsco.core.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import kotlin.math.sqrt

enum class VoiceCapture { Connecting, Recording, Finalizing, Ready }
internal enum class VoiceDestination { Edit, Send }

internal const val VoiceCharacterLimit = 4_000

internal fun String.voiceCharacterCount(): Int = codePointCount(0, length)

internal fun String.takeVoiceCharacters(max: Int): String =
    if (voiceCharacterCount() <= max) this else substring(0, offsetByCodePoints(0, max))

internal class VoiceTranscriptionFailure(message: String) : Exception(message)

internal class VoiceTranscription(private val repository: HttpWorkspaceRepository) {
    private sealed interface Input {
        data class Audio(val frame: ByteString?) : Input
        data class Server(val text: String) : Input
    }

    private data class Segment(val text: String, val unsent: ByteString?, val reachedLimit: Boolean = false)

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun recognize(
        session: Session,
        stop: CompletableDeferred<Unit>,
        characterLimit: Int,
        onAudioReady: () -> Unit,
        onAudioStopped: () -> Unit,
        onLevel: (Float) -> Unit,
        onText: (String) -> Unit,
    ): String = coroutineScope {
        // One microphone stream feeds successive recognition sessions. Keep the PCM
        // queued while a socket opens or the preceding segment finalizes.
        val frames = Channel<ByteString>(120)
        val capture = launch(Dispatchers.IO) {
            try {
                record(frames, stop, onAudioReady, onLevel)
                withContext(Dispatchers.Main.immediate) { onAudioStopped() }
                frames.close()
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (failure: Exception) {
                frames.close(failure)
            }
        }
        try {
            recognizeFrames(session, frames, stop, characterLimit, onText)
        } finally {
            capture.cancel()
            frames.cancel()
        }
    }

    internal suspend fun recognizeFrames(
        session: Session,
        frames: Channel<ByteString>,
        stop: CompletableDeferred<Unit>,
        characterLimit: Int,
        onText: (String) -> Unit,
    ): String {
        var transcript = ""
        var firstFrame = frames.receiveCatching().getOrThrowUnlessClosed()
        while (firstFrame != null) {
            val prefix = if (transcript.isBlank()) "" else "$transcript\n"
            val remaining = characterLimit - prefix.voiceCharacterCount()
            if (remaining <= 0) break
            val segment = recognizeSegment(session, frames, firstFrame, stop, remaining) { text ->
                onText(if (text.isBlank()) transcript else prefix + text.takeVoiceCharacters(remaining))
            }
            if (segment.text.isNotBlank()) {
                transcript = prefix + segment.text.trim().takeVoiceCharacters(remaining)
                onText(transcript)
            }
            if (segment.reachedLimit || transcript.voiceCharacterCount() >= characterLimit) break
            firstFrame = segment.unsent ?: frames.receiveCatching().getOrThrowUnlessClosed()
        }
        return transcript
    }

    private suspend fun recognizeSegment(
        session: Session,
        frames: Channel<ByteString>,
        firstFrame: ByteString,
        stop: CompletableDeferred<Unit>,
        characterLimit: Int,
        onText: (String) -> Unit,
    ): Segment {
        val admission = withTimeoutOrNull(12_000) { admit(session) }
            ?: throw VoiceTranscriptionFailure("连接语音服务超时，请重试。")
        val ticket = admission.optString("ticket").takeIf(String::isNotBlank)
            ?: throw VoiceTranscriptionFailure("无法启动语音服务，请重试。")
        val url = session.server.value.toHttpUrl().newBuilder().encodedPath("/api/stt/realtime")
            .addQueryParameter("ticket", ticket).build()
        // The socket URL contains only the short-lived single-use STT ticket.
        val messages = Channel<String>(64)
        val socket = repository.client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (messages.trySend(text).isFailure) webSocket.cancel()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                messages.close(VoiceTranscriptionFailure("语音服务已断开，请重试。"))
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                messages.close(VoiceTranscriptionFailure("语音服务连接失败，请重试。"))
            }
        })

        try {
            val ready = withTimeoutOrNull(12_000) { receiveReady(messages) }
                ?: throw VoiceTranscriptionFailure("连接语音服务超时，请重试。")
            val duration = minOf(limitMillis(admission), limitMillis(ready))
            val boundary = duration - minOf(750L, duration / 8)
            val deadline = SystemClock.elapsedRealtime() + boundary
            val maxBytes = boundary * 32L
            val text = SegmentText()
            var sent = 0L
            var unsent: ByteString? = firstFrame

            while (true) {
                val timeLeft = deadline - SystemClock.elapsedRealtime()
                val pending = unsent
                if (pending != null) {
                    if (sent > 0 && sent + pending.size > maxBytes) {
                        return Segment(finishSegment(socket, messages, text, durationBoundary = true, onText = onText), pending)
                    }
                    sendAudio(socket, pending)
                    sent += pending.size
                    unsent = null
                }
                val input = withTimeoutOrNull(timeLeft.coerceAtLeast(1)) {
                    select<Input> {
                        messages.onReceive { Input.Server(it) }
                        frames.onReceiveCatching { Input.Audio(it.getOrThrowUnlessClosed()) }
                    }
                }
                when (input) {
                    null -> return Segment(finishSegment(socket, messages, text, durationBoundary = true, onText = onText), null)
                    is Input.Audio -> {
                        unsent = input.frame ?: return Segment(finishSegment(socket, messages, text, durationBoundary = false, onText = onText), null)
                    }
                    is Input.Server -> {
                        if (text.receive(input.text, onText)) return Segment(text.preview, null)
                        if (text.preview.voiceCharacterCount() >= characterLimit) {
                            stop.complete(Unit)
                            return Segment(finishSegment(socket, messages, text, durationBoundary = false, onText = onText)
                                .takeVoiceCharacters(characterLimit), null, reachedLimit = true)
                        }
                    }
                }
            }
        } finally {
            socket.cancel()
            messages.cancel()
        }
    }

    private suspend fun admit(session: Session): JSONObject {
        // The previous socket can send its final result just before releasing admission.
        for (attempt in 0..5) {
            try {
                return repository.api(session.server, "/api/stt/sessions", session.token, method = "POST")
            } catch (failure: ApiFailure) {
                if (failure.status != 409 || attempt == 5) throw failure
                delay(200)
            }
        }
        error("Speech admission retry exhausted")
    }

    private suspend fun receiveReady(messages: Channel<String>): JSONObject {
        while (true) {
            val event = JSONObject(messages.receive())
            when (event.optString("type")) {
                "ready" -> return event
                "error" -> throw serviceError(event)
            }
        }
    }

    private suspend fun finishSegment(
        socket: WebSocket,
        messages: Channel<String>,
        text: SegmentText,
        durationBoundary: Boolean,
        onText: (String) -> Unit,
    ): String {
        socket.send(if (durationBoundary) """{"type":"stop","stop_reason":"duration_limit"}""" else """{"type":"stop"}""")
        val finalized = withTimeoutOrNull(7_000) {
            while (!text.receive(messages.receive(), onText)) {
                currentCoroutineContext().ensureActive()
            }
            text.preview
        }
        return finalized ?: throw VoiceTranscriptionFailure("转写等待超时，请重试。")
    }

    private class SegmentText {
        private var stable = ""
        var preview = ""
            private set

        fun receive(raw: String, onText: (String) -> Unit): Boolean {
            val event = JSONObject(raw)
            val text = event.optString("text")
            when (event.optString("type")) {
                "partial" -> preview = stable + text
                "definite" -> {
                    if (text.isNotBlank()) stable = text
                    preview = stable
                }
                "final" -> {
                    preview = text.ifBlank { preview }
                    return true
                }
                "error" -> throw serviceError(event)
                else -> return false
            }
            onText(preview)
            return false
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun record(
        frames: Channel<ByteString>,
        stop: CompletableDeferred<Unit>,
        onAudioReady: () -> Unit,
        onLevel: (Float) -> Unit,
    ) {
        val sampleRate = 16_000
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val frame = ShortArray(1_600) // 100 ms at 16 kHz.
        val bytes = ByteArray(frame.size * 2)
        val minimumBuffer = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
        if (minimumBuffer <= 0) throw VoiceTranscriptionFailure("麦克风暂时不可用，请重试。")
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, channel, encoding, maxOf(minimumBuffer, bytes.size * 2))
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) throw VoiceTranscriptionFailure("麦克风暂时不可用，请重试。")
            currentCoroutineContext().ensureActive()
            if (stop.isCompleted) return
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw VoiceTranscriptionFailure("无法开始录音，请检查麦克风。")
            withContext(Dispatchers.Main.immediate) { onAudioReady() }
            var filled = 0
            while (!stop.isCompleted) {
                currentCoroutineContext().ensureActive()
                val count = recorder.read(frame, filled, frame.size - filled, AudioRecord.READ_NON_BLOCKING)
                if (count < 0) throw VoiceTranscriptionFailure("录音已中断，请重试。")
                if (count == 0) {
                    delay(12)
                    continue
                }
                filled += count
                if (filled == frame.size) {
                    val level = publishFrame(frames, frame, bytes, filled)
                    withContext(Dispatchers.Main.immediate) { onLevel(level) }
                    filled = 0
                }
            }
            if (filled > 0) publishFrame(frames, frame, bytes, filled)
        } finally {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) runCatching { recorder.stop() }
            recorder.release()
        }
    }

    private fun publishFrame(frames: Channel<ByteString>, frame: ShortArray, bytes: ByteArray, size: Int): Float {
        var energy = 0.0
        for (index in 0 until size) {
            val sample = frame[index].toInt()
            bytes[index * 2] = sample.toByte()
            bytes[index * 2 + 1] = (sample shr 8).toByte()
            val normalized = sample / 32768.0
            energy += normalized * normalized
        }
        if (frames.trySend(bytes.toByteString(0, size * 2)).isFailure) {
            throw VoiceTranscriptionFailure("网络连接不稳定，录音已停止。")
        }
        return (sqrt(energy / size) * 7).toFloat().coerceIn(0f, 1f)
    }

    private fun sendAudio(socket: WebSocket, frame: ByteString) {
        if (socket.queueSize() > 160_000 || !socket.send(frame)) throw VoiceTranscriptionFailure("网络连接不稳定，请重试。")
    }

    private fun <T> kotlinx.coroutines.channels.ChannelResult<T>.getOrThrowUnlessClosed(): T? {
        exceptionOrNull()?.let { throw it }
        return getOrNull()
    }

    private companion object {
        fun serviceError(event: JSONObject) = VoiceTranscriptionFailure(when (event.optString("code")) {
            "session_active" -> "已有语音转写正在进行，请稍后重试。"
            else -> "语音识别服务暂时不可用，请重试。"
        })

        fun limitMillis(event: JSONObject): Long = event.optLong("max_session_ms").takeIf { it > 0 }
            ?: event.optInt("max_session_seconds").takeIf { it > 0 }?.times(1000L) ?: 150_000L
    }
}
