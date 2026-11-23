package cc.catsco.android

import android.app.Application
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cc.catsco.android.data.*
import cc.catsco.android.ui.WorkspaceNotification
import cc.catsco.android.ui.WorkspaceNotificationKind
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
    val loadingMore: Boolean = false,
    val moreError: String? = null,
)
data class OpenLocalFile(val uri: Uri, val mime: String, val name: String = "附件")
data class AgentSettingsState(val agentId: String, val loading: Boolean = true, val quota: JSONObject? = null, val error: String? = null)

internal fun taskOutputTopic(page: String): String? =
    page.substringAfterLast('/').takeIf { it.startsWith("grp_") || it.startsWith("p2p_") }

private fun cachesModuleResource(page: String): Boolean =
    page == "apps" || page == "skills" || page.startsWith("task-outputs/")
private data class AccountCacheWrite(
    val session: Session,
    val write: AccountStorage.() -> Unit,
    val completion: CompletableDeferred<Unit>? = null,
)

data class PendingSend(val page: String, val draft: ComposerDraft, val clientId: String = "")

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
    val loadingOlderMessages: Boolean = false,
    val olderMessagesFailed: Boolean = false,
    val hasMore: Boolean = false,
    val beforeId: Long = 0,
    val sending: Boolean = false,
    val pendingSend: PendingSend? = null,
    val uploading: Boolean = false,
    val connection: String = "未连接",
    val resource: ResourceState = ResourceState(),
    val appearance: String = "system",
    val openUrl: String? = null,
    val taskCreationUncertain: Boolean = false,
    val localFile: OpenLocalFile? = null,
    val attachmentFailure: Pair<String, String>? = null,
    val exportFile: OpenLocalFile? = null,
    val agentSettings: AgentSettingsState? = null,
    val modelPickerRequested: Boolean = false,
    val quote: MessageQuote? = null,
    val stoppingTopic: String = "",
    val localSendRevision: Long = 0,
    val lastSyncedAt: Long = 0,
    val refreshingConversations: Boolean = false,
    val messageTarget: Long = 0,
    val localDataEpoch: Int = 0,
    val skillTargetId: String = "",
    val voiceCapture: VoiceCapture? = null,
    val voicePreview: String = "",
    val voiceLevel: Float = 0f,
    val voiceStartedAt: Long? = null,
    val voiceStoppedAt: Long? = null,
    val voiceLimit: Int = VoiceCharacterLimit,
    val voiceWarning: String? = null,
    val projectsLoading: Boolean = false,
    val projectsLoaded: Boolean = false,
    val projectsError: String? = null,
    val newTaskProjectId: String = "",
    val newTaskTitle: String = "",
    val awaitingReplyAfter: Map<String, Long> = emptyMap(),
    val conversationFollowups: Map<String, ConversationFollowups> = emptyMap(),
    val restoringSession: Boolean = false,
)

data class ConversationFollowups(
    val pendingClientIds: Set<String> = emptySet(),
    val sentMessageIds: Set<Long> = emptySet(),
    val lastSentMessageId: Long = 0,
)

internal val WorkspaceState.conversationRunning: Boolean
    get() = page.startsWith("chat/") && (
        workspace.conversations.any { "chat/${it.id}" == page && it.active } ||
            messages.any { it.streaming } || stoppingTopic == page.removePrefix("chat/")
        )

internal val WorkspaceState.canSendVoice: Boolean
    get() = draft.isBlank() && attachments.isEmpty() && !sending && !uploading && !busy &&
        !conversationRunning && !taskCreationUncertain && (page != "home" || employeeId.isNotBlank())

