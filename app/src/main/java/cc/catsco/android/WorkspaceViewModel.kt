package cc.catsco.android

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cc.catsco.android.data.*
import cc.catsco.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

data class ResourceState(
    val loading: Boolean = false,
    val value: JSONObject = JSONObject(),
    val error: String? = null,
    val loaded: Boolean = false,
    val errorStatus: Int? = null,
    val query: String = "",
)
data class OpenLocalFile(val uri: Uri, val mime: String, val name: String = "附件")
data class AgentSettingsState(val agentId: String, val loading: Boolean = true, val quota: JSONObject? = null, val error: String? = null)
private data class AccountCacheWrite(
    val session: Session,
    val write: AccountStorage.() -> Unit,
    val completion: CompletableDeferred<Unit>? = null,
)

data class WorkspaceState(
    val session: Session? = null,
    val workspace: Workspace = Workspace(),
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val preview: Boolean = false,
    val employeeId: String = "",
    val draft: String = "",
    val attachments: List<Attachment> = emptyList(),
    val page: String = "home",
    val messages: List<ChatMessage> = emptyList(),
    val loadingMessages: Boolean = false,
    val hasMore: Boolean = false,
    val beforeId: Long = 0,
    val sending: Boolean = false,
    val uploading: Boolean = false,
    val connection: String = "未连接",
    val resource: ResourceState = ResourceState(),
    val appearance: String = "system",
    val openUrl: String? = null,
    val taskCreationUncertain: Boolean = false,
    val localFile: OpenLocalFile? = null,
    val exportFile: OpenLocalFile? = null,
    val agentSettings: AgentSettingsState? = null,
    val quote: MessageQuote? = null,
    val stoppingTopic: String = "",
    val lastSyncedAt: Long = 0,
    val messageTarget: Long = 0,
    val localDataEpoch: Int = 0,
    val skillTargetId: String = "",
)