class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    private data class VoiceInputTarget(val generation: Int, val page: String, val employeeId: String)

    private val vault = KeystoreSessionStore(application)
    private val storage = AccountStorage(application)
    private val preferences = application.getSharedPreferences("appearance", 0)
    private val live = HttpWorkspaceRepository()
    private val attachmentFiles = AttachmentFiles(java.io.File(application.cacheDir, "shared-attachments"), live.client)
    val accountImages = coil.ImageLoader.Builder(application)
        .okHttpClient(live.client)
        .components { add(coil.decode.SvgDecoder.Factory()) }
        .build()
    private var repository: WorkspaceRepository = live
    private val mutable = MutableStateFlow(WorkspaceState(restoringSession = true, appearance = preferences.getString("mode", "system") ?: "system"))
    val state = mutable.asStateFlow()
    private val notificationInbox = MutableStateFlow(WorkspaceNotificationInbox())
    internal val notifications = notificationInbox.asStateFlow()
    private var outcomeSyncJob: Job? = null
    private var lastOutcomeAttempt = -1L
    private var outcomeHistoryUnavailable = false
    private val observedRunStarts = linkedMapOf<Triple<String, String, String>, Long>()
    private var generation = 0
    private var voiceInputTarget: VoiceInputTarget? = null
    private var voiceJob: Job? = null
    private var voiceStop: CompletableDeferred<Unit>? = null
    private var voiceDestination: VoiceDestination? = null
    private var voiceTaskTitle = ""
    private var employeeSelectionExplicit = false
    private var restoreTaskEmployeeAfterConversation = false
    private var lastTaskEmployeeId = ""
    private var authJob: Job? = null
    private var screenJob: Job? = null
    private var historyJob: Job? = null
    private var refreshJob: Job? = null
    private var refreshingConversationsOnly = false
    private var projectsJob: Job? = null
    private var sendJob: Job? = null
    private var uploadJob: Job? = null
    private var metadataJob: Job? = null
    private var streamingPublishJob: Job? = null
    private var agentSettingsJob: Job? = null
    private var draftSaveJob: Job? = null
    private var pollingJob: Job? = null
    private var realtimeFallback = false
    private val actions = mutableSetOf<Job>()
    private val composerDrafts = mutableMapOf<String, ComposerDraft>()
    private val moduleDrafts = mutableMapOf<String, Map<String, String>>()
    private val moduleQueries = mutableMapOf<String, String>()
    private val libraryResources = mutableMapOf<String, ResourceState>()
    private var commercialBeforeRedeem: ResourceState? = null
    private val accountCacheWrites = Channel<AccountCacheWrite>(Channel.UNLIMITED)
    private var active = true
    private val pages = mutableListOf<String>()
    val backDestination: String
        get() = pages.lastOrNull()?.takeUnless { it.startsWith("agent-settings/") } ?: "home"
    internal val inSettingsFlow: Boolean
        get() = mutable.value.page == "settings" || "settings" in pages
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
        authenticate {
            val saved = withContext(Dispatchers.IO) { vault.read() } ?: return@authenticate
            activateCachedAccount(saved, coldStart = true)
            try {
                establish(saved, resuming = true)
            } catch (error: Exception) {
                if (active && error !is CancellationException && (error as? ApiFailure)?.status != 401) {
                    channel.connect(saved)
                    startPolling()
                }
                throw error
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
            finally { if (epoch == generation) mutable.update { it.copy(busy = actions.any { action -> action.isActive }, restoringSession = false) } }
        }
    }

    private suspend fun activateCachedAccount(session: Session, coldStart: Boolean) {
        val cached = withContext(Dispatchers.IO) {
            CachedAccount(storage.cachedWorkspace(session), storage.lastTaskEmployeeId(session),
                storage.loadNotificationInbox(session, System.currentTimeMillis()), storage.draft(session, "new-task"))
        }
        lastTaskEmployeeId = cached.employeeId
        notificationInbox.value = cached.inbox
        composerDrafts[moduleDraftKey(session, "composer:new-task")] = cached.draft
        val workspace = cached.workspace ?: Workspace()
        mutable.value = WorkspaceState(session = session, workspace = workspace, appearance = mutable.value.appearance,
            employeeId = workspace.employees.defaultTaskEmployeeId(lastTaskEmployeeId),
            busy = coldStart && cached.workspace == null, restoringSession = coldStart && cached.workspace == null)
        restoreComposer("new-task")
    }

    private data class CachedAccount(val workspace: Workspace?, val employeeId: String,
        val inbox: WorkspaceNotificationInbox, val draft: ComposerDraft)

    private suspend fun establish(candidate: Session, resuming: Boolean = false) {
        val session = live.restore(candidate)
        currentCoroutineContext().ensureActive()
        repository = live
        if (!resuming || session != candidate) vault.save(session)
        if (resuming) mutable.update { it.copy(session = session, restoringSession = false) }
        else activateCachedAccount(session, coldStart = false)
        refresh()
        if (active) {
            channel.connect(session)
            startPolling()
        }
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

    fun logout(message: String? = null, keepAccountData: Boolean = false) {
        TaskOutcomeReminders.cancel(getApplication())
        persistComposer()
        discardVoiceInput()
        generation++
        notificationInbox.value = WorkspaceNotificationInbox()
        observedRunStarts.clear()
        outcomeSyncJob?.cancel()
        lastOutcomeAttempt = -1
        outcomeHistoryUnavailable = false
        libraryResources.clear()
        commercialBeforeRedeem = null
        employeeSelectionExplicit = false
        restoreTaskEmployeeAfterConversation = false
        lastTaskEmployeeId = ""
        listOf(authJob, screenJob, historyJob, refreshJob, projectsJob, sendJob, uploadJob, metadataJob, agentSettingsJob, draftSaveJob, pollingJob).forEach { it?.cancel() }
        streamingPublishJob?.cancel()
        realtimeFallback = false
        actions.toList().forEach { it.cancel() }
        authJob = null
        channel.disconnect()
        mutable.value.session?.takeUnless { keepAccountData }?.let { session ->
            viewModelScope.launch { attachmentFiles.clear(session) }
            accountCacheWrites.trySend(AccountCacheWrite(session, { clear(session) }))
        }
        composerDrafts.clear()
        moduleDrafts.clear()
        moduleQueries.clear()
        vault.clear()
        TaskWidgetProvider.clear(getApplication())
        pages.clear(); timeline = MessageTimeline(); timelineTopic = ""; repository = live
        mutable.value = WorkspaceState(error = message, appearance = mutable.value.appearance)
    }

    fun foreground(visible: Boolean) {
        if (visible) TaskOutcomeReminders.cancel(getApplication())
        if (active == visible) return
        active = visible
        if (visible) {
            mutable.value.session?.takeUnless { mutable.value.preview }?.let { session ->
                val epoch = generation
                viewModelScope.launch {
                    val notices = withContext(Dispatchers.IO) { storage.backgroundNotices(session) }
                    if (epoch == generation) mergeNotifications(notices, announce = false)
                }
            }
            mutable.value.session?.takeUnless { mutable.value.preview }?.let(channel::connect)
            startPolling()
            if (mutable.value.page.startsWith("chat/")) loadMessages()
            else if (mutable.value.page !in setOf("home", "settings")) loadResource()
        } else {
            mutable.value.session?.takeUnless { mutable.value.preview }?.let { session ->
                val inbox = notificationInbox.value
                if (inbox.since > 0) TaskOutcomeReminders.schedule(getApplication(), session, inbox.cursor.micros)
            }
            if (mutable.value.voiceCapture != null) {
                voiceJob?.cancel()
                voiceDestination = VoiceDestination.Edit
                val preview = mutable.value.voicePreview
                finishVoiceInput(preview, if (preview.isBlank()) "录音已停止。" else "录音已停止，已识别的文字已放入草稿。")
            }
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

    private fun currentDraftTopic(): String {
        val state = mutable.value
        if (state.page.startsWith("chat/")) return state.page.removePrefix("chat/")
        val projectId = state.newTaskProjectId
        return if (projectId.isBlank()) "new-task" else "new-task:$projectId"
    }
    private fun persistComposer() {
        val state = mutable.value
        if (state.page != "home" && !state.page.startsWith("chat/")) return
        val session = state.session ?: return
        val topic = currentDraftTopic()
        val prior = composerDraft(session, topic)
        saveComposerDraft(session, topic, prior.copy(
            text = state.draft, attachments = state.attachments, quote = state.quote,
            taskTitle = if (state.page == "home") state.newTaskTitle else "",
        ))
    }
    private fun restoreComposer(topic: String) {
        val session = mutable.value.session ?: return
        val saved = composerDraft(session, topic)
        mutable.update { it.copy(draft = saved.text, attachments = saved.attachments, quote = saved.quote,
            newTaskTitle = if (it.page == "home") saved.taskTitle else "",
            taskCreationUncertain = it.page == "home" && saved.pendingTaskAgent.isNotEmpty()) }
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
        if ((current.page == "apps" || current.page == "skills") && (destination == "apps" || destination == "skills")) {
            selectLibraryTab(skills = destination == "skills")
            return
        }
        persistComposer()
        if (current.page == "commercial" && destination == "redeem") {
            commercialBeforeRedeem = current.resource.takeIf { it.loaded }?.copy(loading = false)
        }
        if (destination == "home") pages.clear() else pages.add(current.page)
        openPage(destination)
    }

    fun selectLibraryTab(skills: Boolean) {
        val destination = if (skills) "skills" else "apps"
        if (destination != mutable.value.page) openPage(destination, reuseLibraryResource = true)
    }

    fun selectFileAgent(id: String) {
        if (!mutable.value.page.startsWith("files/") || mutable.value.workspace.employees.none { it.id == id }) return
        openPage("files/$id")
    }

    fun openTaskOutputs() {
        val state = mutable.value
        val scope = if (state.page.startsWith("chat/")) state.page.removePrefix("chat/")
        else state.workspace.employees.firstOrNull { it.id == state.employeeId }?.id
            ?: state.workspace.employees.firstOrNull()?.id.orEmpty()
        navigate("task-outputs/files/$scope")
    }

    fun selectTaskOutputTab(apps: Boolean) {
        val page = mutable.value.page
        if (!page.startsWith("task-outputs/")) return
        val tab = if (apps) "apps" else "files"
        val destination = "task-outputs/$tab/${page.substringAfterLast('/')}"
        if (page != destination) openPage(destination, reuseLibraryResource = true)
    }

    fun selectTaskOutputAgent(id: String) {
        val state = mutable.value
        val page = state.page
        if (!page.startsWith("task-outputs/") || taskOutputTopic(page) != null ||
            state.workspace.employees.none { it.id == id }) return
        val destination = page.substringBeforeLast('/') + "/$id"
        if (page != destination) openPage(destination, reuseLibraryResource = true)
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
        } else if (mutable.value.page == "redeem" && destination == "commercial") {
            val restored = commercialBeforeRedeem
            commercialBeforeRedeem = null
            openPage(destination, restoredResource = restored)
        } else openPage(destination)
    }

    fun refreshEntitlementsAfterRedemption() {
        commercialBeforeRedeem = null
        if (mutable.value.page == "commercial") loadResource()
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
        val chooseModel = page == "model/$id"
        if (chooseModel && mutable.value.workspace.employees.none { it.id == id && it.owner }) return
        persistComposer()
        dismissAgentSettings()
        pages.add("agent-settings/$id")
        openPage(page, modelPickerRequested = chooseModel)
    }

    fun consumeModelPickerRequest() = mutable.update { it.copy(modelPickerRequested = false) }

    private fun openPage(page: String, modelPickerRequested: Boolean = false, reuseLibraryResource: Boolean = false,
        confirmedMessage: ChatMessage? = null, restoredResource: ResourceState? = null) {
        screenJob?.cancel(); historyJob?.cancel()
        if (mutable.value.page != page) discardVoiceInput()
        if (page.startsWith("chat/")) restoreTaskEmployeeAfterConversation = true
        val restoreTaskEmployee = page == "home" && restoreTaskEmployeeAfterConversation
        if (restoreTaskEmployee) {
            restoreTaskEmployeeAfterConversation = false
            employeeSelectionExplicit = false
        }
        val libraryPage = cachesModuleResource(page)
        if (!libraryPage && cachesModuleResource(mutable.value.page)) libraryResources.clear()
        val query = if (page == "skills") moduleQuery("skills") else ""
        val cachedResource = restoredResource
            ?: libraryResources[page]?.takeIf { reuseLibraryResource && it.query == query }
        val confirmedTopic = page.removePrefix("chat/")
        if (confirmedMessage != null) {
            if (timelineTopic != confirmedTopic) {
                timeline = MessageTimeline()
                timelineTopic = confirmedTopic
            }
            timeline.receive(confirmedMessage)
        }
        mutable.update {
            val destination = it.copy(page = page,
                employeeId = if (restoreTaskEmployee) it.workspace.employees.defaultTaskEmployeeId(lastTaskEmployeeId) else it.employeeId,
                modelPickerRequested = modelPickerRequested,
                resource = cachedResource ?: ResourceState(loading = libraryPage, query = query), error = null, notice = null,
                openUrl = null, messageTarget = 0, loadingMessages = false, loadingOlderMessages = false, olderMessagesFailed = false)
            if (confirmedMessage == null) destination else destination.withTimeline(confirmedTopic, timeline.messages)
                .copy(loadingMessages = false, beforeId = 0, hasMore = false)
        }
        if (page == "home" || page.startsWith("chat/")) restoreComposer(currentDraftTopic())
        if (page.startsWith("chat/")) {
            val topic = page.removePrefix("chat/")
            if (timelineTopic != topic) {
                timeline = MessageTimeline(); timelineTopic = topic
                mutable.update { it.copy(messages = emptyList(), beforeId = 0, hasMore = false) }
            }
            channel.attention(topic)
            if (confirmedMessage == null) loadMessages(restoreCache = timeline.messages.isEmpty())
        } else {
            channel.attention("")
            if (cachedResource == null && page !in setOf("home", "settings", "files", "appearance", "wallpaper", "offline", "notifications", "surfaces", "feedback", "server", "redeem")) loadResource()
        }
    }

    fun refresh() = refreshWorkspace(conversationsOnly = false)

    fun refreshConversations(manual: Boolean = false) {
        if (mutable.value.preview && !manual) return
        refreshWorkspace(conversationsOnly = true, manual = manual)
    }

    private fun refreshWorkspace(conversationsOnly: Boolean, manual: Boolean = false) {
        val session = mutable.value.session ?: return
        if (manual) mutable.update { it.copy(refreshingConversations = true) }
        val previousRefresh = refreshJob?.takeIf { it.isActive }
        if (previousRefresh != null && (conversationsOnly || !refreshingConversationsOnly)) return
        val epoch = generation
        refreshingConversationsOnly = conversationsOnly
        refreshJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val workspace = if (conversationsOnly) {
                    val conversations = repository.conversations(session)
                    mutable.value.workspace.copy(conversations = conversations)
                } else {
                    val received = repository.workspace(session)
                    val previous = withContext(Dispatchers.IO) { storage.cachedWorkspace(session)?.employees }
                    received.copy(employees = received.employees.withObservedAdditions(previous, System.currentTimeMillis()))
                }
                ensureActive()
                if (epoch != generation) return@launch
                val workspaceChanged = workspace.conversations != mutable.value.workspace.conversations ||
                    workspace.employees != mutable.value.workspace.employees
                if (mutable.value.workspace.employees.map { it.id }.toSet() != workspace.employees.map { it.id }.toSet()) {
                    libraryResources.remove("apps")
                }
                mutable.update {
                    val keepSelection = (employeeSelectionExplicit || it.sending || it.taskCreationUncertain || it.draft.isNotBlank() || it.attachments.isNotEmpty()) &&
                        workspace.employees.any { agent -> agent.id == it.employeeId }
                    val stoppingTopic = it.stoppingTopic.takeIf { topic ->
                        it.busy || workspace.conversations.any { conversation -> conversation.id == topic && conversation.active }
                    }.orEmpty()
                    it.copy(workspace = workspace.copy(projects = it.workspace.projects),
                        awaitingReplyAfter = it.awaitingReplyAfter - workspace.conversations.filter { chat -> chat.active }.map { chat -> chat.id }.toSet(),
                        employeeId = if (conversationsOnly || keepSelection) it.employeeId else workspace.employees.defaultTaskEmployeeId(lastTaskEmployeeId),
                        stoppingTopic = stoppingTopic, lastSyncedAt = System.currentTimeMillis())
                }
                if (!mutable.value.preview) {
                    workspace.conversations.mapNotNull { it.runStatus }.sortedBy { it.updatedAt }.forEach(::receiveRunStatus)
                    syncTaskOutcomes(session, manual)
                }
                if (!conversationsOnly || workspaceChanged) {
                    TaskWidgetProvider.update(getApplication(), workspace.conversations, syncedAt = mutable.value.lastSyncedAt)
                    val saved = CompletableDeferred<Unit>()
                    accountCacheWrites.send(AccountCacheWrite(session, { saveWorkspace(session, workspace) }, saved))
                    saved.await()
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (epoch == generation && (!conversationsOnly || mutable.value.refreshingConversations ||
                        (error is ApiFailure && error.status == 401))) report(error)
            } finally {
                if (epoch == generation && (refreshJob == null || refreshJob === coroutineContext[Job])) {
                    mutable.update { it.copy(refreshingConversations = false) }
                }
            }
        }
        previousRefresh?.cancel()
        refreshJob?.start()
    }

    fun selectEmployee(id: String) {
        if (mutable.value.workspace.employees.none { it.id == id }) return
        if (mutable.value.page == "home" && mutable.value.employeeId != id) discardVoiceInput()
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
        mutable.update { it.copy(loadingMessages = true, loadingOlderMessages = false, olderMessagesFailed = false, error = null) }
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
    private fun currentVoiceInputTarget(): VoiceInputTarget? {
        val state = mutable.value
        if (state.session == null || state.preview) return null
        return when {
            state.page == "home" -> VoiceInputTarget(generation, state.page, state.employeeId)
            state.page.startsWith("chat/") -> VoiceInputTarget(generation, state.page, "")
            else -> null
        }
    }
    fun startVoiceInput(taskTitle: String): Boolean {
        val state = mutable.value
        val session = state.session ?: return false
        if (voiceInputTarget != null || state.busy || state.uploading || state.sending || !active) return false
        if (getApplication<Application>().checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val prefix = voiceDraftPrefix(state.draft)
        val limit = VoiceCharacterLimit - prefix.voiceCharacterCount()
        if (limit <= 0) {
            notice("草稿已达到 ${VoiceCharacterLimit} 字，请先缩短草稿。")
            return false
        }
        voiceInputTarget = currentVoiceInputTarget() ?: return false
        voiceTaskTitle = taskTitle
        val stop = CompletableDeferred<Unit>()
        voiceStop = stop
        mutable.update {
            it.copy(voiceCapture = VoiceCapture.Connecting, voicePreview = "", voiceLevel = 0f,
                voiceStartedAt = null, voiceStoppedAt = null, voiceLimit = limit, voiceWarning = null, notice = null)
        }
        voiceJob = viewModelScope.launch {
            try {
                val transcript = VoiceTranscription(live).recognize(session, stop, limit,
                    onAudioReady = {
                        mutable.update {
                            if (it.voiceCapture == VoiceCapture.Connecting) {
                                it.copy(voiceCapture = VoiceCapture.Recording, voiceStartedAt = SystemClock.elapsedRealtime())
                            } else it
                        }
                    },
                    onAudioStopped = {
                        mutable.update {
                            if (it.voiceStartedAt != null && it.voiceStoppedAt == null) it.copy(voiceStoppedAt = SystemClock.elapsedRealtime())
                            else it
                        }
                    },
                    onLevel = { level -> mutable.update { if (it.voiceCapture == VoiceCapture.Recording) it.copy(voiceLevel = level) else it } },
                    onText = { text -> mutable.update { it.copy(voicePreview = text) } },
                )
                finishVoiceInput(transcript)
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (failure: Exception) {
                val warning = when (failure) {
                    is SecurityException -> "请在系统设置中允许 CatsCo 使用麦克风。"
                    is VoiceTranscriptionFailure -> failure.message
                    is ApiFailure -> when (failure.status) {
                        401, 403, 404 -> failure.message
                        409, 429 -> "语音服务繁忙，请稍后重试。"
                        else -> "语音服务暂时不可用，请稍后重试。"
                    }
                    else -> null
                } ?: "语音识别失败，请重试。"
                finishVoiceInput(mutable.value.voicePreview, warning)
            }
        }
        return true
    }

    internal fun completeVoiceInput(destination: VoiceDestination) {
        val state = mutable.value
        if (state.voiceCapture == null || voiceDestination != null) return
        voiceDestination = destination
        if (state.voiceCapture == VoiceCapture.Ready) {
            finishVoiceInput(state.voicePreview, state.voiceWarning)
        } else {
            voiceStop?.complete(Unit)
            mutable.update {
                it.copy(voiceCapture = VoiceCapture.Finalizing, voiceLevel = 0f,
                    voiceStoppedAt = it.voiceStoppedAt ?: SystemClock.elapsedRealtime())
            }
        }
    }

    private fun finishVoiceInput(text: String, warning: String? = null) {
        val target = voiceInputTarget ?: return
        voiceJob = null
        voiceStop = null
        if (target != currentVoiceInputTarget()) {
            discardVoiceInput()
            return
        }
        val state = mutable.value
        val transcript = text.trim().takeVoiceCharacters(state.voiceLimit)
        if (transcript.isBlank()) {
            discardVoiceInput()
            if (warning != null) notice(warning)
            return
        }
        val destination = voiceDestination
        if (destination == null) {
            mutable.update {
                it.copy(voiceCapture = VoiceCapture.Ready, voicePreview = transcript, voiceLevel = 0f, voiceWarning = warning,
                    voiceStoppedAt = it.voiceStoppedAt ?: SystemClock.elapsedRealtime())
            }
            return
        }
        val title = voiceTaskTitle
        val sendNow = destination == VoiceDestination.Send && warning == null && state.canSendVoice
        discardVoiceInput()
        val prefix = voiceDraftPrefix(state.draft)
        val remaining = (VoiceCharacterLimit - prefix.voiceCharacterCount()).coerceAtLeast(0)
        if (remaining == 0) {
            notice("草稿已达到 ${VoiceCharacterLimit} 字，请先缩短草稿。")
            return
        }
        draft(prefix + transcript.takeVoiceCharacters(remaining))
        if (sendNow) sendDraft(title)
        else if (warning != null || destination == VoiceDestination.Send) notice(warning ?: "已放入草稿，可稍后发送。")
    }

    fun discardVoiceInput() {
        if (voiceInputTarget == null) return
        voiceJob?.cancel()
        voiceJob = null
        voiceStop = null
        voiceInputTarget = null
        voiceDestination = null
        voiceTaskTitle = ""
        mutable.update {
            it.copy(voiceCapture = null, voicePreview = "", voiceLevel = 0f, voiceWarning = null,
                voiceStartedAt = null, voiceStoppedAt = null)
        }
    }

    private fun voiceDraftPrefix(draft: String): String = when {
        draft.isBlank() -> ""
        draft.endsWith('\n') -> draft
        else -> "$draft\n"
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

    fun loadMessages(older: Boolean = false, quiet: Boolean = false, restoreCache: Boolean = false) {
        val state = mutable.value
        val session = state.session ?: return
        if (!state.page.startsWith("chat/") || state.preview || state.messageTarget > 0 ||
            (older && (!state.hasMore || state.beforeId <= 0))) return
        if (historyJob?.isActive == true) return
        val topic = state.page.removePrefix("chat/")
        val epoch = generation
        val requestedTimeline = timeline
        mutable.update { it.copy(
            loadingMessages = !quiet || state.hasMore,
            loadingOlderMessages = older,
            olderMessagesFailed = !older && it.olderMessagesFailed,
        ) }
        historyJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (restoreCache) {
                    val cached = withContext(Dispatchers.IO) {
                        storage.read(session, "messages:$topic")?.let { raw ->
                            runCatching {
                                val snapshot = JSONObject(raw)
                                val messages = snapshot.objects("messages").map(::chatMessage)
                                val oldestCached = messages.filter { it.sequence > 0 && it.kind != "runtime_plan" }
                                    .minOfOrNull { it.sequence } ?: 0
                                messages to snapshot.optLong("history_synced_through", oldestCached)
                            }.getOrNull()
                        }
                    }
                    ensureActive()
                    if (generation != epoch || timelineTopic != topic || timeline !== requestedTimeline) return@launch
                    if (cached != null) {
                        val (cachedMessages, syncedThrough) = cached
                        requestedTimeline.markHistorySyncedThrough(syncedThrough)
                        val liveKeys = requestedTimeline.messages.mapTo(hashSetOf()) { it.key }
                        requestedTimeline.merge(cachedMessages.filterNot { it.key in liveKeys })
                        mutable.update { it.withTimeline(topic, requestedTimeline.messages) }
                    }
                }
                val syncedThrough = requestedTimeline.historySyncedThrough
                val fetchPage: suspend (Long, Int) -> MessageHistoryPage = { beforeId, limit ->
                    val result = request(session, "/api/messages", mapOf("topic_id" to topic,
                        "limit" to limit.toString(), "latest" to "1", "before_id" to beforeId.toString()))
                    withContext(Dispatchers.Default) {
                        val messages = result.objects("messages").map(::chatMessage)
                        val next = result.optLong("next_before_id", messages.minOfOrNull { it.sequence } ?: 0)
                        val more = result.optBoolean("has_more", messages.size == limit)
                        MessageHistoryPage(messages, next, more)
                    }
                }
                val page = if (older) fetchPage(state.beforeId, 50) else loadLatestHistory(syncedThrough, fetchPage)
                ensureActive()
                if (generation != epoch || timelineTopic != topic || timeline !== requestedTimeline) return@launch
                requestedTimeline.merge(page.messages)
                if (!older) requestedTimeline.markHistorySyncedThrough(page.messages
                    .filterNot { it.kind in setOf("runtime_plan", "stream_delta", "stream_cancel") || it.streamEvent in setOf("delta", "cancel") }
                    .maxOfOrNull { it.sequence } ?: 0)
                val merged = requestedTimeline.messages
                val messagesChanged = merged != mutable.value.messages
                val completedThrough = requestedTimeline.historySyncedThrough
                val hasOlderPage = page.beforeId > 0 && (!older || page.beforeId < state.beforeId) && page.hasMore
                mutable.update { current -> current.withTimeline(topic, merged).copy(
                    beforeId = if (older || current.beforeId == 0L) page.beforeId else current.beforeId,
                    hasMore = if (older || current.beforeId == 0L) hasOlderPage else current.hasMore) }
                if (messagesChanged || completedThrough != syncedThrough) {
                    val snapshot = merged.filter { !it.streaming && (it.sequence <= completedThrough || it.kind == "runtime_plan") }.takeLast(100)
                    accountCacheWrites.send(AccountCacheWrite(session, {
                        write(session, "messages:$topic", JSONObject().put("history_synced_through", completedThrough)
                            .put("messages", JSONArray(snapshot.map { message ->
                                JSONObject(message.raw).apply { if (message.kind == "runtime_plan") put("_after_sequence", message.afterSequence) }
                            })).toString())
                    }))
                }
                if (active && mutable.value.page == "chat/$topic") channel.read(topic, completedThrough)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (epoch == generation && timelineTopic == topic) {
                    if (error is ApiFailure && error.status == 403) {
                        timeline = MessageTimeline()
                        accountCacheWrites.trySend(AccountCacheWrite(session, { write(session, "messages:$topic", "{}") }))
                        mutable.update { it.copy(messages = emptyList(), hasMore = false, beforeId = 0) }
                    }
                    if (older && (error !is ApiFailure || error.status !in setOf(401, 403))) {
                        mutable.update { it.copy(olderMessagesFailed = true) }
                    } else if (!quiet || error is ApiFailure) {
                        report(error)
                    }
                }
            }
            finally {
                if (epoch == generation && historyJob === coroutineContext[Job]) {
                    mutable.update { it.copy(loadingMessages = false, loadingOlderMessages = false) }
                }
            }
        }
        historyJob?.start()
    }

    private fun receive(event: JSONObject) {
        if (mutable.value.session == null || mutable.value.preview) return
        if (event.optString("_type") == "ws_open") {
            realtimeFallback = false
            loadMessages(quiet = true)
            refreshConversations()
            return
        }
        val runStatus = (event.optJSONObject("task_status_source") ?: event.optJSONObject("task_status"))
            ?.let { conversationRunStatus(it) }
        runStatus?.let(::receiveRunStatus)
        val data = event.optJSONObject("data")
        val state = mutable.value
        val topic = timelineTopic
        if (data != null && data.first("topic", "topic_id") == topic && state.page == "chat/$topic" && state.messageTarget == 0L) {
            val message = chatMessage(data)
            timeline.receive(message)
            val repeatedDelta = (message.kind == "stream_delta" || message.streamEvent == "delta") &&
                state.messages.any { it.streaming && it.streamId == message.streamId }
            if (!repeatedDelta) {
                streamingPublishJob?.cancel()
                streamingPublishJob = null
                mutable.update { it.withTimeline(topic, timeline.messages) }
            } else if (streamingPublishJob?.isActive != true) {
                val liveTimeline = timeline
                streamingPublishJob = viewModelScope.launch {
                    delay(32)
                    val current = mutable.value
                    if (timeline === liveTimeline && timelineTopic == topic && current.page == "chat/$topic" && current.messageTarget == 0L) {
                        mutable.update { it.withTimeline(topic, liveTimeline.messages) }
                    }
                }
            }
        }
        val previousRun = runStatus?.let { status -> state.workspace.conversations.find { it.id == status.topic }?.runStatus }
        val runChanged = runStatus != null && (previousRun?.runId != runStatus.runId || previousRun.state != runStatus.state)
        val rosterChanged = event.optJSONObject("pres")?.first("topic", "src")?.startsWith("grp_") == true ||
            event.has("session_fork") || runChanged ||
            (data != null && data.optLong("seq", data.optLong("id")) > 0)
        if (rosterChanged && metadataJob?.isActive != true) {
            metadataJob = viewModelScope.launch {
                delay(350)
                refreshJob?.join()
                refreshConversations()
            }
        }
        if (event.has("friend")) refresh()
    }

    private fun receiveRunStatus(status: ConversationRunStatus) {
        val conversation = mutable.value.workspace.conversations.find { it.id == status.topic } ?: return
        val run = Triple(status.topic, status.sourceId, status.runId)
        if (status.state in setOf("running", "waiting", "queued", "working", "pending")) {
            observedRunStarts.putIfAbsent(run, status.updatedAt)
            if (observedRunStarts.size > 128) observedRunStarts.remove(observedRunStarts.keys.first())
            return
        }
        val startedAt = observedRunStarts.remove(run) ?: 0L
        if (status.updatedAt < notificationInbox.value.since) return
        val item = runNotification(status, conversation, startedAt) ?: return
        addNotification(item, if (item.kind == WorkspaceNotificationKind.Quota) "chat/${status.topic}" else null)
    }

    private fun addNotification(item: WorkspaceNotification, quotaPage: String? = null) {
        mergeNotifications(listOf(item), quotaPage = quotaPage)
    }

    private fun syncTaskOutcomes(session: Session, manual: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (outcomeHistoryUnavailable || outcomeSyncJob?.isActive == true ||
            (!manual && lastOutcomeAttempt >= 0 && now - lastOutcomeAttempt < 60_000L)) return
        lastOutcomeAttempt = now
        val epoch = generation
        outcomeSyncJob = viewModelScope.launch {
            try {
                val inbox = notificationInbox.value
                // Replay a short overlap for concurrent commits that become visible out of order.
                var pageCursor = OutcomeCursor(maxOf(inbox.since * 1000, inbox.cursor.micros - 60_000_000), 0)
                do {
                    val page = live.taskOutcomes(session, pageCursor)
                    if (epoch != generation) return@launch
                    val conversations = mutable.value.workspace.conversations.associateBy { it.id }
                    val items = page.statuses.mapNotNull { status ->
                        val conversation = conversations[status.topic] ?: Conversation(status.topic, "任务")
                        runNotification(status, conversation, 0)
                    }
                    mergeNotifications(items, cursor = page.next, announce = !page.hasMore)
                    pageCursor = page.next
                } while (page.hasMore)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: ApiFailure) {
                if (epoch != generation) return@launch
                if (error.status in setOf(404, 405, 501)) outcomeHistoryUnavailable = true
                if (error.status == 401) report(error)
            } catch (_: java.io.IOException) {
                // Retry from the last committed page on reconnect.
            } catch (_: org.json.JSONException) {
                // Older deployments may return non-API content for unknown routes.
            } catch (_: IllegalStateException) {
                // A proxy may serve HTML or a body exceeding the API response limit.
            }
        }
    }

    private fun mergeNotifications(newItems: List<WorkspaceNotification>, cursor: OutcomeCursor? = null, quotaPage: String? = null, announce: Boolean = true) {
        val inbox = notificationInbox.value
        val keys = inbox.items.mapTo(mutableSetOf()) { it.key }
        val arrivals = newItems.filter { keys.add(it.key) }
        val items = if (arrivals.isEmpty()) inbox.items else recentNotices(inbox.items + arrivals)
        val latest = if (announce && arrivals.isNotEmpty()) {
            val newKeys = arrivals.mapTo(mutableSetOf()) { it.key }
            items.firstOrNull { it.key in newKeys }
        } else null
        val next = inbox.copy(items = items, cursor = maxOf(cursor ?: inbox.cursor, inbox.cursor),
            latestKey = latest?.key ?: inbox.latestKey, arrivalElapsed = if (latest != null) SystemClock.elapsedRealtime() else inbox.arrivalElapsed,
            quotaPage = if (latest != null) quotaPage ?: inbox.quotaPage else inbox.quotaPage)
        if (next != inbox) {
            notificationInbox.value = next
            persistNotifications()
        }
    }

    private fun persistNotifications() {
        val session = mutable.value.session ?: return
        val inbox = notificationInbox.value
        accountCacheWrites.trySend(AccountCacheWrite(session, { saveNotificationInbox(session, inbox) }))
    }

    internal fun readAllNotifications() {
        val items = notificationInbox.value.items.map { it.copy(unread = false) }
        notificationInbox.update { it.copy(items = items) }
        persistNotifications()
    }

    internal fun readNotification(item: WorkspaceNotification) {
        if (notificationInbox.value.items.none { it.key == item.key && it.unread }) return
        val items = notificationInbox.value.items.map { if (it.key == item.key) it.copy(unread = false) else it }
        notificationInbox.update { it.copy(items = items) }
        persistNotifications()
    }

    internal fun openNotification(item: WorkspaceNotification) {
        readNotification(item)
        if (item.kind == WorkspaceNotificationKind.Quota) navigate("commercial")
        else openConversation(item.topic, item.title)
    }

    internal suspend fun resolveNotificationReply(item: WorkspaceNotification): String? {
        val session = mutable.value.session ?: return null
        if (item.kind == WorkspaceNotificationKind.Quota || item.sourceId.isBlank()) return null
        return try {
            val result = request(session, "/api/messages", mapOf("topic_id" to item.topic, "latest" to "1", "limit" to "50"))
            withContext(Dispatchers.Default) { replyForNotification(item, result) }
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (_: Exception) {
            null
        }
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
                if (error is ApiFailure && error.status == 401) logout(error.message, keepAccountData = true)
                else {
                    realtimeFallback = true
                    mutable.update { it.copy(connection = "自动同步") }
                    startPolling()
                }
            }
        }
    }

    private fun WorkspaceState.withTimeline(topic: String, updatedMessages: List<ChatMessage>): WorkspaceState {
        val after = awaitingReplyAfter[topic]
        val agent = workspace.conversations.find { it.id == topic }?.agentId
        val replyStarted = after != null && updatedMessages.any { message ->
            message.senderId == agent && (message.streaming || message.sequence > after || message.afterSequence >= after)
        }
        return copy(messages = updatedMessages, awaitingReplyAfter = if (replyStarted) awaitingReplyAfter - topic else awaitingReplyAfter)
    }

    private fun awaitAgentReply(topic: String, sequence: Long) {
        if (sequence <= 0 || agentFor(topic).isBlank()) return
        if (mutable.value.workspace.conversations.any { it.id == topic && it.active }) return
        mutable.update { it.copy(awaitingReplyAfter = it.awaitingReplyAfter + (topic to sequence)) }
        val epoch = generation
        viewModelScope.launch {
            delay(20_000)
            if (epoch == generation) mutable.update {
                if (it.awaitingReplyAfter[topic] == sequence) it.copy(awaitingReplyAfter = it.awaitingReplyAfter - topic) else it
            }
        }
    }

    private fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            var tick = 0
            while (active && mutable.value.session != null && !mutable.value.preview) {
                if (realtimeFallback && tick > 0 && tick % 12 == 0) mutable.value.session?.let(channel::connect)
                if (!realtimeFallback || tick++ % 3 == 0) refreshConversations()
                loadMessages(quiet = true)
                delay(if (realtimeFallback) 5_000 else 15_000)
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

    fun loadProjects() {
        val state = mutable.value
        val session = state.session ?: return
        if (state.preview || state.projectsLoading) return
        val epoch = generation
        mutable.update { it.copy(projectsLoading = true, projectsError = null) }
        projectsJob = viewModelScope.launch {
            try {
                val projects = request(session, "/api/projects").objects("projects").mapNotNull(::parseProject)
                mutable.update { it.copy(projectsLoaded = true, workspace = it.workspace.copy(projects = projects)) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (epoch == generation) mutable.update { it.copy(projectsError = "项目暂时无法读取。") }
            } finally {
                if (epoch == generation) mutable.update { it.copy(projectsLoading = false) }
            }
        }
    }

    private fun parseProject(value: JSONObject): Project? {
        val id = value.optLong("id")
        val name = value.first("name")
        return if (id > 0 && name.isNotBlank()) Project(id.toString(), name) else null
    }

    fun createProject(name: String, onCreated: (Project) -> Unit) = action { session ->
        val response = request(session, "/api/projects", method = "POST", body = JSONObject().put("name", name.trim()))
        val project = response.optJSONObject("project")?.let(::parseProject) ?: error("未收到可用的项目信息。")
        updateProjects(session) {
            it.copy(workspace = it.workspace.copy(projects = listOf(project) + it.workspace.projects.filterNot { item -> item.id == project.id }))
        }
        onCreated(project)
    }

    fun renameProject(id: String, name: String, onSuccess: () -> Unit) = action { session ->
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "请输入项目名称。" }
        request(session, "/api/projects", method = "PATCH", body = JSONObject().put("project_id", id.toLong()).put("name", trimmed))
        updateProjects(session) { state ->
            state.copy(workspace = state.workspace.copy(projects = state.workspace.projects.map { if (it.id == id) it.copy(name = trimmed) else it }))
        }
        onSuccess()
    }

    fun deleteProject(id: String, onSuccess: () -> Unit) = action { session ->
        request(session, "/api/projects", query = mapOf("project_id" to id), method = "DELETE")
        updateProjects(session) { state ->
            state.copy(newTaskProjectId = state.newTaskProjectId.takeUnless { it == id }.orEmpty(),
                workspace = state.workspace.copy(projects = state.workspace.projects.filterNot { it.id == id },
                    conversations = state.workspace.conversations.map { if (it.projectId == id) it.copy(projectId = "") else it }))
        }
        onSuccess()
    }

    private fun updateProjects(session: Session, change: (WorkspaceState) -> WorkspaceState) {
        projectsJob?.cancel()
        refreshJob?.cancel()
        refreshJob = null
        mutable.update { change(it).copy(projectsLoading = false, refreshingConversations = false) }
        val updated = mutable.value.workspace
        accountCacheWrites.trySend(AccountCacheWrite(session, { saveWorkspace(session, updated) }))
    }

    private suspend fun assignProject(session: Session, topic: String, projectId: String) {
        if (projectId.isEmpty()) {
            request(session, "/api/projects/topic", query = mapOf("topic_id" to topic), method = "DELETE")
        } else {
            request(session, "/api/projects/topic", method = "POST",
                body = JSONObject().put("topic_id", topic).put("project_id", projectId.toLong()))
        }
        refreshJob?.cancel()
        refreshJob = null
        mutable.update { current ->
            current.copy(workspace = current.workspace.copy(conversations = current.workspace.conversations.map {
                if (it.id == topic) it.copy(projectId = projectId) else it
            }))
        }
    }

    fun moveToProject(topic: String, projectId: String, onSuccess: () -> Unit) = action { session ->
        assignProject(session, topic, projectId)
        refresh()
        onSuccess()
    }

    fun startProjectTask(projectId: String = "") {
        val state = mutable.value
        if (state.sending || state.uploading || state.taskCreationUncertain) return
        if (state.newTaskProjectId == projectId && state.page == "home") return
        persistComposer()
        discardVoiceInput()
        mutable.update { it.copy(newTaskProjectId = projectId) }
        if (state.page == "home") restoreComposer(currentDraftTopic())
        else {
            pages.clear()
            openPage("home")
        }
    }

    fun renameNewTask(title: String) {
        if (mutable.value.page != "home") return
        mutable.update { it.copy(newTaskTitle = title.trim()) }
        persistComposer()
    }

    private fun sendDraft(taskTitle: String = "", regeneration: ComposerDraft? = null) {
        val state = mutable.value
        val session = state.session ?: return
        if (sendJob?.isActive == true || state.uploading) return
        if (state.preview) { report(IllegalStateException("调试预览不发送任务，请使用真实账号登录。")); return }
        if (regeneration == null && state.draft.isBlank() && state.attachments.isEmpty()) return
        if (state.taskCreationUncertain && state.page == "home") { report(IllegalStateException("上次创建结果未确认，请先刷新并检查历史任务。")); return }
        val originalTopic = currentDraftTopic()
        val newTask = state.page == "home"
        if (!newTask && state.stoppingTopic == originalTopic) return
        val followup = regeneration == null && state.conversationRunning
        if (state.messageTarget > 0) showLatestMessages()
        draftSaveJob?.cancel()
        persistComposer()
        val epoch = generation
        val original = regeneration ?: composerDraft(session, originalTopic)
        val snapshot = if (newTask && original.taskCreationId.isBlank()) {
            original.copy(taskCreationId = UUID.randomUUID().toString())
        } else original
        mutable.update {
            it.copy(sending = true, localSendRevision = it.localSendRevision + 1, error = null,
                pendingSend = if (regeneration == null) PendingSend(state.page, snapshot) else null)
        }
        sendJob = viewModelScope.launch {
            var topic = originalTopic
            var assigningProject = false
            try {
                if (newTask) {
                    require(state.employeeId.toLongOrNull()?.let { it > 0 } == true) { "请先选择 Agent。" }
                    val name = taskTitle.ifBlank { snapshot.taskTitle }.takeUnless { it.isBlank() || it == "新任务" }
                        ?: snapshot.text.trim().replace(Regex("\\s+"), " ").take(36).ifBlank { "附件任务" }
                    persistDeliveryDraft(session, topic, snapshot.copy(pendingTaskAgent = state.employeeId))
                    val created = request(session, "/api/groups/create", method = "POST", body = JSONObject()
                        .put("name", name).put("kind", "agent_task").put("client_task_id", snapshot.taskCreationId)
                        .put("member_ids", JSONArray().put(state.employeeId.toLong())))
                    ensureActive()
                    if (epoch != generation) return@launch
                    val groupId = maxOf(created.optJSONObject("group")?.optLong("id") ?: 0, created.optLong("group_id"))
                    check(groupId > 0) { "创建结果未确认，请检查历史任务。" }
                    topic = "grp_$groupId"
                    persistDeliveryDraft(session, topic, snapshot.copy(pendingTaskAgent = "", taskTitle = "", taskCreationId = ""))
                    persistComposer()
                    refreshJob?.cancel()
                    refreshJob = null
                    val conversation = Conversation(topic, name, agentId = state.employeeId, task = true, date = Instant.now().toString())
                    mutable.update {
                        it.copy(workspace = it.workspace.copy(conversations =
                            listOf(conversation) + it.workspace.conversations.filterNot { item -> item.id == topic }),
                            taskCreationUncertain = false)
                    }
                    if (state.newTaskProjectId.isNotBlank()) {
                        assigningProject = true
                        assignProject(session, topic, state.newTaskProjectId)
                        assigningProject = false
                    }
                }
                val deliverySlot = if (regeneration != null) "regenerate:$topic" else topic
                val fingerprint = snapshot.text + "\n" + snapshot.attachments.joinToString("\n") { it.raw } +
                    snapshot.quote?.let { "\nreply:${it.messageId}" }.orEmpty()
                val saved = if (regeneration != null) withContext(Dispatchers.IO) { storage.draft(session, deliverySlot) }
                    else composerDraft(session, deliverySlot)
                val clientId = when {
                    saved.fingerprint == fingerprint && saved.clientId.isNotBlank() -> saved.clientId
                    newTask -> snapshot.taskCreationId
                    else -> UUID.randomUUID().toString()
                }
                val sendingFollowup = followup || (saved.clientId == clientId && saved.followup) ||
                    clientId in mutable.value.conversationFollowups[topic]?.pendingClientIds.orEmpty()
                persistDeliveryDraft(session, deliverySlot, snapshot.copy(clientId = clientId, fingerprint = fingerprint,
                    taskTitle = "", taskCreationId = "", followup = sendingFollowup))
                if (sendingFollowup) {
                    mutable.update {
                        val batch = it.conversationFollowups[topic] ?: ConversationFollowups()
                        it.copy(conversationFollowups = it.conversationFollowups +
                            (topic to batch.copy(pendingClientIds = batch.pendingClientIds + clientId)))
                    }
                }
                val body = messageSendBody(topic, snapshot, clientId)
                if (sendingFollowup) body.put("metadata", JSONObject().put("task_followup", true))
                mutable.update { it.copy(pendingSend = it.pendingSend?.copy(clientId = clientId)) }
                val result = request(session, "/api/messages/send", method = "POST", body = body)
                ensureActive()
                if (epoch != generation) return@launch
                notificationInbox.update { inbox ->
                    if (inbox.quotaPage == state.page || inbox.quotaPage == "chat/$topic") inbox.copy(quotaPage = null) else inbox
                }
                val receipt = result.optJSONObject("message") ?: result
                val delivered = JSONObject(body.toString()).put("from_uid", session.userId).put("from_name", session.name)
                receipt.keys().forEach { key -> delivered.put(key, receipt.get(key)) }
                val deliveredMessage = chatMessage(delivered)
                if (regeneration == null && !sendingFollowup) awaitAgentReply(topic, deliveredMessage.sequence)
                if (newTask) {
                    lastTaskEmployeeId = state.employeeId
                    val savedSelection = CompletableDeferred<Unit>()
                    accountCacheWrites.send(AccountCacheWrite(session, {
                        saveLastTaskEmployeeId(session, state.employeeId)
                    }, savedSelection))
                    try { savedSelection.await() }
                    catch (canceled: CancellationException) { throw canceled }
                    catch (_: Exception) { notice("任务已发送，暂时无法保存默认员工。") }
                    if (epoch != generation) return@launch
                    finishCreatedTask(session, originalTopic, topic, snapshot, confirmedMessage = deliveredMessage.takeIf { it.sequence > 0 })
                }
                if (currentDraftTopic() == topic) persistComposer()
                val latest = composerDraft(session, topic)
                val clearComposer = regeneration == null && latest.text == snapshot.text &&
                    latest.attachments == snapshot.attachments && latest.quote == snapshot.quote
                if (regeneration != null) saveComposerDraft(session, deliverySlot, ComposerDraft())
                else if (clearComposer) {
                    saveComposerDraft(session, topic, ComposerDraft())
                } else {
                    saveComposerDraft(session, topic, latest.copy(clientId = "", fingerprint = "", followup = false))
                }
                val currentTimeline = timeline.takeIf { timelineTopic == topic }
                currentTimeline?.receive(deliveredMessage)
                mutable.update { state ->
                    val batch = state.conversationFollowups[topic]
                    val withReceipt = if (batch != null && sendingFollowup && deliveredMessage.sequence > 0) {
                        val sequence = deliveredMessage.sequence
                        state.copy(conversationFollowups = state.conversationFollowups + (topic to batch.copy(
                            pendingClientIds = batch.pendingClientIds - clientId,
                            sentMessageIds = batch.sentMessageIds + sequence,
                            lastSentMessageId = sequence,
                        )))
                    } else state
                    val received = currentTimeline?.let { withReceipt.withTimeline(topic, it.messages) } ?: withReceipt
                    val confirmed = received.copy(pendingSend = null)
                    if (clearComposer && confirmed.page == "chat/$topic") confirmed.copy(draft = "", attachments = emptyList(), quote = null)
                    else confirmed
                }
                if (currentTimeline != null) {
                    historyJob?.cancel(); historyJob = null; loadMessages(quiet = true)
                }
                refresh()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (epoch == generation) {
                    if ((error as? ApiFailure)?.status == 402 && !assigningProject) {
                        val now = System.currentTimeMillis()
                        val destination = if (topic.startsWith("grp_") || topic.startsWith("p2p_")) "chat/$topic" else state.page
                        addNotification(WorkspaceNotification(
                            topic, WorkspaceNotificationKind.Quota, "模型额度不足", "未能发送消息。请查看模型额度和套餐权益。",
                            notificationTime(now), "", key = "quota:${UUID.randomUUID()}", finishedAt = now,
                        ), destination)
                    }
                    if (newTask && topic != originalTopic) finishCreatedTask(session, originalTopic, topic, snapshot)
                    if (newTask && topic == originalTopic) {
                        val uncertain = error !is ApiFailure || error.status >= 500
                        val latest = composerDraft(session, topic)
                        saveComposerDraft(session, topic, latest.copy(pendingTaskAgent = if (uncertain) state.employeeId else "",
                            taskCreationId = if (uncertain) snapshot.taskCreationId else ""))
                        mutable.update { it.copy(taskCreationUncertain = uncertain) }
                    }
                    report(error, when {
                        regeneration != null -> "重新生成结果未确认，请刷新对话后重试此操作。"
                        assigningProject -> "任务已创建，归入项目未确认。请在历史任务中检查项目归属，草稿仍在任务内。"
                        newTask && topic == originalTopic -> "创建结果未确认，草稿已保留。请先检查历史任务。"
                        else -> "发送结果未确认，草稿已保留。刷新对话后可重试，相同内容会沿用发送编号。"
                    })
                }
            } finally { if (epoch == generation) mutable.update { it.copy(sending = false, pendingSend = null) } }
        }
    }

    private fun finishCreatedTask(session: Session, draftTopic: String, topic: String, sent: ComposerDraft,
        confirmedMessage: ChatMessage? = null) {
        persistComposer()
        val latest = composerDraft(session, draftTopic)
        if (latest.text == sent.text && latest.attachments == sent.attachments && latest.quote == sent.quote && latest.taskTitle == sent.taskTitle) {
            saveComposerDraft(session, draftTopic, ComposerDraft())
        } else {
            saveComposerDraft(session, draftTopic, latest.copy(pendingTaskAgent = "", taskCreationId = ""))
        }
        if (mutable.value.page == "home") {
            mutable.update { it.copy(newTaskProjectId = "") }
            openPage("chat/$topic", confirmedMessage = confirmedMessage)
        }
    }

    fun acknowledgeTaskCreation() {
        val session = mutable.value.session ?: return
        val draftTopic = currentDraftTopic()
        val saved = composerDraft(session, draftTopic)
        saveComposerDraft(session, draftTopic, saved.copy(pendingTaskAgent = ""))
        mutable.update { it.copy(taskCreationUncertain = false, error = null) }
    }

    private suspend fun uploadUri(
        session: Session, uri: Uri, maxMiB: Int, imagesOnly: Boolean = false, type: String? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        require(!imagesOnly || mime in setOf("image/png", "image/jpeg", "image/gif", "image/webp")) {
            "请选择 PNG、JPG、GIF 或 WebP 图片。"
        }
        var name = "attachment"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) { name = cursor.getString(0) ?: name; if (!cursor.isNull(1)) size = cursor.getLong(1) }
        }
        val limit = maxMiB * 1024L * 1024
        require(size <= limit) { "请选择 ${maxMiB} MB 以内的文件。" }
        val staging = java.io.File(getApplication<Application>().cacheDir, "upload-staging").apply { mkdirs() }
        staging.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
        val temp = java.io.File.createTempFile("upload-", ".part", staging)
        try {
            resolver.openInputStream(uri)?.use { input -> temp.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= limit) { "文件超过 ${maxMiB} MB。" }
                    output.write(buffer, 0, count)
                }
            } } ?: error("无法读取所选文件。")
            JSONObject(if (type == null) repository.upload(session, temp, name, mime)
                else repository.upload(session, temp, name, mime, type))
        } finally {
            temp.delete()
        }
    }

    suspend fun uploadFeedbackImage(session: Session, uri: Uri): JSONObject {
        check(repository === live && !mutable.value.preview) { "请使用真实账号上传反馈图片。" }
        return uploadUri(session, uri, 8, imagesOnly = true, type = "feedback")
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
                val uploaded = attachment(uploadUri(session, uri, 100))
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
        val conversation = state.workspace.conversations.find { it.id == topic }
        val run = conversation?.runStatus?.takeIf { it.runId.isNotBlank() }
        val agent = run?.sourceId?.takeIf { it.toLongOrNull() != null } ?: conversation?.agentId.orEmpty()
        val expectedRunId = run?.runId.orEmpty()
        val epoch = generation
        mutable.update { it.copy(stoppingTopic = topic) }
        action { session ->
            try {
                if (!channel.stop(topic, agent, expectedRunId)) {
                    val controlId = UUID.randomUUID().toString()
                    val receipt = try {
                        request(session, "/api/messages/stop", method = "POST", body = JSONObject()
                            .put("topic_id", topic).put("control_id", controlId)
                            .put("target_bot_uid", agent.toLongOrNull())
                            .put("expected_run_id", expectedRunId.ifBlank { null }))
                    } catch (error: ApiFailure) {
                        if (error.status !in setOf(404, 405, 501)) throw error
                        if (expectedRunId.isNotEmpty()) throw IllegalStateException("当前服务器暂不支持按运行编号停止任务，请刷新查看最新状态。")
                        throw IllegalStateException("当前服务器需要实时连接才能停止任务，请恢复连接后重试。")
                    }
                    check(receipt.optString("control_id") == controlId && receipt.optString("topic_id") == topic &&
                        (expectedRunId.isEmpty() || receipt.optString("expected_run_id") == expectedRunId)) {
                        "停止请求尚未确认，请检查任务状态。"
                    }
                }
                if (epoch == generation) {
                    refreshConversations()
                    if (mutable.value.page == "chat/$topic") loadMessages(quiet = true)
                    viewModelScope.launch {
                        delay(12_000)
                        if (epoch == generation && mutable.value.stoppingTopic == topic) {
                            mutable.update { it.copy(stoppingTopic = "") }
                            notice("尚未收到任务停止确认，可查看任务状态后重试。")
                        }
                    }
                }
            } catch (error: Exception) {
                if (epoch == generation) mutable.update { it.copy(stoppingTopic = "") }
                if (epoch == generation && error is ApiFailure && error.status == 409) {
                    refreshConversations()
                    if (mutable.value.page == "chat/$topic") loadMessages(quiet = true)
                    throw IllegalStateException("任务运行状态已变更，正在同步最新进度。")
                }
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
        if (error is ApiFailure && error.status == 401) { logout(error.message, keepAccountData = true); return }
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

    fun saveProfile(name: String, photo: Uri?, onSuccess: () -> Unit = {}) = action { session ->
        require(name.isNotBlank()) { "请填写显示名称。" }
        val avatar = if (photo == null) {
            session.avatarUrl
        } else {
            check(!mutable.value.preview) { "请使用真实账号更换头像。" }
            val uploaded = uploadUri(session, photo, 100, imagesOnly = true).optString("url")
            check(mediaUrl(session.server, uploaded) != null) { "头像上传后未返回可用的图片地址。" }
            uploaded
        }
        val result = request(session, "/api/me/update", method = "POST",
            body = JSONObject().put("display_name", name.trim()).put("avatar_url", avatar))
        val updated = session.copy(name = name.trim(), avatarUrl = avatar).withProfile(result)
        vault.save(updated)
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
        outcomeSyncJob?.cancel()
        lastOutcomeAttempt = -1
        refreshJob?.cancel()
        draftSaveJob?.cancel()
        val cleared = CompletableDeferred<Unit>()
        accountCacheWrites.send(AccountCacheWrite(session, { clear(session) }, cleared))
        cleared.await()
        attachmentFiles.clear(session)
        val since = System.currentTimeMillis()
        notificationInbox.value = WorkspaceNotificationInbox(since = since, cursor = OutcomeCursor(since * 1000, 0))
        persistNotifications()
        observedRunStarts.clear()
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
    internal fun releaseTaskApp(session: Session, opened: TaskAppLaunch) {
        viewModelScope.launch {
            try { request(session, "/api/artifacts/open-bindings/" + encodeSegment(opened.reference), method = "DELETE") }
            catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { /* Offline references expire on the server. */ }
        }
    }
    fun fileOpened() { mutable.update { it.copy(localFile = null) } }

    fun openAttachment(file: Attachment) = obtainAttachment(file, export = false)
    fun downloadAttachment(file: Attachment) = obtainAttachment(file, export = true)

    private fun obtainAttachment(file: Attachment, export: Boolean) {
        val initial = mutable.value
        if (initial.exportFile != null) return
        action(onFailure = { message ->
            if (mutable.value.page == initial.page) mutable.update { it.copy(attachmentFailure = file.url to message) }
        }) { session ->
            mutable.update { it.copy(attachmentFailure = null) }
            val opened = cachedAttachment(session, file)
            if (mutable.value.page == initial.page) {
                mutable.update { if (export) it.copy(exportFile = opened) else it.copy(localFile = opened) }
            }
        }
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

    private suspend fun cachedAttachment(session: Session, file: Attachment): OpenLocalFile {
        val (target, mime) = attachmentFiles.read(session, file)
        val uri = androidx.core.content.FileProvider.getUriForFile(getApplication(), "${BuildConfig.APPLICATION_ID}.files", target)
        return OpenLocalFile(uri, mime, file.name)
    }

    fun loadResource(query: String? = null) {
        val session = mutable.value.session ?: return
        val page = mutable.value.page
        val epoch = generation
        val search = query ?: mutable.value.resource.query
        screenJob?.cancel()
        mutable.update { it.copy(resource = it.resource.copy(loading = true, loadingMore = false, moreError = null, error = null, errorStatus = null, query = search)) }
        screenJob = viewModelScope.launch {
            try {
                val result = loadModule(session, page, search)
                ensureActive()
                if (generation == epoch && mutable.value.page == page) {
                    val loaded = ResourceState(value = result, loaded = true, query = search)
                    val failedOutput = page.startsWith("task-outputs/") &&
                        (result.optJSONArray("failed_agents")?.length() ?: 0) > 0
                    if (cachesModuleResource(page) && !failedOutput) {
                        libraryResources[page] = loaded
                    }
                    mutable.update { it.copy(resource = loaded) }
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

    internal suspend fun loadComputerDetails(session: Session, audit: Boolean): JSONObject =
        loadModule(session, if (audit) "device-audit" else "cloud-workers", "")

    fun loadMoreRecords() {
        val state = mutable.value
        val session = state.session ?: return
        val page = state.page
        val current = state.resource
        if (current.loading || current.loadingMore || !current.value.optBoolean("has_more")) return
        val key = if (page == "orders") "orders" else if (page.startsWith("topic-files/") || page.startsWith("task-outputs/files/")) "files" else return
        val id = current.value.optLong("next_before_id")
        val timestamp = current.value.first("next_before_created_at")
        if (id <= 0 || timestamp.isBlank()) return
        val epoch = generation
        mutable.update { it.copy(resource = current.copy(loadingMore = true, moreError = null)) }
        screenJob = viewModelScope.launch {
            try {
                val next = loadModule(session, page, current.query, mapOf("before_id" to id.toString(), "before_created_at" to timestamp))
                check(!next.optBoolean("has_more") || next.optLong("next_before_id") != id || next.first("next_before_created_at") != timestamp) {
                    "无法继续读取，请刷新后重试。"
                }
                withContext(Dispatchers.Default) {
                    val rows = (current.value.objects(key) + next.objects(key)).distinctBy {
                        if (key == "orders") it.first("order_no", "id")
                        else it.first("id").ifBlank { "${it.first("message_id")}:${it.optInt("block_index")}:${it.first("url")}" }
                    }
                    next.put(key, JSONArray(rows))
                }
                ensureActive()
                if (generation == epoch && mutable.value.page == page) {
                    val loaded = current.copy(value = next, loadingMore = false, moreError = null)
                    if (cachesModuleResource(page)) libraryResources[page] = loaded
                    mutable.update { it.copy(resource = loaded) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == epoch && mutable.value.page == page) {
                    if (error is ApiFailure && error.status == 401) report(error)
                    else mutable.update { it.copy(resource = current.copy(loadingMore = false, moreError = "读取下一页失败")) }
                }
            }
        }
    }

    private suspend fun loadModule(session: Session, page: String, search: String, cursor: Map<String, String> = emptyMap()): JSONObject = when {
        page.startsWith("task-outputs/") -> {
            val topic = taskOutputTopic(page)
            val id = topic?.let(::agentFor) ?: page.substringAfterLast('/')
            when {
                page.startsWith("task-outputs/apps/") && id.isNotBlank() -> {
                    val agent = mutable.value.workspace.employees.find { it.id == id } ?: Employee(id, "Agent")
                    loadSharedApplications(listOf(agent)) { request(session, "/api/artifacts/apps", mapOf("agent" to it)) }
                }
                page.startsWith("task-outputs/apps/") -> JSONObject()
                topic != null -> request(session, "/api/topics/${encodeSegment(topic)}/files", cursor + ("limit" to "40"))
                id.isNotBlank() -> request(session, "/api/agents/${encodeSegment(id)}/artifacts", mapOf("status" to "active"))
                else -> JSONObject()
            }
        }
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
            if (updated != session) vault.save(updated)
            mutable.update { it.copy(session = updated) }
        }
        page == "usage" -> request(session, "/api/relay/usage")
        page == "commercial" -> request(session, "/api/relay/commercial").also { commercial ->
            try { commercial.put("model_usage", request(session, "/api/relay/usage")) }
            catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { commercial.put("model_usage_error", "模型用量暂时无法读取。") }
        }
        page == "orders" -> request(session, "/api/relay/commercial/orders", cursor + ("limit" to "20"))
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
        page.startsWith("topic-files/") -> request(session, "/api/topics/${encodeSegment(page.removePrefix("topic-files/"))}/files", cursor + ("limit" to "40"))
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