class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    private val vault = KeystoreSessionStore(application)
    private val storage = AccountStorage(application)
    private val preferences = application.getSharedPreferences("appearance", 0)
    private val live = HttpWorkspaceRepository()
    val accountImages = coil.ImageLoader.Builder(application)
        .okHttpClient(live.client)
        .components { add(coil.decode.SvgDecoder.Factory()) }
        .build()
    private var repository: WorkspaceRepository = live
    private val mutable = MutableStateFlow(WorkspaceState(appearance = preferences.getString("mode", "system") ?: "system"))
    val state = mutable.asStateFlow()
    private var generation = 0
    private var employeeSelectionExplicit = false
    private var authJob: Job? = null
    private var screenJob: Job? = null
    private var historyJob: Job? = null
    private var refreshJob: Job? = null
    private var sendJob: Job? = null
    private var uploadJob: Job? = null
    private var metadataJob: Job? = null
    private var agentSettingsJob: Job? = null
    private var draftSaveJob: Job? = null
    private var pollingJob: Job? = null
    private var realtimeFallback = false
    private val actions = mutableSetOf<Job>()
    private val composerDrafts = mutableMapOf<String, ComposerDraft>()
    private val moduleDrafts = mutableMapOf<String, Map<String, String>>()
    private val moduleQueries = mutableMapOf<String, String>()
    private val accountCacheWrites = Channel<AccountCacheWrite>(Channel.UNLIMITED)
    private var active = true
    private val pages = mutableListOf<String>()
    val backDestination: String
        get() = pages.lastOrNull()?.takeUnless { it.startsWith("agent-settings/") } ?: "home"
    private var timeline = MessageTimeline()
    private var timelineTopic = ""
    private val channel = RealtimeChannel(live.client, viewModelScope,
        onState = { status -> viewModelScope.launch { mutable.update { it.copy(connection = status) } } },
        onEvent = { event -> viewModelScope.launch { receive(event) } },
        onUnauthorized = { viewModelScope.launch { verifyRealtimeSession() } },
    )

    init {
        viewModelScope.launch {
            for ((session, write, completion) in accountCacheWrites) {
                try {
                    withContext(Dispatchers.IO) {
                        storage.write()
                    }
                    completion?.complete(Unit)
                } catch (canceled: CancellationException) {
                    throw canceled
                } catch (failure: Exception) {
                    completion?.completeExceptionally(failure)
                    if (completion == null && mutable.value.session?.userId == session.userId && mutable.value.session?.server == session.server) {
                        notice("本机缓存暂时写入失败，当前页面内容已保留。")
                    }
                }
            }
        }
        vault.read()?.let { saved ->
            val cached = storage.workspace(saved)
            mutable.update { it.copy(session = saved, workspace = cached, employeeId = cached.employees.firstOrNull()?.id.orEmpty()) }
            restoreComposer("new-task")
            authenticate {
                establish(saved)
            }
        }
    }

    private fun authenticate(block: suspend () -> Unit) {
        if (authJob?.isActive == true) return
        val epoch = generation
        mutable.update { it.copy(busy = true, error = null, notice = null) }
        authJob = viewModelScope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (epoch == generation) report(error) }
            finally { if (epoch == generation) mutable.update { it.copy(busy = false) } }
        }
    }

    private suspend fun establish(candidate: Session) {
        val session = live.restore(candidate)
        currentCoroutineContext().ensureActive()
        repository = live
        employeeSelectionExplicit = false
        vault.write(session)
        val appearance = mutable.value.appearance
        val cached = storage.workspace(session)
        mutable.value = WorkspaceState(session = session, workspace = cached, busy = true, appearance = appearance, employeeId = cached.employees.firstOrNull()?.id.orEmpty())
        restoreComposer("new-task")
        refresh()
        if (active) channel.connect(session)
    }

    fun login(server: String, account: String, password: String) = authenticate {
        require(account.isNotBlank() && password.isNotBlank()) { "请填写账号和密码。" }
        establish(live.login(ServerAddress.parse(server), account, password))
    }

    fun sendCode(server: String, email: String, reset: Boolean, onSent: () -> Unit = {}) = authenticate {
        require(email.contains('@')) { "请填写有效的邮箱。" }
        live.api(ServerAddress.parse(server), if (reset) "/api/auth/reset-password/send-code" else "/api/auth/send-code", method = "POST", body = JSONObject().put("email", email.trim()))
        mutable.update { it.copy(notice = "验证码已发送，请检查邮箱。") }
        onSent()
    }

    fun register(server: String, email: String, password: String, code: String, invite: String, onCreated: () -> Unit = {}) = authenticate {
        require(email.contains('@') && password.length >= 6 && code.isNotBlank()) { "请填写邮箱、验证码和至少 6 位密码。" }
        val address = ServerAddress.parse(server)
        val result = live.api(address, "/api/auth/register", method = "POST", body = JSONObject()
            .put("email", email.trim()).put("password", password).put("code", code.trim()).put("bot_invite_code", invite.trim()))
        onCreated()
        val session = if (result.optString("token").isNotBlank()) live.session(address, result, email)
            else live.login(address, email, password)
        establish(session)
    }

    fun resetPassword(server: String, email: String, password: String, code: String, onSuccess: () -> Unit = {}) = authenticate {
        require(password.length >= 6 && code.isNotBlank()) { "请填写验证码和至少 6 位新密码。" }
        live.api(ServerAddress.parse(server), "/api/auth/reset-password", method = "POST", body = JSONObject().put("email", email.trim()).put("code", code.trim()).put("password", password))
        mutable.update { it.copy(notice = "密码已重置，可以使用新密码登录。") }
        onSuccess()
    }

    fun enterPreview() {
        val preview = DebugEntry.repository() ?: return
        logout()
        repository = preview
        authenticate {
            val session = preview.login(ServerAddress.parse("https://app.catsco.cc"), "", "")
            val workspace = preview.workspace(session)
            mutable.update { it.copy(session = session, workspace = workspace, preview = true, employeeId = workspace.employees.firstOrNull()?.id.orEmpty()) }
        }
    }

    fun logout(message: String? = null) {
        persistComposer()
        generation++
        employeeSelectionExplicit = false
        listOf(authJob, screenJob, historyJob, refreshJob, sendJob, uploadJob, metadataJob, agentSettingsJob, draftSaveJob, pollingJob).forEach { it?.cancel() }
        realtimeFallback = false
        actions.toList().forEach { it.cancel() }
        authJob = null
        channel.disconnect()
        mutable.value.session?.let { session ->
            accountCacheWrites.trySend(AccountCacheWrite(session, { clear(session) }))
        }
        composerDrafts.clear()
        vault.clear()
        TaskWidgetProvider.clear(getApplication())
        pages.clear(); timeline = MessageTimeline(); timelineTopic = ""; repository = live
        mutable.value = WorkspaceState(error = message, appearance = mutable.value.appearance)
    }

    fun foreground(visible: Boolean) {
        if (active == visible) return
        active = visible
        if (visible) {
            if (realtimeFallback) startPolling() else mutable.value.session?.takeUnless { mutable.value.preview }?.let(channel::connect)
            if (mutable.value.page.startsWith("chat/")) loadMessages()
            else if (mutable.value.page !in setOf("home", "settings")) loadResource()
        } else {
            pollingJob?.cancel()
            persistComposer(); channel.disconnect()
        }
    }

    private fun composerDraft(session: Session, topic: String): ComposerDraft =
        composerDrafts.getOrPut(moduleDraftKey(session, "composer:$topic")) { storage.draft(session, topic) }

    private fun saveComposerDraft(session: Session, topic: String, draft: ComposerDraft, completion: CompletableDeferred<Unit>? = null) {
        composerDrafts[moduleDraftKey(session, "composer:$topic")] = draft
        accountCacheWrites.trySend(AccountCacheWrite(session, { saveDraft(session, topic, draft) }, completion)).getOrThrow()
    }

    private suspend fun persistDeliveryDraft(session: Session, topic: String, draft: ComposerDraft) {
        val saved = CompletableDeferred<Unit>()
        saveComposerDraft(session, topic, draft, saved)
        saved.await()
    }

    private fun currentDraftTopic() = mutable.value.page.takeIf { it.startsWith("chat/") }?.removePrefix("chat/") ?: "new-task"
    private fun persistComposer() {
        if (mutable.value.page != "home" && !mutable.value.page.startsWith("chat/")) return
        val session = mutable.value.session ?: return
        val topic = currentDraftTopic()
        val prior = composerDraft(session, topic)
        saveComposerDraft(session, topic, prior.copy(text = mutable.value.draft, attachments = mutable.value.attachments, quote = mutable.value.quote))
    }
    private fun restoreComposer(topic: String) {
        val session = mutable.value.session ?: return
        val saved = composerDraft(session, topic)
        mutable.update { it.copy(draft = saved.text, attachments = saved.attachments, quote = saved.quote, taskCreationUncertain = topic == "new-task" && saved.pendingTaskAgent.isNotEmpty()) }
    }

    fun navigate(page: String) {
        dismissAgentSettings()
        val current = mutable.value
        val conversationPage = current.page.takeIf { it.startsWith("chat/") }
            ?: pages.lastOrNull { it.startsWith("chat/") }
        val fileAgent = conversationPage?.let { agentFor(it.removePrefix("chat/")) }
            ?.takeIf { id -> current.workspace.employees.any { it.id == id } }
            ?: current.employeeId.takeIf { id -> current.workspace.employees.any { it.id == id } }
            ?: current.workspace.employees.firstOrNull()?.id
        val destination = if (page == "files" && fileAgent != null) "files/$fileAgent" else page
        if (destination == current.page) {
            if (current.messageTarget > 0 && destination.startsWith("chat/")) showLatestMessages() else retryCurrent()
            return
        }
        persistComposer()
        if (destination == "home") pages.clear() else pages.add(current.page)
        openPage(destination)
    }

    fun selectLibraryTab(skills: Boolean) {
        val destination = if (skills) "skills" else "apps"
        if (destination != mutable.value.page) openPage(destination)
    }

    fun selectFileAgent(id: String) {
        if (!mutable.value.page.startsWith("files/") || mutable.value.workspace.employees.none { it.id == id }) return
        openPage("files/$id")
    }

    fun selectSkillTarget(id: String) {
        if (mutable.value.workspace.employees.any { it.id == id && it.owner }) mutable.update { it.copy(skillTargetId = id) }
    }

    private fun moduleDraftKey(session: Session, page: String) = "${session.server.value}\n${session.userId}\n$page"

    fun moduleDraft(page: String): Map<String, String> {
        val session = mutable.value.session ?: return emptyMap()
        return moduleDrafts.getOrPut(moduleDraftKey(session, page)) {
            val stored = storage.read(session, "module-draft:$page")
                ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
            stored.keys().asSequence().associateWith { stored.optString(it) }
        }
    }

    fun saveModuleDraft(page: String, values: Map<String, String>) {
        val session = mutable.value.session ?: return
        moduleDrafts[moduleDraftKey(session, page)] = values.toMap()
        val snapshot = values.toMap()
        accountCacheWrites.trySend(AccountCacheWrite(session, { write(session, "module-draft:$page", JSONObject(snapshot).toString()) }))
    }

    fun moduleQuery(page: String, initial: String = ""): String {
        val session = mutable.value.session ?: return initial
        return moduleQueries[moduleDraftKey(session, page)] ?: initial
    }

    fun saveModuleQuery(page: String, query: String) {
        val session = mutable.value.session ?: return
        moduleQueries[moduleDraftKey(session, page)] = query
    }
    fun back() {
        persistComposer()
        val destination = if (pages.isEmpty()) "home" else pages.removeAt(pages.lastIndex)
        if (destination.startsWith("agent-settings/")) {
            openPage("home")
            showAgentSettings(destination.removePrefix("agent-settings/"))
        } else openPage(destination)
    }

    fun showAgentSettings(id: String) {
        val session = mutable.value.session ?: return
        if (mutable.value.workspace.employees.none { it.id == id }) return
        agentSettingsJob?.cancel()
        val epoch = generation
        mutable.update { it.copy(agentSettings = AgentSettingsState(id)) }
        agentSettingsJob = viewModelScope.launch {
            try {
                val quota = request(session, "/api/agents/quota", mapOf("uid" to id))
                ensureActive()
                if (generation == epoch) mutable.update {
                    if (it.agentSettings?.agentId == id) it.copy(agentSettings = AgentSettingsState(id, false, quota)) else it
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == epoch) {
                    if (error is ApiFailure && error.status == 401) report(error)
                    else mutable.update {
                        if (it.agentSettings?.agentId == id) it.copy(agentSettings = AgentSettingsState(id, false, error = error.message ?: "读取失败，请重试。")) else it
                    }
                }
            }
        }
    }

    fun dismissAgentSettings() {
        agentSettingsJob?.cancel()
        mutable.update { if (it.agentSettings == null) it else it.copy(agentSettings = null) }
    }

    fun openAgentSettingsPage(page: String) {
        val id = mutable.value.agentSettings?.agentId ?: return
        persistComposer()
        dismissAgentSettings()
        pages.add("agent-settings/$id")
        openPage(page)
    }
    private fun openPage(page: String) {
        screenJob?.cancel(); historyJob?.cancel()
        mutable.update { it.copy(page = page, resource = ResourceState(), error = null, notice = null, openUrl = null, messageTarget = 0) }
        if (page == "home" || page.startsWith("chat/")) restoreComposer(currentDraftTopic())
        if (page.startsWith("chat/")) {
            val topic = page.removePrefix("chat/")
            if (timelineTopic != topic) {
                timeline = MessageTimeline(); timelineTopic = topic
                mutable.update { it.copy(messages = emptyList(), beforeId = 0, hasMore = false) }
                mutable.value.session?.let { session ->
                    storage.read(session, "messages:$topic")?.let { raw ->
                        runCatching { timeline.merge(JSONObject(raw).objects("messages").map(::chatMessage)) }
                        mutable.update { it.copy(messages = timeline.messages) }
                    }
                }
            }
            channel.attention(topic)
            loadMessages()
        } else {
            channel.attention("")
            if (page !in setOf("home", "settings", "files", "appearance", "offline", "notifications", "surfaces", "feedback", "server")) loadResource()
        }
    }

    fun refresh() {
        val session = mutable.value.session ?: return
        if (refreshJob?.isActive == true) return
        val epoch = generation
        refreshJob = viewModelScope.launch {
            try {
                val received = repository.workspace(session)
                ensureActive()
                if (epoch != generation) return@launch
                val previous = withContext(Dispatchers.IO) { storage.cachedWorkspace(session)?.employees }
                ensureActive()
                if (epoch != generation) return@launch
                val workspace = received.copy(employees = received.employees.withObservedAdditions(previous, System.currentTimeMillis()))
                mutable.update {
                    val keepSelection = (employeeSelectionExplicit || it.sending || it.taskCreationUncertain || it.draft.isNotBlank() || it.attachments.isNotEmpty()) &&
                        workspace.employees.any { agent -> agent.id == it.employeeId }
                    val stoppingTopic = it.stoppingTopic.takeIf { topic ->
                        it.busy || workspace.conversations.any { conversation -> conversation.id == topic && conversation.active }
                    }.orEmpty()
                    it.copy(workspace = workspace, employeeId = if (keepSelection) it.employeeId else workspace.employees.firstOrNull()?.id.orEmpty(),
                        stoppingTopic = stoppingTopic, lastSyncedAt = System.currentTimeMillis())
                }
                TaskWidgetProvider.update(getApplication(), workspace.conversations, syncedAt = mutable.value.lastSyncedAt)
                val saved = CompletableDeferred<Unit>()
                accountCacheWrites.send(AccountCacheWrite(session, { saveWorkspace(session, workspace) }, saved))
                saved.await()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (epoch == generation) report(error) }
        }
    }

    fun selectEmployee(id: String) {
        if (mutable.value.workspace.employees.none { it.id == id }) return
        employeeSelectionExplicit = true
        mutable.update { it.copy(employeeId = id) }
        if (mutable.value.page != "home") navigate("home")
    }
    fun openConversation(topic: String, title: String) {
        if (!topic.matches(Regex("(?:grp_[0-9]+|p2p_[0-9]+_[0-9]+)"))) { notice("无法识别这个会话。"); return }
        if (mutable.value.workspace.conversations.none { it.id == topic }) {
            mutable.update { it.copy(workspace = it.workspace.copy(conversations = it.workspace.conversations + Conversation(topic, title))) }
        }
        navigate("chat/$topic")
    }

    fun openSearchResult(result: JSONObject) {
        val topic = result.optString("topic_id")
        val target = result.optLong("message_id")
        openConversation(topic, result.first("topic_name").ifBlank { "对话" })
        if (target <= 0 || mutable.value.page != "chat/$topic") return
        historyJob?.cancel()
        timeline = MessageTimeline()
        mutable.update { it.copy(messageTarget = target, messages = emptyList(), hasMore = false, beforeId = 0) }
        loadSearchContext()
    }

    private fun loadSearchContext() {
        val state = mutable.value
        val session = state.session ?: return
        val target = state.messageTarget
        val topic = state.page.removePrefix("chat/")
        val epoch = generation
        if (target <= 0 || state.preview) return
        historyJob?.cancel()
        mutable.update { it.copy(loadingMessages = true, error = null) }
        historyJob = viewModelScope.launch {
            try {
                val result = request(session, "/api/messages", mapOf("topic_id" to topic, "around_id" to target.toString(), "limit" to "50"))
                ensureActive()
                if (epoch != generation || mutable.value.page != "chat/$topic" || mutable.value.messageTarget != target) return@launch
                timeline = MessageTimeline().apply { merge(result.objects("messages").map(::chatMessage)) }
                mutable.update { it.copy(messages = timeline.messages) }
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (failure: Exception) {
                if (epoch == generation && mutable.value.page == "chat/$topic" && mutable.value.messageTarget == target) report(failure, "无法定位这条消息。")
            } finally {
                if (epoch == generation && mutable.value.page == "chat/$topic" && mutable.value.messageTarget == target) {
                    mutable.update { it.copy(loadingMessages = false) }
                }
            }
        }
    }

    fun showLatestMessages() {
        historyJob?.cancel()
        timeline = MessageTimeline()
        mutable.update { it.copy(messageTarget = 0, messages = emptyList(), beforeId = 0, hasMore = false, error = null) }
        loadMessages()
    }
    fun draft(text: String) {
        mutable.update { it.copy(draft = text) }
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch { delay(350); persistComposer() }
    }
    fun removeAttachment(index: Int) {
        mutable.update { it.copy(attachments = it.attachments.filterIndexed { i, _ -> i != index }) }; persistComposer()
    }
    fun dismissError() { mutable.update { it.copy(error = null, notice = null) } }
    fun consumeError(message: String) { mutable.update { if (it.error == message) it.copy(error = null) else it } }
    fun retryCurrent() {
        dismissError()
        when {
            mutable.value.messageTarget > 0 -> loadSearchContext()
            mutable.value.page.startsWith("chat/") -> loadMessages()
            mutable.value.page in setOf("home", "settings") -> refresh()
            else -> loadResource()
        }
    }
    fun renameConversation(id: String, name: String) = action {
        repository.renameConversation(it, id, name)
        mutable.update { state -> state.copy(workspace = state.workspace.copy(conversations = state.workspace.conversations.map { chat -> if (chat.id == id) chat.copy(name = name) else chat })) }
    }

    fun loadMessages(older: Boolean = false, quiet: Boolean = false) {
        val state = mutable.value
        val session = state.session ?: return
        if (!state.page.startsWith("chat/") || state.preview || state.messageTarget > 0 || (older && !state.hasMore)) return
        if (historyJob?.isActive == true) return
        val topic = state.page.removePrefix("chat/")
        val epoch = generation
        if (!quiet) mutable.update { it.copy(loadingMessages = true) }
        historyJob = viewModelScope.launch {
            try {
                val result = request(session, "/api/messages", mapOf("topic_id" to topic, "limit" to "50", "latest" to "1", "before_id" to if (older) state.beforeId.toString() else "0"))
                ensureActive()
                if (generation != epoch || timelineTopic != topic) return@launch
                val messages = withContext(Dispatchers.Default) { result.objects("messages").map(::chatMessage) }
                ensureActive()
                if (generation != epoch || timelineTopic != topic) return@launch
                timeline.merge(messages)
                mutable.update { current -> current.copy(messages = timeline.messages,
                    beforeId = if (older || current.beforeId == 0L) result.optLong("next_before_id", messages.minOfOrNull { it.sequence } ?: 0) else current.beforeId,
                    hasMore = if (older || current.beforeId == 0L) result.optBoolean("has_more", messages.size == 50) else current.hasMore) }
                val snapshot = timeline.messages.takeLast(100).filterNot { it.streaming }
                accountCacheWrites.send(AccountCacheWrite(session, {
                    write(session, "messages:$topic", JSONObject().put("messages", JSONArray(snapshot.map { message ->
                        JSONObject(message.raw).apply { if (message.kind == "runtime_plan") put("_after_sequence", message.afterSequence) }
                    })).toString())
                }))
                if (active && mutable.value.page == "chat/$topic") channel.read(topic, timeline.messages.filterNot { it.streaming }.maxOfOrNull { it.sequence } ?: 0)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (epoch == generation && timelineTopic == topic) {
                    if (error is ApiFailure && error.status == 403) {
                        timeline = MessageTimeline()
                        accountCacheWrites.trySend(AccountCacheWrite(session, { write(session, "messages:$topic", "{}") }))
                        mutable.update { it.copy(messages = emptyList()) }
                    }
                    if (!quiet || error is ApiFailure) report(error)
                }
            }
            finally { if (epoch == generation && timelineTopic == topic) mutable.update { it.copy(loadingMessages = false) } }
        }
    }

    private fun receive(event: JSONObject) {
        if (mutable.value.session == null || mutable.value.preview) return
        if (event.optString("_type") == "ws_open") { loadMessages(); refresh(); return }
        val data = event.optJSONObject("data")
        if (data != null && data.first("topic", "topic_id") == timelineTopic && mutable.value.page == "chat/$timelineTopic" && mutable.value.messageTarget == 0L) {
            timeline.receive(chatMessage(data))
            mutable.update { it.copy(messages = timeline.messages) }
        }
        if (metadataJob?.isActive != true) metadataJob = viewModelScope.launch { delay(1000); refresh() }
    }

    private suspend fun verifyRealtimeSession() {
        val session = mutable.value.session ?: return
        val epoch = generation
        try {
            live.restore(session)
            currentCoroutineContext().ensureActive()
            if (epoch != generation) return
            // Older servers authenticate sockets only through URL parameters.
            // HTTP success proves this is a transport mismatch, not an expired account.
            realtimeFallback = true
            mutable.update { it.copy(connection = "自动同步") }
            startPolling()
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (epoch == generation) {
                if (error is ApiFailure && error.status == 401) logout(error.message)
                else {
                    realtimeFallback = true
                    mutable.update { it.copy(connection = "自动同步") }
                    startPolling()
                }
            }
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            var tick = 0
            while (active && mutable.value.session != null && !mutable.value.preview) {
                mutable.update { it.copy(connection = "自动同步") }
                loadMessages(quiet = true)
                if (tick++ % 3 == 0) refresh()
                delay(5000)
            }
        }
    }

    fun quoteMessage(turn: MessageTurn) {
        val state = mutable.value
        if (!state.page.startsWith("chat/") || turn.messageId <= 0 || turn.streaming) return
        if (state.messages.none { it.sequence == turn.messageId && it.senderId == turn.senderId }) return
        val name = turn.senderName.ifBlank { state.workspace.employees.find { it.id == turn.senderId }?.name.orEmpty() }
        mutable.update { it.copy(quote = MessageQuote(turn.messageId, name, turn.text.joinToString("\n\n"))) }
        persistComposer()
    }

    fun clearQuote() {
        mutable.update { it.copy(quote = null) }
        persistComposer()
    }

    fun regenerate(turn: MessageTurn) {
        val state = mutable.value
        if (!state.page.startsWith("chat/")) return
        val draft = regenerationDraft(state.messages, turn, state.session?.userId.orEmpty())
        if (draft == null) { notice("未找到原任务，请加载更早的消息后重试。"); return }
        sendDraft(regeneration = draft)
    }

    fun send(taskTitle: String = "") = sendDraft(taskTitle)

    private fun sendDraft(taskTitle: String = "", regeneration: ComposerDraft? = null) {
        val state = mutable.value
        val session = state.session ?: return
        if (sendJob?.isActive == true || state.uploading) return
        if (state.preview) { report(IllegalStateException("调试预览不发送任务，请使用真实账号登录。")); return }
        if (regeneration == null && state.draft.isBlank() && state.attachments.isEmpty()) return
        if (state.taskCreationUncertain && state.page == "home") { report(IllegalStateException("上次创建结果未确认，请先刷新并检查历史任务。")); return }
        if (state.messageTarget > 0) showLatestMessages()
        draftSaveJob?.cancel()
        persistComposer()
        val epoch = generation
        val originalTopic = currentDraftTopic()
        val snapshot = regeneration ?: composerDraft(session, originalTopic)
        mutable.update { it.copy(sending = true, error = null) }
        sendJob = viewModelScope.launch {
            var topic = originalTopic
            try {
                if (topic == "new-task") {
                    require(state.employeeId.toLongOrNull()?.let { it > 0 } == true) { "请先选择 Agent。" }
                    val name = taskTitle.takeUnless { it.isBlank() || it == "新任务" } ?: snapshot.text.trim().replace(Regex("\\s+"), " ").take(36).ifBlank { "附件任务" }
                    persistDeliveryDraft(session, topic, snapshot.copy(pendingTaskAgent = state.employeeId))
                    val created = request(session, "/api/groups/create", method = "POST", body = JSONObject().put("name", name).put("kind", "agent_task").put("member_ids", JSONArray().put(state.employeeId.toLong())))
                    ensureActive()
                    if (epoch != generation) return@launch
                    val groupId = maxOf(created.optJSONObject("group")?.optLong("id") ?: 0, created.optLong("group_id"))
                    check(groupId > 0) { "创建结果未确认，请检查历史任务。" }
                    topic = "grp_$groupId"
                    persistDeliveryDraft(session, topic, snapshot.copy(pendingTaskAgent = ""))
                    persistComposer()
                    mutable.update { it.copy(workspace = it.workspace.copy(conversations = listOf(Conversation(topic, name, agentId = state.employeeId, task = true, date = Instant.now().toString())) + it.workspace.conversations), taskCreationUncertain = false) }
                }
                val deliverySlot = if (regeneration != null) "regenerate:$topic" else topic
                val fingerprint = snapshot.text + "\n" + snapshot.attachments.joinToString("\n") { it.raw } +
                    snapshot.quote?.let { "\nreply:${it.messageId}" }.orEmpty()
                val saved = if (regeneration != null) withContext(Dispatchers.IO) { storage.draft(session, deliverySlot) }
                    else composerDraft(session, deliverySlot)
                val clientId = if (saved.fingerprint == fingerprint && saved.clientId.isNotBlank()) saved.clientId else UUID.randomUUID().toString()
                persistDeliveryDraft(session, deliverySlot, snapshot.copy(clientId = clientId, fingerprint = fingerprint))
                val body = messageSendBody(topic, snapshot, clientId)
                val result = request(session, "/api/messages/send", method = "POST", body = body)
                ensureActive()
                if (epoch != generation) return@launch
                if (originalTopic == "new-task") finishCreatedTask(session, topic, snapshot)
                if (currentDraftTopic() == topic) persistComposer()
                val latest = composerDraft(session, topic)
                if (regeneration != null) saveComposerDraft(session, deliverySlot, ComposerDraft())
                else if (latest.text == snapshot.text && latest.attachments == snapshot.attachments && latest.quote == snapshot.quote) {
                    saveComposerDraft(session, topic, ComposerDraft())
                    if (mutable.value.page == "chat/$topic") mutable.update { it.copy(draft = "", attachments = emptyList(), quote = null) }
                } else {
                    saveComposerDraft(session, topic, latest.copy(clientId = "", fingerprint = ""))
                }
                if (timelineTopic == topic) {
                    val receipt = result.optJSONObject("message") ?: result
                    val delivered = JSONObject(body.toString()).put("from_uid", session.userId).put("from_name", session.name)
                    receipt.keys().forEach { key -> delivered.put(key, receipt.get(key)) }
                    timeline.receive(chatMessage(delivered))
                    mutable.update { it.copy(messages = timeline.messages) }
                    historyJob?.cancel(); historyJob = null; loadMessages(quiet = true)
                }
                refresh()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (epoch == generation) {
                    if (originalTopic == "new-task" && topic != "new-task") finishCreatedTask(session, topic, snapshot)
                    if (topic == "new-task") {
                        val uncertain = error !is ApiFailure || error.status >= 500
                        saveComposerDraft(session, topic, snapshot.copy(pendingTaskAgent = if (uncertain) state.employeeId else ""))
                        mutable.update { it.copy(taskCreationUncertain = uncertain) }
                    }
                    report(error, when {
                        regeneration != null -> "重新生成结果未确认，请刷新对话后重试此操作。"
                        topic == "new-task" -> "创建结果未确认，草稿已保留。请先检查历史任务。"
                        else -> "发送结果未确认，草稿已保留。刷新对话后可重试，相同内容会沿用发送编号。"
                    })
                }
            } finally { if (epoch == generation) mutable.update { it.copy(sending = false) } }
        }
    }

    private fun finishCreatedTask(session: Session, topic: String, sent: ComposerDraft) {
        persistComposer()
        val latest = composerDraft(session, "new-task")
        if (latest.text == sent.text && latest.attachments == sent.attachments && latest.quote == sent.quote) {
            saveComposerDraft(session, "new-task", ComposerDraft())
        } else {
            saveComposerDraft(session, "new-task", latest.copy(pendingTaskAgent = ""))
        }
        if (mutable.value.page == "home") openPage("chat/$topic")
    }

    fun acknowledgeTaskCreation() {
        val session = mutable.value.session ?: return
        val saved = composerDraft(session, "new-task")
        saveComposerDraft(session, "new-task", saved.copy(pendingTaskAgent = ""))
        mutable.update { it.copy(taskCreationUncertain = false, error = null) }
    }

    fun attach(uri: Uri) {
        val session = mutable.value.session ?: return
        if (uploadJob?.isActive == true || mutable.value.sending) return
        persistComposer()
        val topic = currentDraftTopic()
        val epoch = generation
        mutable.update { it.copy(uploading = true, error = null) }
        uploadJob = viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    var name = "attachment"
                    var size = -1L
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) { name = cursor.getString(0) ?: name; if (!cursor.isNull(1)) size = cursor.getLong(1) }
                    }
                    require(size <= 100 * 1024 * 1024) { "请选择 100 MB 以内的文件。" }
                    val bytes = resolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 100 * 1024 * 1024) { "文件超过 100 MB。" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: error("无法读取所选文件。")
                    Triple(name, resolver.getType(uri) ?: "application/octet-stream", bytes)
                }
                val uploaded = attachment(JSONObject(repository.upload(session, file.third, file.first, file.second)))
                ensureActive()
                if (epoch != generation) return@launch
                val saved = composerDraft(session, topic)
                saveComposerDraft(session, topic, saved.copy(attachments = saved.attachments + uploaded))
                if (currentDraftTopic() == topic) mutable.update { it.copy(attachments = it.attachments + uploaded) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (epoch == generation) report(error) }
            finally { if (epoch == generation) mutable.update { it.copy(uploading = false) } }
        }
    }

    fun stop() {
        val state = mutable.value
        if (state.session == null || state.busy || state.preview || !state.page.startsWith("chat/") || state.stoppingTopic.isNotBlank()) return
        val topic = state.page.removePrefix("chat/")
        val agent = state.workspace.conversations.find { it.id == topic }?.agentId.orEmpty()
        val epoch = generation
        mutable.update { it.copy(stoppingTopic = topic) }
        action { session ->
            try {
                if (!channel.stop(topic, agent)) {
                    // Match the desktop's visible stop message when realtime is unavailable.
                    request(session, "/api/messages/send", method = "POST", body = JSONObject()
                        .put("topic_id", topic).put("content", "停止")
                        .put("client_msg_id", java.util.UUID.randomUUID().toString()))
                }
                if (epoch == generation) {
                    refresh()
                    if (mutable.value.page == "chat/$topic") loadMessages()
                }
            } catch (error: Exception) {
                if (epoch == generation) mutable.update { it.copy(stoppingTopic = "") }
                throw error
            }
        }
    }

    suspend fun request(session: Session, path: String, query: Map<String, String> = emptyMap(), method: String = "GET", body: JSONObject? = null): JSONObject {
        val epoch = generation
        val result = JSONObject(repository.resource(session, path, query, method, body?.toString()))
        currentCoroutineContext().ensureActive()
        if (epoch != generation) throw CancellationException()
        return result
    }

    fun action(onFailure: ((String) -> Unit)? = null, block: suspend (Session) -> Unit) {
        val session = mutable.value.session ?: return
        if (mutable.value.busy) return
        val epoch = generation
        mutable.update { it.copy(busy = true, error = null, notice = null) }
        val job = viewModelScope.launch {
            try { block(session); ensureActive() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (epoch == generation) {
                    if (onFailure == null || (error is ApiFailure && error.status == 401)) report(error)
                    else onFailure(operationErrorMessage(error))
                }
            }
            finally { if (epoch == generation) mutable.update { it.copy(busy = false) } }
        }
        actions.add(job)
        job.invokeOnCompletion { actions.remove(job) }
    }

    fun notice(text: String) { mutable.update { it.copy(notice = text) } }
    fun report(error: Exception, prefix: String = "") {
        if (error is ApiFailure && error.status == 401) { logout(error.message); return }
        mutable.update { it.copy(error = listOf(prefix, operationErrorMessage(error)).filter(String::isNotBlank).joinToString("\n")) }
    }

    private fun operationErrorMessage(error: Exception): String = when (error) {
        is java.io.IOException -> "连接失败，请检查网络后重试。"
        else -> error.message ?: "无法完成操作，请重试。"
    }

    fun appearance(mode: String) {
        if (mode !in setOf("system", "light", "dark")) return
        preferences.edit().putString("mode", mode).apply()
        mutable.update { it.copy(appearance = mode) }
    }

    fun saveProfile(name: String, avatar: String, onSuccess: () -> Unit = {}) = action { session ->
        require(name.isNotBlank()) { "请填写显示名称。" }
        val result = request(session, "/api/me/update", method = "POST", body = JSONObject().put("display_name", name.trim()).put("avatar_url", avatar))
        val updated = session.copy(name = name.trim(), avatarUrl = avatar).withProfile(result)
        vault.write(updated)
        mutable.update { it.copy(session = updated, notice = "资料已保存。") }
        onSuccess()
        if (mutable.value.page == "profile") loadResource()
    }

    fun configureServer(address: String) {
        try {
            val server = ServerAddress.parse(address)
            if (server.value == mutable.value.session?.server?.value) return
            preferences.edit().putString("server", server.value).apply()
            logout()
        } catch (error: Exception) { report(error) }
    }
    val serverAddress: String get() = preferences.getString("server", "https://app.catsco.cc") ?: "https://app.catsco.cc"

    fun clearOfflineData() = action { session ->
        historyJob?.cancel()
        refreshJob?.cancel()
        draftSaveJob?.cancel()
        val cleared = CompletableDeferred<Unit>()
        accountCacheWrites.send(AccountCacheWrite(session, { clear(session) }, cleared))
        cleared.await()
        val prefix = moduleDraftKey(session, "")
        composerDrafts.keys.removeAll { it.startsWith(prefix) }
        moduleDrafts.keys.removeAll { it.startsWith(prefix) }
        moduleQueries.keys.removeAll { it.startsWith(prefix) }
        mutable.update { it.copy(draft = "", attachments = emptyList(), taskCreationUncertain = false,
            localDataEpoch = it.localDataEpoch + 1, notice = "离线缓存和本机草稿已清除。") }
    }

    fun openUrl(url: String) {
        val session = mutable.value.session ?: return
        val resolved = runCatching { java.net.URI(session.server.value + "/").resolve(url) }.getOrNull()
        if (resolved?.scheme != "https" || resolved.rawUserInfo != null) { notice("无法打开这个链接。"); return }
        mutable.update { it.copy(openUrl = resolved.toASCIIString()) }
    }
    fun urlOpened() { mutable.update { it.copy(openUrl = null) } }
    fun fileOpened() { mutable.update { it.copy(localFile = null) } }

    fun openAttachment(file: Attachment) = action { session ->
        val opened = cachedAttachment(session, file)
        mutable.update { it.copy(localFile = opened) }
    }

    fun downloadAttachment(file: Attachment) = action { session ->
        if (mutable.value.exportFile != null) return@action
        val opened = cachedAttachment(session, file)
        mutable.update { it.copy(exportFile = opened) }
    }

    fun saveAttachment(destination: Uri?) {
        val file = mutable.value.exportFile ?: return
        if (destination == null) {
            mutable.update { it.copy(exportFile = null) }
            return
        }
        action {
            try {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    try {
                        resolver.openInputStream(file.uri)?.use { input ->
                            resolver.openOutputStream(destination, "w")?.use { output -> input.copyTo(output) }
                                ?: error("无法写入所选位置。")
                        } ?: error("无法读取已下载的文件。")
                    } catch (error: Exception) {
                        runCatching { android.provider.DocumentsContract.deleteDocument(resolver, destination) }
                        throw error
                    }
                }
                notice("文件已保存。")
            } finally {
                mutable.update { it.copy(exportFile = null) }
            }
        }
    }

    private suspend fun cachedAttachment(session: Session, file: Attachment): OpenLocalFile = withContext(Dispatchers.IO) {
        val url = mediaUrl(session.server, file.url) ?: error("无效的文件链接。")
        val request = okhttp3.Request.Builder().url(url)
        val origin = java.net.URI(session.server.value)
        if (origin.scheme == url.scheme && origin.host == url.host && (if (origin.port < 0) 443 else origin.port) == (if (url.port < 0) 443 else url.port)) {
            request.header("Authorization", "Bearer ${session.token}")
        }
        val directory = java.io.File(getApplication<Application>().cacheDir, "shared-attachments").apply { mkdirs() }
        var destination: java.io.File? = null
        try {
            live.client.newCall(request.build()).execute().use { response ->
                if (!response.isSuccessful) throw ApiFailure(response.code)
                val body = response.body ?: error("文件内容为空。")
                require(body.contentLength() <= 100L * 1024 * 1024) { "文件超过 100 MB，请在网页版打开。" }
                val mime = if (file.mime == "application/octet-stream") body.contentType()?.toString()?.substringBefore(';') ?: file.mime else file.mime
                val extension = file.name.substringAfterLast('.', "").takeIf { it.isNotBlank() && it.length <= 16 && it.all(Char::isLetterOrDigit) }
                    ?: android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
                val target = java.io.File(directory, UUID.randomUUID().toString() + "." + extension)
                destination = target
                body.byteStream().use { input -> target.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 100L * 1024 * 1024) { "文件超过 100 MB。" }
                        output.write(buffer, 0, count)
                    }
                } }
                val uri = androidx.core.content.FileProvider.getUriForFile(getApplication(), "${BuildConfig.APPLICATION_ID}.files", target)
                OpenLocalFile(uri, mime, file.name)
            }
        } catch (error: Exception) {
            destination?.delete()
            throw error
        }
    }

    fun loadResource(query: String? = null) {
        val session = mutable.value.session ?: return
        val page = mutable.value.page
        val epoch = generation
        val search = query ?: mutable.value.resource.query
        screenJob?.cancel()
        mutable.update { it.copy(resource = it.resource.copy(loading = true, error = null, errorStatus = null, query = search)) }
        screenJob = viewModelScope.launch {
            try {
                val result = loadModule(session, page, search)
                ensureActive()
                if (generation == epoch && mutable.value.page == page) mutable.update {
                    it.copy(resource = ResourceState(value = result, loaded = true, query = search))
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (generation == epoch && mutable.value.page == page) {
                    if (error is ApiFailure && error.status == 401) report(error)
                    else mutable.update { it.copy(resource = it.resource.copy(
                        loading = false,
                        error = error.message ?: "读取失败，请重试。",
                        errorStatus = (error as? ApiFailure)?.status,
                    )) }
                }
            }
        }
    }

    private suspend fun loadModule(session: Session, page: String, search: String): JSONObject = when {
        page == "apps" || page.startsWith("agent-apps/") -> {
            val selected = page.removePrefix("agent-apps/").takeIf { page.startsWith("agent-apps/") }
            val agents = if (selected == null) mutable.value.workspace.employees else listOf(
                mutable.value.workspace.employees.find { it.id == selected } ?: Employee(selected, "Agent"),
            )
            loadSharedApplications(agents) { id -> request(session, "/api/artifacts/apps", mapOf("agent" to id)) }
        }
        page.startsWith("agent-prompt/") -> request(session, "/api/agents/prompt", mapOf("uid" to page.removePrefix("agent-prompt/")))
        page.startsWith("agent-skills/") -> request(session, "/api/bots/definition/skills", mapOf("uid" to page.removePrefix("agent-skills/")))
        page == "skills" -> request(session, "/api/skillhub/skills", if (search.isBlank()) emptyMap() else mapOf("q" to search))
        page.startsWith("skill/") -> request(session, "/api/skillhub/skills/" + page.removePrefix("skill/").split('/').joinToString("/") { encodeSegment(it) })
        page == "devices" -> request(session, "/api/devices")
        page == "device-audit" -> request(session, "/api/devices/audit", mapOf("limit" to "30"))
        page == "profile" -> request(session, "/api/me").also { profile ->
            val updated = session.withProfile(profile)
            vault.write(updated)
            mutable.update { it.copy(session = updated) }
        }
        page == "usage" -> request(session, "/api/relay/usage")
        page == "commercial" -> request(session, "/api/relay/commercial").also { commercial ->
            try { commercial.put("model_usage", request(session, "/api/relay/usage")) }
            catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { commercial.put("model_usage_error", "模型用量暂时无法读取。") }
        }
        page == "orders" -> request(session, "/api/relay/commercial/orders")
        page == "cloud-workers" -> request(session, "/api/cloud-workers")
        page == "agents" -> request(session, "/api/bots")
        page.startsWith("agent-access/") -> {
            val id = page.removePrefix("agent-access/")
            request(session, "/api/bots").objects("bots", "agents")
                .firstOrNull { it.first("id", "uid", "user_id") == id } ?: throw ApiFailure(404)
        }
        page.startsWith("agent/") -> request(session, "/api/bots/definition", mapOf("uid" to page.removePrefix("agent/")))
        page.startsWith("model/") -> request(session, "/api/bots/model-config", mapOf("uid" to page.removePrefix("model/"), "include_usage" to "1"))
        page.startsWith("files/") -> request(session, "/api/agents/${encodeSegment(page.removePrefix("files/"))}/artifacts", mapOf("status" to "active"))
        page.startsWith("topic-files/") -> request(session, "/api/topics/${encodeSegment(page.removePrefix("topic-files/"))}/files", mapOf("limit" to "40"))
        page.startsWith("select-device/") -> request(session, "/api/conversations/device-selection", mapOf("topic_id" to page.removePrefix("select-device/"), "agent_id" to agentFor(page.removePrefix("select-device/"))))
        page == "contacts" -> request(session, "/api/friends").put("groups", request(session, "/api/groups").optJSONArray("groups") ?: JSONArray())
            .put("requests", request(session, "/api/friends/pending").optJSONArray("requests") ?: JSONArray())
        page == "people-search" -> if (search.isBlank()) JSONObject() else request(session, "/api/users/search", mapOf("q" to search, "mode" to if (search.toLongOrNull() == null) "name" else "id"))
        page == "message-search" -> if (search.isBlank()) JSONObject() else request(session, "/api/messages/search", mapOf("q" to search, "type" to "all"))
        page == "relay-key" -> {
            val config = request(session, "/api/relay/config")
            if (config.optBoolean("self_service_enabled")) config.put("credential", request(session, "/api/relay/key"))
            config
        }
        page == "service-agents" -> request(session, "/api/service-agents/catalog")
        else -> JSONObject()
    }

    fun agentFor(topic: String): String = mutable.value.workspace.conversations.find { it.id == topic }?.agentId.orEmpty()
    override fun onCleared() { channel.disconnect(); accountImages.shutdown(); super.onCleared() }
}
