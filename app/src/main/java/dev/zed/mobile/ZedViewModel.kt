package dev.zed.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zeddesktop.mobile.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Page { PAIR, PROJECTS, THREADS, CHAT, CHANGES, SETTINGS }

enum class TurnPhase {
    IDLE,
    SENDING,
    RUNNING,
    AWAITING_APPROVAL,
    CANCELLING,
    COMPLETED,
    FAILED;

    val isActive: Boolean
        get() = when (this) {
            SENDING, RUNNING, AWAITING_APPROVAL, CANCELLING -> true
            IDLE, COMPLETED, FAILED -> false
        }

    val canCancel: Boolean
        get() = this == RUNNING || this == AWAITING_APPROVAL
}

typealias ChatTurnPhase = TurnPhase

enum class PendingMessageStatus { SENDING, SENT, FAILED }

data class PendingMessage(
    val text: String,
    val status: PendingMessageStatus = PendingMessageStatus.SENDING,
    val error: String? = null,
    val localId: Long = 0,
    /** Avoids acknowledging a newly sent duplicate from an older matching transcript entry. */
    val observedUserMessageCount: Int = 0,
)

data class ChatTurnState(
    val phase: TurnPhase = TurnPhase.IDLE,
    val error: String? = null,
) {
    val isActive: Boolean get() = phase.isActive
    val canCancel: Boolean get() = phase.canCancel
}

data class ComposerState(
    val draft: String = "",
    val pendingMessage: PendingMessage? = null,
)

data class ApprovalState(
    val approvals: List<ToolApproval> = emptyList(),
    val resolvingToolCallIds: Set<String> = emptySet(),
    val errorsByToolCallId: Map<String, String> = emptyMap(),
)

/** Questions the Agent is waiting on in the open thread. */
data class QuestionState(
    val elicitations: List<Elicitation> = emptyList(),
    val answeringIds: Set<String> = emptySet(),
    val errorsById: Map<String, String> = emptyMap(),
)

/** Keep/Reject review of the Agent's unreviewed edits, plus any diff pages loaded on demand. */
data class ReviewState(
    val unreviewedFiles: List<String> = emptyList(),
    val canUndoReject: Boolean = false,
    val reviewing: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    /** Paged diff text for files whose diff was too large to embed in the changes list. */
    val diffPages: Map<String, DiffPage> = emptyMap(),
    val loadingDiffPaths: Set<String> = emptySet(),
)

/** Older transcript pages the user scrolled back to; polling only refreshes the newest page. */
data class TranscriptHistoryState(
    val earlierEntries: List<TranscriptEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

data class ConfigurationState(
    val models: List<ModelCapability> = emptyList(),
    val configuration: ThreadConfiguration? = null,
    val isLoadingModels: Boolean = false,
    val isSavingConfiguration: Boolean = false,
    val error: String? = null,
) {
    val isLoading: Boolean get() = isLoadingModels
    val isSaving: Boolean get() = isSavingConfiguration
}

/** Thread-scoped only: tool output is held in memory and is never persisted. */
data class ToolDetailsKey(val threadId: String, val toolCallId: String)

data class ToolDetailsState(
    val cache: Map<ToolDetailsKey, ToolDetails> = emptyMap(),
    val loading: Set<ToolDetailsKey> = emptySet(),
    val errors: Map<ToolDetailsKey, String> = emptyMap(),
)

data class MobileState(
    val page: Page = Page.PAIR,
    val host: String = "",
    /** True only for a v2 QR pairing that the background event service may use. */
    val hasPersistentPairing: Boolean = false,
    /** A cached catalog may remain visible when the paired desktop cannot currently be reached. */
    val gatewayUnavailable: Boolean = false,
    val projects: List<Project> = emptyList(),
    val project: Project? = null,
    val threads: List<ThreadSummary> = emptyList(),
    /** Agents configured on desktop for [project]; empty for desktops without external agents. */
    val agents: List<AgentInfo> = emptyList(),
    val agentPickerVisible: Boolean = false,
    val selectedThread: ThreadSummary? = null,
    val snapshot: ThreadSnapshot? = null,
    val files: List<ChangedFile> = emptyList(),
    val turn: ChatTurnState = ChatTurnState(),
    val composer: ComposerState = ComposerState(),
    val approval: ApprovalState = ApprovalState(),
    val questions: QuestionState = QuestionState(),
    val review: ReviewState = ReviewState(),
    val history: TranscriptHistoryState = TranscriptHistoryState(),
    val configurationState: ConfigurationState = ConfigurationState(),
    val toolDetails: ToolDetailsState = ToolDetailsState(),
    val busy: Boolean = false,
    val error: String? = null,
    val pollError: String? = null,
    val notice: String? = null,
    val lastUpdated: Long? = null,
) {
    // Existing composables read these values directly; chat mutations use the scoped state above.
    val draft: String get() = composer.draft
    val pendingMessage: PendingMessage? get() = composer.pendingMessage
    val turnPhase: TurnPhase get() = turn.phase
    val approvals: List<ToolApproval> get() = approval.approvals

    /** Older pages joined to the polled newest page, without duplicating overlapping entries. */
    val fullTranscript: List<TranscriptEntry>
        get() {
            val latest = snapshot?.transcript.orEmpty()
            val firstLatestIndex = latest.firstOrNull()?.index ?: Int.MAX_VALUE
            return history.earlierEntries.filter { it.index < firstLatestIndex } + latest
        }

    /** Number of entries still older than anything loaded. */
    val olderTranscriptEntries: Int
        get() = history.earlierEntries.firstOrNull()?.index ?: snapshot?.transcriptOmittedEntries ?: 0
    val resolvingToolCallIds: Set<String> get() = approval.resolvingToolCallIds
    val models: List<ModelCapability> get() = configurationState.models
    val configuration: ThreadConfiguration? get() = configurationState.configuration
    val isLoadingModels: Boolean get() = configurationState.isLoadingModels
    val isSavingConfiguration: Boolean get() = configurationState.isSavingConfiguration
}

/**
 * v1 host/token sessions remain ephemeral. v2 QR claims use an injectable [CredentialStore] so
 * production can restore a per-device credential while JVM tests need no Android dependencies.
 */
class ZedViewModel(
    private val connect: (String, String) -> ZedRepository = { host, token ->
        HttpZedRepository(host, token, BuildConfig.DEBUG)
    },
    private val connectV2: (DeviceCredential) -> ZedRepository = { credential ->
        HttpZedRepository.v2(credential, BuildConfig.DEBUG)
    },
    private val pairingRepository: (String) -> PairingRepository = { origin ->
        V2PairingRepository(origin, BuildConfig.DEBUG)
    },
    private val credentialStore: CredentialStore = ApplicationCredentialStore(),
    private val projectCatalogStore: ProjectCatalogStore = ApplicationProjectCatalogStore(),
) : ViewModel() {
    private val mutable = MutableStateFlow(MobileState())
    val state = mutable.asStateFlow()
    private var repository: ZedRepository? = null
    private var activeV2Credential: DeviceCredential? = null
    private var epoch = 0L
    private var chatEpoch = 0L
    private var turnRevision = 0L
    private var configurationRevision = 0L
    private var nextPendingMessageId = 0L
    private var action: Job? = null
    private var sendJob: Job? = null
    private var cancelJob: Job? = null
    private var modelsJob: Job? = null
    private var configurationJob: Job? = null
    private val approvalJobs = mutableMapOf<String, Job>()
    private val questionJobs = mutableMapOf<String, Job>()
    private var reviewJob: Job? = null
    private var historyJob: Job? = null
    private val diffPageJobs = mutableMapOf<String, Job>()
    private val toolDetailsJobs = mutableMapOf<ToolDetailsKey, Job>()

    /** Existing manual host/token flow. This is retained as the v1 fallback. */
    fun pair(host: String, token: String) = task {
        val candidate = connect(host, token)
        candidate.checkStatus()
        val projects = candidate.projects()
        beginChatSession()
        activeV2Credential = null
        repository = candidate
        mutable.value = MobileState(
            page = Page.PROJECTS,
            host = host.trim(),
            projects = projects,
            busy = true,
        )
    }

    /** Claims a ZEDM2 QR ticket, verifies v2 access, then persists the per-device credential. */
    fun pairQr(qrValue: String, deviceLabel: String = DEFAULT_DEVICE_LABEL) = task {
        val ticket = QrPairingContract.parse(qrValue, BuildConfig.DEBUG)
        val credential = pairingRepository(ticket.origin).claim(ticket, deviceLabel)
        val candidate = connectV2(credential)
        candidate.checkStatus()
        // A newly claimed device must never inherit a catalog from another credential binding.
        val cachedProjects = loadCachedProjects(credential)
        credentialStore.save(credential)
        beginChatSession()
        activeV2Credential = credential
        repository = candidate
        mutable.value = MobileState(
            page = Page.PROJECTS,
            host = credential.origin.trim(),
            hasPersistentPairing = true,
            projects = cachedProjects,
            busy = true,
        )
        syncV2Projects(credential, candidate)
    }

    /** Restores a prior v2 QR pairing, showing its bound catalog before refreshing from desktop. */
    fun restorePersistentPairing() = task {
        val credential = try {
            credentialStore.load()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            clearProjectCatalog()
            throw error
        } ?: run {
            clearProjectCatalog()
            return@task
        }
        activeV2Credential = credential
        val cachedProjects = loadCachedProjects(credential)
        beginChatSession()
        mutable.value = MobileState(
            page = Page.PROJECTS,
            host = credential.origin.trim(),
            hasPersistentPairing = true,
            projects = cachedProjects,
            busy = true,
        )
        val candidate = connectV2(credential)
        try {
            candidate.checkStatus()
        } catch (error: CancellationException) {
            throw error
        } catch (_: GatewayAuthenticationException) {
            handleRevokedCredential()
            return@task
        } catch (_: Exception) {
            // Retain the validated repository instance so an explicit refresh can retry later.
            repository = candidate
            mutable.value = mutable.value.copy(gatewayUnavailable = true)
            return@task
        }
        repository = candidate
        syncV2Projects(credential, candidate)
    }

    /** Removes this phone's local v2 credential and its credential-bound UI-only project catalog. */
    fun disconnect() {
        epoch++
        beginChatSession()
        action?.cancel()
        repository = null
        activeV2Credential = null
        val version = epoch
        mutable.value = MobileState(busy = true)
        action = viewModelScope.launch {
            try {
                clearPersistentPairingData()
                if (version == epoch) mutable.value = MobileState(notice = "Pairing removed from this phone.")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (version == epoch) mutable.value = MobileState(error = message(error))
            } finally {
                if (version == epoch) mutable.value = mutable.value.copy(busy = false)
            }
        }
    }

    fun openProject(project: Project) {
        val current = mutable.value
        if (current.busy) return
        if (current.gatewayUnavailable) {
            mutable.value = current.copy(
                error = "Desktop gateway is unavailable. Reconnect before opening a cached project.",
            )
            return
        }
        beginChatSession()
        task {
            val repo = requireNotNull(repository)
            // The catalog ID is opaque and desktop-owned. Never send a phone path or retry the POST.
            val opened = if (project.isOpen) project else openAndAwaitProject(repo, project)
            // Keep the catalog visible until both the desktop open and thread request have succeeded.
            val threads = repo.threads(opened.id)
            val agents = loadAgents(repo, opened.id)
            mutable.value = mutable.value.copy(
                page = Page.THREADS,
                project = opened,
                threads = threads,
                agents = agents,
                selectedThread = null,
                snapshot = null,
                turn = ChatTurnState(),
                composer = ComposerState(),
                approval = ApprovalState(),
                configurationState = ConfigurationState(),
                notice = if (project.isOpen) null else "Opened on Desktop. Your Agent threads are ready.",
            )
        }
    }

    /** Older desktops have no agent list; they can still create built-in Zed Agent threads. */
    private suspend fun loadAgents(repo: ZedRepository, projectId: String): List<AgentInfo> = try {
        repo.agents(projectId)
    } catch (error: GatewayAuthenticationException) {
        throw error
    } catch (error: GatewayException) {
        emptyList()
    }

    /** Performs one catalog-open POST, then only polls authenticated catalog reads for that ID. */
    private suspend fun openAndAwaitProject(repo: ZedRepository, project: Project): Project {
        val response = repo.openProject(project.id)
        if (response.id != project.id) {
            throw GatewayException("Desktop returned a different project while opening the catalog entry.")
        }
        if (response.isOpen) return response
        if (!response.opening) {
            throw GatewayException("Desktop did not begin opening this project.")
        }

        mutable.value = mutable.value.copy(
            projects = mutable.value.projects.map { listed -> if (listed.id == project.id) response else listed },
            notice = "Connecting on Desktop. Complete any SSH, host-key, or MFA prompt there if requested.",
        )

        repeat(OPEN_PROJECT_POLL_ATTEMPTS) {
            delay(OPEN_PROJECT_POLL_INTERVAL_MILLIS)
            val projects = repo.projects()
            activeV2Credential?.let { saveProjectCatalog(it, projects) }
            val updated = projects.firstOrNull { it.id == project.id }
                ?: throw GatewayException("This project is no longer available in the desktop catalog.")
            mutable.value = mutable.value.copy(projects = projects, gatewayUnavailable = false)
            if (updated.isOpen) return updated
            if (!updated.opening) {
                throw GatewayException("Desktop stopped opening this project before it was ready.")
            }
        }

        throw GatewayException(
            "Desktop is still connecting to this project. Complete any SSH, host-key, or MFA prompt in Zed Desktop, then try again.",
        )
    }

    fun openThread(thread: ThreadSummary) {
        if (mutable.value.busy) return
        beginChatSession()
        navigate(Page.CHAT)
        mutable.value = mutable.value.copy(
            selectedThread = thread,
            snapshot = null,
            turn = ChatTurnState(),
            composer = ComposerState(),
            approval = ApprovalState(),
            configurationState = ConfigurationState(),
            lastUpdated = null,
        )
        // The visible chat owns the first fetch and subsequent lifecycle-aware polling.
    }

    /** Asks which agent to use only when desktop has external agents configured. */
    fun requestNewThread() {
        val current = mutable.value
        if (current.busy || current.project == null) return
        if (current.agents.any { !it.isNative }) {
            mutable.value = current.copy(agentPickerVisible = true)
        } else {
            createThread()
        }
    }

    fun dismissAgentPicker() {
        mutable.value = mutable.value.copy(agentPickerVisible = false)
    }

    /** Null [agentId] means the built-in Zed Agent. */
    fun createThread(agentId: String? = null) {
        val project = mutable.value.project ?: return
        mutable.value = mutable.value.copy(agentPickerVisible = false)
        task {
            val thread = requireNotNull(repository).createThread(project.id, agentId)
            beginChatSession()
            mutable.value = mutable.value.copy(
                page = Page.CHAT,
                selectedThread = thread,
                snapshot = null,
                turn = ChatTurnState(),
                composer = ComposerState(),
                approval = ApprovalState(),
                configurationState = ConfigurationState(),
                lastUpdated = null,
                notice = "Thread created. Send your first prompt.",
            )
        }
    }

    fun editDraft(text: String) {
        val current = mutable.value
        mutable.value = current.copy(composer = current.composer.copy(draft = text))
    }

    fun send() {
        val current = mutable.value
        val id = current.selectedThread?.id ?: return
        val text = current.composer.draft
        if (text.isBlank() || current.turn.isActive) return
        val repo = repository ?: return
        val session = chatEpoch
        val pendingId = ++nextPendingMessageId
        val pendingMessage = PendingMessage(
            text = text,
            localId = pendingId,
            observedUserMessageCount = current.snapshot?.messages?.count { it.isUserMessage(text) } ?: 0,
        )

        turnRevision++
        mutable.value = current.copy(
            turn = ChatTurnState(phase = TurnPhase.SENDING),
            composer = current.composer.copy(draft = "", pendingMessage = pendingMessage),
        )
        sendJob = viewModelScope.launch {
            try {
                repo.send(id, text)
                updateCurrentChat(id, session) { latest ->
                    val latestPending = latest.composer.pendingMessage
                    if (latestPending?.localId != pendingId) {
                        latest
                    } else {
                        turnRevision++
                        latest.copy(
                            turn = ChatTurnState(phase = TurnPhase.RUNNING),
                            composer = latest.composer.copy(
                                pendingMessage = latestPending.copy(
                                    status = PendingMessageStatus.SENT,
                                    error = null,
                                ),
                            ),
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: GatewayAuthenticationException) {
                if (isCurrentChat(id, session) && activeV2Credential != null) {
                    handleRevokedCredential()
                } else {
                    markSendFailed(id, session, pendingId, text, message(error))
                }
            } catch (error: Exception) {
                markSendFailed(id, session, pendingId, text, message(error))
            }
        }
    }

    fun cancel() {
        val current = mutable.value
        val id = current.selectedThread?.id ?: return
        if (!current.turn.canCancel) return
        val repo = repository ?: return
        val session = chatEpoch
        val previousPhase = current.turn.phase
        val revision = ++turnRevision

        mutable.value = current.copy(
            turn = current.turn.copy(phase = TurnPhase.CANCELLING, error = null),
        )
        cancelJob = viewModelScope.launch {
            try {
                repo.cancel(id)
                updateCurrentChat(id, session) { latest ->
                    if (turnRevision != revision) {
                        latest
                    } else {
                        turnRevision++
                        latest.copy(turn = latest.turn.copy(phase = TurnPhase.CANCELLING, error = null))
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: GatewayAuthenticationException) {
                if (isCurrentChat(id, session) && activeV2Credential != null) {
                    handleRevokedCredential()
                } else {
                    markCancelFailed(id, session, revision, previousPhase, message(error))
                }
            } catch (error: Exception) {
                markCancelFailed(id, session, revision, previousPhase, message(error))
            }
        }
    }

    /**
     * Fetches full output only after an explicit tool-card interaction. Concurrent requests for
     * the same thread/tool pair share one in-flight call; completed results remain in-memory only.
     */
    fun loadToolDetails(toolCallId: String, retry: Boolean = false) {
        val current = mutable.value
        val threadId = current.selectedThread?.id ?: return
        if (current.page != Page.CHAT || toolCallId.isBlank()) return
        // Tool IDs are desktop-owned opaque values. Confirm the visible transcript supplied this
        // ID before making a scoped detail request so future callers cannot use this as arbitrary
        // API access.
        if (current.snapshot?.transcript?.any { it.toolCallId == toolCallId } != true) return
        val key = ToolDetailsKey(threadId, toolCallId)
        if (key in current.toolDetails.loading || (!retry && key in current.toolDetails.cache)) return
        val repo = repository ?: return
        val version = epoch
        val session = chatEpoch

        mutable.value = current.copy(
            toolDetails = current.toolDetails.copy(
                loading = current.toolDetails.loading + key,
                errors = current.toolDetails.errors - key,
            ),
        )
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val details = repo.toolDetails(threadId, toolCallId)
                if (details.toolCallId != toolCallId) {
                    throw GatewayException("Desktop returned detailed output for a different tool call.")
                }
                updateCurrentToolDetails(threadId, version, session) { latest ->
                    // A new snapshot can replace a completed/compacted transcript while this
                    // request is in flight. Never retain output for a tool that is no longer shown.
                    if (latest.snapshot?.transcript?.any { it.toolCallId == toolCallId } != true) {
                        latest.copy(
                            toolDetails = latest.toolDetails.copy(
                                loading = latest.toolDetails.loading - key,
                                errors = latest.toolDetails.errors - key,
                            ),
                        )
                    } else {
                        latest.copy(
                            toolDetails = latest.toolDetails.copy(
                                cache = latest.toolDetails.cache + (key to details),
                                loading = latest.toolDetails.loading - key,
                                errors = latest.toolDetails.errors - key,
                            ),
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: GatewayAuthenticationException) {
                if (isCurrentChatPage(threadId, version, session) && activeV2Credential != null) {
                    handleRevokedCredential()
                } else {
                    markToolDetailsFailed(threadId, version, session, key, message(error))
                }
            } catch (error: Exception) {
                markToolDetailsFailed(threadId, version, session, key, message(error))
            } finally {
                if (toolDetailsJobs[key] == currentCoroutineContext()[Job]) {
                    toolDetailsJobs.remove(key)
                }
            }
        }
        toolDetailsJobs[key] = job
        job.start()
    }

    fun showChanges() {
        if (mutable.value.busy) return
        navigate(Page.CHANGES)
        mutable.value = mutable.value.copy(files = emptyList(), review = ReviewState())
        refresh()
    }

    fun showProjects() {
        if (mutable.value.busy) return
        if (mutable.value.page == Page.CHAT) beginChatSession()
        navigate(Page.PROJECTS)
        refresh()
    }

    fun showThreads() {
        if (mutable.value.busy || mutable.value.project == null) return
        if (mutable.value.page == Page.CHAT) beginChatSession()
        navigate(Page.THREADS)
        refresh()
    }

    fun showChat() {
        if (mutable.value.busy || mutable.value.selectedThread == null) return
        navigate(Page.CHAT)
    }

    fun showSettings() {
        if (!mutable.value.busy) navigate(Page.SETTINGS)
    }

    fun back() {
        if (mutable.value.busy) return
        when (mutable.value.page) {
            Page.CHANGES -> navigate(Page.CHAT)
            Page.CHAT -> {
                beginChatSession()
                navigate(Page.THREADS); mutable.value =
                    mutable.value.copy(
                        snapshot = null,
                        turn = ChatTurnState(),
                        composer = ComposerState(),
                        approval = ApprovalState(),
                        questions = QuestionState(),
                        history = TranscriptHistoryState(),
                        configurationState = ConfigurationState(),
                        selectedThread = null,
                    ); refresh()
            }

            Page.THREADS -> {
                navigate(Page.PROJECTS); refresh()
            }

            Page.SETTINGS -> navigate(Page.PROJECTS)

            else -> Unit
        }
    }

    fun refresh() {
        val current = mutable.value
        val repo = repository ?: return
        task {
            try {
                when (current.page) {
                    Page.PROJECTS -> {
                        val projects = repo.projects()
                        activeV2Credential?.let { saveProjectCatalog(it, projects) }
                        mutable.value = mutable.value.copy(projects = projects, gatewayUnavailable = false)
                    }

                    Page.THREADS -> {
                        val projectId = requireNotNull(current.project).id
                        val threads = repo.threads(projectId)
                        val agents = loadAgents(repo, projectId)
                        mutable.value = mutable.value.copy(threads = threads, agents = agents)
                    }

                    Page.CHANGES -> {
                        val changes = repo.changesSnapshot(requireNotNull(current.selectedThread).id)
                        mutable.value = mutable.value.copy(
                            files = changes.files,
                            review = mutable.value.review.copy(
                                unreviewedFiles = changes.unreviewedFiles,
                                canUndoReject = changes.canUndoReject,
                                // Diff text may have changed, so drop pages loaded for older text.
                                diffPages = emptyMap(),
                            ),
                        )
                    }

                    else -> Unit
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: GatewayAuthenticationException) {
                if (activeV2Credential != null) {
                    handleRevokedCredential()
                } else {
                    throw error
                }
            } catch (error: Exception) {
                if (current.page == Page.PROJECTS && activeV2Credential != null) {
                    // Keep the last authenticated catalog on screen, but never treat it as live.
                    mutable.value = mutable.value.copy(gatewayUnavailable = true)
                } else {
                    throw error
                }
            }
        }
    }

    /** Called sequentially by repeatOnLifecycle; cancellation propagates to the underlying OkHttp call. */
    suspend fun pollThread() {
        val current = mutable.value
        if (current.page != Page.CHAT) return
        val id = current.selectedThread?.id ?: return
        val repo = repository ?: return
        val version = epoch
        val session = chatEpoch
        val observedTurnRevision = turnRevision
        val observedConfigurationRevision = configurationRevision
        try {
            val snapshot = repo.thread(id)
            // A thread whose agent has not started (still initializing, or waiting for a desktop
            // sign-in) has no approvals, questions, or configuration, and desktop refuses those reads.
            val threadReady = snapshot.status != THREAD_STATUS_NEEDS_AUTHENTICATION && snapshot.status != "initializing"
            val approvals = if (!threadReady) emptyList() else try {
                repo.approvals(id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: GatewayException) {
                if (approvalsUnavailable(error)) emptyList() else throw error
            }
            val elicitations = if (!threadReady) emptyList() else try {
                repo.elicitations(id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: GatewayAuthenticationException) {
                throw error
            } catch (_: GatewayException) {
                // Desktops from before phone questions existed simply have none to answer here.
                emptyList()
            }
            val configuration = if (!threadReady) null else try {
                repo.configuration(id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: GatewayAuthenticationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (isCurrentChatPage(id, version, session)) {
                val latest = mutable.value
                val pendingMessage = latest.composer.pendingMessage
                val pendingWasObserved = pendingMessage != null &&
                        snapshot.messages.count { it.isUserMessage(pendingMessage.text) } >
                        pendingMessage.observedUserMessageCount
                val nextConfiguration = when {
                    latest.configurationState.isSavingConfiguration -> latest.configurationState
                    configurationRevision != observedConfigurationRevision -> latest.configurationState
                    configuration == null -> latest.configurationState
                    else -> latest.configurationState.copy(configuration = configuration, error = null)
                }
                val approvalIds = approvals.mapTo(mutableSetOf()) { it.toolCallId }
                val elicitationIds = elicitations.mapTo(mutableSetOf()) { it.id }
                val derivedTurnPhase = deriveTurnPhase(snapshot.status, approvals, elicitations)
                val nextTurn = if (
                    turnRevision == observedTurnRevision &&
                    !shouldPreserveLocalTurn(
                        current = latest.turn,
                        pendingMessage = pendingMessage,
                        pendingWasObserved = pendingWasObserved,
                        derived = derivedTurnPhase,
                    )
                ) {
                    ChatTurnState(phase = derivedTurnPhase)
                } else {
                    latest.turn
                }
                mutable.value = latest.copy(
                    snapshot = snapshot,
                    turn = nextTurn,
                    composer = if (pendingWasObserved) {
                        latest.composer.copy(pendingMessage = null)
                    } else {
                        latest.composer
                    },
                    approval = latest.approval.copy(
                        approvals = approvals,
                        resolvingToolCallIds = latest.approval.resolvingToolCallIds.intersect(approvalIds),
                        errorsByToolCallId = latest.approval.errorsByToolCallId.filterKeys { it in approvalIds },
                    ),
                    questions = latest.questions.copy(
                        elicitations = elicitations,
                        answeringIds = latest.questions.answeringIds.intersect(elicitationIds),
                        errorsById = latest.questions.errorsById.filterKeys { it in elicitationIds },
                    ),
                    configurationState = nextConfiguration,
                    pollError = null,
                    lastUpdated = System.currentTimeMillis(),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: GatewayAuthenticationException) {
            if (isCurrentChatPage(id, version, session) && activeV2Credential != null) {
                handleRevokedCredential()
            } else if (isCurrentChatPage(id, version, session)) {
                mutable.value = mutable.value.copy(pollError = message(error))
            }
        } catch (error: Exception) {
            if (isCurrentChatPage(id, version, session)) {
                mutable.value = mutable.value.copy(pollError = message(error))
            }
        }
    }

    fun resolveApproval(toolCallId: String, allow: Boolean) =
        submitApproval(toolCallId) { repo, threadId -> repo.resolveApproval(threadId, toolCallId, allow) }

    /** Sends one of the desktop's own options, such as "Always allow" or a pattern-scoped rule. */
    fun resolveApproval(toolCallId: String, decision: ApprovalDecision) {
        val approval = mutable.value.approval.approvals.firstOrNull { it.toolCallId == toolCallId } ?: return
        val option = approval.options.firstOrNull { it.optionId == decision.optionId } ?: return
        if (!option.enabled) return
        submitApproval(toolCallId) { repo, threadId -> repo.resolveApproval(threadId, toolCallId, decision) }
    }

    private fun submitApproval(
        toolCallId: String,
        send: suspend (ZedRepository, String) -> Unit,
    ) {
        val current = mutable.value
        val threadId = current.selectedThread?.id ?: return
        if (toolCallId in current.approval.resolvingToolCallIds) return
        val repo = repository ?: return
        val session = chatEpoch

        mutable.value = current.copy(
            approval = current.approval.copy(
                resolvingToolCallIds = current.approval.resolvingToolCallIds + toolCallId,
                errorsByToolCallId = current.approval.errorsByToolCallId - toolCallId,
            ),
        )
        approvalJobs[toolCallId] = viewModelScope.launch {
            var decisionAccepted = false
            try {
                send(repo, threadId)
                decisionAccepted = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: GatewayAuthenticationException) {
                if (isCurrentChat(threadId, session) && activeV2Credential != null) {
                    handleRevokedCredential()
                } else {
                    markApprovalFailed(threadId, session, toolCallId, message(error))
                }
            } catch (error: Exception) {
                markApprovalFailed(threadId, session, toolCallId, message(error))
            } finally {
                if (!decisionAccepted) {
                    updateCurrentChat(threadId, session) { latest ->
                        latest.copy(
                            approval = latest.approval.copy(
                                resolvingToolCallIds = latest.approval.resolvingToolCallIds - toolCallId,
                            ),
                        )
                    }
                }
                approvalJobs.remove(toolCallId)
            }
        }
    }

    /** Answers, declines or cancels a question the Agent asked. Desktop validates the answer. */
    fun answerElicitation(
        elicitationId: String,
        action: String,
        values: Map<String, ElicitationValue> = emptyMap(),
    ) {
        val current = mutable.value
        val threadId = current.selectedThread?.id ?: return
        if (elicitationId in current.questions.answeringIds) return
        val repo = repository ?: return
        val session = chatEpoch

        mutable.value = current.copy(
            questions = current.questions.copy(
                answeringIds = current.questions.answeringIds + elicitationId,
                errorsById = current.questions.errorsById - elicitationId,
            ),
        )
        questionJobs[elicitationId] = viewModelScope.launch {
            var answered = false
            try {
                repo.answerElicitation(threadId, elicitationId, action, values)
                answered = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: GatewayAuthenticationException) {
                if (isCurrentChat(threadId, session) && activeV2Credential != null) {
                    handleRevokedCredential()
                } else {
                    markQuestionFailed(threadId, session, elicitationId, message(error))
                }
            } catch (error: Exception) {
                markQuestionFailed(threadId, session, elicitationId, message(error))
            } finally {
                if (!answered) {
                    updateCurrentChat(threadId, session) { latest ->
                        latest.copy(
                            questions = latest.questions.copy(
                                answeringIds = latest.questions.answeringIds - elicitationId,
                            ),
                        )
                    }
                }
                questionJobs.remove(elicitationId)
            }
        }
    }

    /** Keeps or rejects the Agent's unreviewed edits; [paths] null means every unreviewed file. */
    fun reviewChanges(keep: Boolean, paths: List<String>? = null) =
        runReview(if (keep) "Kept the Agent's edits." else "Rejected the Agent's edits. You can undo this.") { repo, id ->
            repo.reviewChanges(id, keep, paths)
        }

    fun undoReject() = runReview("Restored the rejected edits.") { repo, id -> repo.undoReject(id) }

    private fun runReview(successNotice: String, action: suspend (ZedRepository, String) -> Unit) {
        val current = mutable.value
        if (current.page != Page.CHANGES || current.review.reviewing) return
        val threadId = current.selectedThread?.id ?: return
        val repo = repository ?: return
        val version = epoch
        mutable.value = current.copy(review = current.review.copy(reviewing = true, error = null, notice = null))
        reviewJob = viewModelScope.launch {
            try {
                action(repo, threadId)
                val changes = repo.changesSnapshot(threadId)
                if (version == epoch) {
                    mutable.value = mutable.value.copy(
                        files = changes.files,
                        review = mutable.value.review.copy(
                            unreviewedFiles = changes.unreviewedFiles,
                            canUndoReject = changes.canUndoReject,
                            reviewing = false,
                            notice = successNotice,
                            diffPages = emptyMap(),
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: GatewayAuthenticationException) {
                if (activeV2Credential != null) {
                    handleRevokedCredential()
                } else if (version == epoch) {
                    mutable.value = mutable.value.copy(
                        review = mutable.value.review.copy(reviewing = false, error = message(error)),
                    )
                }
            } catch (error: Exception) {
                if (version == epoch) {
                    mutable.value = mutable.value.copy(
                        review = mutable.value.review.copy(reviewing = false, error = message(error)),
                    )
                }
            }
        }
    }

    /** Loads the next page of a diff that was too large to embed in the changes list. */
    fun loadDiffPage(path: String) {
        val current = mutable.value
        if (current.page != Page.CHANGES || path in current.review.loadingDiffPaths) return
        val threadId = current.selectedThread?.id ?: return
        val repo = repository ?: return
        val existing = current.review.diffPages[path]
        val offset = when {
            existing == null -> 0
            existing.nextOffset == null -> return
            else -> existing.nextOffset
        }
        val version = epoch
        mutable.value = current.copy(
            review = current.review.copy(loadingDiffPaths = current.review.loadingDiffPaths + path, error = null),
        )
        diffPageJobs[path] = viewModelScope.launch {
            try {
                val page = repo.changedFilePage(threadId, path, offset)
                if (version == epoch) {
                    val review = mutable.value.review
                    val merged = review.diffPages[path]?.let { previous ->
                        page.copy(offset = previous.offset, diff = previous.diff + page.diff)
                    } ?: page
                    mutable.value = mutable.value.copy(
                        review = review.copy(
                            diffPages = review.diffPages + (path to merged),
                            loadingDiffPaths = review.loadingDiffPaths - path,
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (version == epoch) {
                    val review = mutable.value.review
                    mutable.value = mutable.value.copy(
                        review = review.copy(loadingDiffPaths = review.loadingDiffPaths - path, error = message(error)),
                    )
                }
            } finally {
                diffPageJobs.remove(path)
            }
        }
    }

    /** Fetches the page of transcript entries just before the oldest one shown. */
    fun loadOlderTranscript() {
        val current = mutable.value
        val threadId = current.selectedThread?.id ?: return
        if (current.history.loading || current.olderTranscriptEntries <= 0) return
        val before = current.fullTranscript.firstOrNull()?.index ?: return
        val repo = repository ?: return
        val session = chatEpoch
        mutable.value = current.copy(history = current.history.copy(loading = true, error = null))
        historyJob = viewModelScope.launch {
            try {
                val page = repo.olderTranscript(threadId, before)
                updateCurrentChat(threadId, session) { latest ->
                    latest.copy(
                        history = latest.history.copy(
                            earlierEntries = page.entries + latest.history.earlierEntries,
                            loading = false,
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                updateCurrentChat(threadId, session) { latest ->
                    latest.copy(history = latest.history.copy(loading = false, error = message(error)))
                }
            }
        }
    }

    fun updateConfiguration(
        model: ModelCapability? = null,
        thinkingEnabled: Boolean? = null,
        thinkingEffort: String? = null,
        clearThinkingEffort: Boolean = false,
    ) = saveConfiguration { repo, threadId ->
        repo.updateConfiguration(
            id = threadId,
            model = model,
            thinkingEnabled = thinkingEnabled,
            thinkingEffort = thinkingEffort,
            clearThinkingEffort = clearThinkingEffort,
        )
    }

    fun setAgentMode(modeId: String) = saveConfiguration { repo, threadId ->
        repo.setAgentMode(threadId, modeId)
    }

    fun setAgentOption(optionId: String, value: AgentOptionValue) = saveConfiguration { repo, threadId ->
        repo.setAgentOption(threadId, optionId, value)
    }

    private fun saveConfiguration(save: suspend (ZedRepository, String) -> ThreadConfiguration) {
        val current = mutable.value
        val threadId = current.selectedThread?.id ?: return
        if (current.turn.isActive || current.configurationState.isSavingConfiguration) return
        val repo = repository ?: return
        val session = chatEpoch
        val revision = ++configurationRevision

        mutable.value = current.copy(
            configurationState = current.configurationState.copy(
                isSavingConfiguration = true,
                error = null,
            ),
        )
        configurationJob = viewModelScope.launch {
            try {
                val configuration = save(repo, threadId)
                updateCurrentChat(threadId, session) { latest ->
                    if (configurationRevision != revision) {
                        latest
                    } else {
                        configurationRevision++
                        latest.copy(
                            configurationState = latest.configurationState.copy(
                                configuration = configuration,
                                isSavingConfiguration = false,
                                error = null,
                            ),
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: GatewayAuthenticationException) {
                if (isCurrentChat(threadId, session) && activeV2Credential != null) {
                    handleRevokedCredential()
                } else {
                    markConfigurationFailed(threadId, session, revision, message(error))
                }
            } catch (error: Exception) {
                markConfigurationFailed(threadId, session, revision, message(error))
            }
        }
    }

    fun loadModels() {
        val current = mutable.value
        val projectId = current.project?.id ?: return
        if (current.configurationState.isLoadingModels) return
        val repo = repository ?: return
        val session = chatEpoch

        mutable.value = current.copy(
            configurationState = current.configurationState.copy(isLoadingModels = true, error = null),
        )
        modelsJob = viewModelScope.launch {
            try {
                val models = repo.models(projectId)
                updateCurrentProjectConfiguration(projectId, session) { latest ->
                    latest.copy(
                        configurationState = latest.configurationState.copy(
                            models = models,
                            isLoadingModels = false,
                            error = null,
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: GatewayAuthenticationException) {
                if (isCurrentProjectConfiguration(projectId, session) && activeV2Credential != null) {
                    handleRevokedCredential()
                } else {
                    markModelLoadFailed(projectId, session, message(error))
                }
            } catch (error: Exception) {
                markModelLoadFailed(projectId, session, message(error))
            }
        }
    }

    private fun markSendFailed(
        threadId: String,
        session: Long,
        pendingId: Long,
        text: String,
        error: String,
    ) = updateCurrentChat(threadId, session) { latest ->
        val pendingMessage = latest.composer.pendingMessage
        if (pendingMessage?.localId != pendingId) {
            latest
        } else {
            turnRevision++
            latest.copy(
                turn = ChatTurnState(phase = TurnPhase.FAILED, error = error),
                composer = latest.composer.copy(
                    draft = latest.composer.draft.ifEmpty { text },
                    pendingMessage = pendingMessage.copy(
                        status = PendingMessageStatus.FAILED,
                        error = error,
                    ),
                ),
            )
        }
    }

    private fun markCancelFailed(
        threadId: String,
        session: Long,
        revision: Long,
        previousPhase: TurnPhase,
        error: String,
    ) = updateCurrentChat(threadId, session) { latest ->
        if (turnRevision != revision || latest.turn.phase != TurnPhase.CANCELLING) {
            latest
        } else {
            turnRevision++
            latest.copy(turn = latest.turn.copy(phase = previousPhase, error = error))
        }
    }

    private fun markApprovalFailed(threadId: String, session: Long, toolCallId: String, error: String) =
        updateCurrentChat(threadId, session) { latest ->
            if (toolCallId !in latest.approval.resolvingToolCallIds) {
                latest
            } else {
                latest.copy(
                    approval = latest.approval.copy(
                        errorsByToolCallId = latest.approval.errorsByToolCallId + (toolCallId to error),
                    ),
                )
            }
        }

    private fun markQuestionFailed(threadId: String, session: Long, elicitationId: String, error: String) =
        updateCurrentChat(threadId, session) { latest ->
            if (elicitationId !in latest.questions.answeringIds) {
                latest
            } else {
                latest.copy(
                    questions = latest.questions.copy(
                        errorsById = latest.questions.errorsById + (elicitationId to error),
                    ),
                )
            }
        }

    private fun markConfigurationFailed(threadId: String, session: Long, revision: Long, error: String) =
        updateCurrentChat(threadId, session) { latest ->
            if (configurationRevision != revision) {
                latest
            } else {
                latest.copy(
                    configurationState = latest.configurationState.copy(
                        isSavingConfiguration = false,
                        error = error,
                    ),
                )
            }
        }

    private fun markToolDetailsFailed(
        threadId: String,
        version: Long,
        session: Long,
        key: ToolDetailsKey,
        error: String,
    ) = updateCurrentToolDetails(threadId, version, session) { latest ->
        latest.copy(
            toolDetails = latest.toolDetails.copy(
                loading = latest.toolDetails.loading - key,
                errors = latest.toolDetails.errors + (key to error),
            ),
        )
    }

    private fun markModelLoadFailed(projectId: String, session: Long, error: String) =
        updateCurrentProjectConfiguration(projectId, session) { latest ->
            latest.copy(
                configurationState = latest.configurationState.copy(
                    isLoadingModels = false,
                    error = error,
                ),
            )
        }

    private fun updateCurrentChat(
        threadId: String,
        session: Long,
        transform: (MobileState) -> MobileState,
    ) {
        if (isCurrentChat(threadId, session)) {
            mutable.value = transform(mutable.value)
        }
    }

    private fun isCurrentChat(threadId: String, session: Long): Boolean =
        session == chatEpoch && mutable.value.selectedThread?.id == threadId

    private fun updateCurrentToolDetails(
        threadId: String,
        version: Long,
        session: Long,
        transform: (MobileState) -> MobileState,
    ) {
        if (isCurrentChatPage(threadId, version, session)) {
            mutable.value = transform(mutable.value)
        }
    }

    private fun updateCurrentProjectConfiguration(
        projectId: String,
        session: Long,
        transform: (MobileState) -> MobileState,
    ) {
        if (isCurrentProjectConfiguration(projectId, session)) {
            mutable.value = transform(mutable.value)
        }
    }

    private fun isCurrentProjectConfiguration(projectId: String, session: Long): Boolean =
        session == chatEpoch && mutable.value.project?.id == projectId

    private fun isCurrentChatPage(threadId: String, version: Long, session: Long): Boolean =
        version == epoch && mutable.value.page == Page.CHAT && isCurrentChat(threadId, session)

    private fun beginChatSession() {
        invalidateChatSession()
    }

    private suspend fun invalidateChatSessionFromCurrentJob() {
        invalidateChatSession(currentCoroutineContext()[Job])
    }

    private fun invalidateChatSession(excludedJob: Job? = null) {
        chatEpoch++
        turnRevision++
        configurationRevision++

        fun cancel(job: Job?) {
            if (job != null && job != excludedJob) job.cancel()
        }

        cancel(sendJob)
        cancel(cancelJob)
        cancel(modelsJob)
        cancel(configurationJob)
        approvalJobs.values.forEach(::cancel)
        questionJobs.values.forEach(::cancel)
        toolDetailsJobs.values.forEach(::cancel)
        cancel(historyJob)
        sendJob = null
        cancelJob = null
        modelsJob = null
        configurationJob = null
        historyJob = null
        approvalJobs.clear()
        questionJobs.clear()
        toolDetailsJobs.clear()
        // Detailed output belongs only to the active in-memory chat session. It is deliberately
        // discarded on every chat transition and is never written to a persistent store.
        mutable.value = mutable.value.copy(
            toolDetails = ToolDetailsState(),
            questions = QuestionState(),
            history = TranscriptHistoryState(),
        )
    }

    private fun approvalsUnavailable(error: GatewayException): Boolean =
        error.message?.contains("Remote tool approvals are disabled") == true ||
                error.message?.contains("requires Zed-Desktop Mobile Gateway API v2") == true

    private fun deriveTurnPhase(
        status: String,
        approvals: List<ToolApproval>,
        elicitations: List<Elicitation> = emptyList(),
    ): TurnPhase {
        if (approvals.isNotEmpty() || elicitations.isNotEmpty()) return TurnPhase.AWAITING_APPROVAL
        return when (status.trim().lowercase().replace('-', '_').replace(' ', '_')) {
            "sending", "running", "active", "pending", "queued", "in_progress", "processing" -> TurnPhase.RUNNING
            "awaiting_approval", "waiting_for_approval", "waiting" -> TurnPhase.AWAITING_APPROVAL
            "cancelling", "canceling" -> TurnPhase.CANCELLING
            "completed", "complete", "done", "cancelled", "canceled" -> TurnPhase.COMPLETED
            "failed", "error" -> TurnPhase.FAILED
            else -> TurnPhase.IDLE
        }
    }

    private fun shouldPreserveLocalTurn(
        current: ChatTurnState,
        pendingMessage: PendingMessage?,
        pendingWasObserved: Boolean,
        derived: TurnPhase,
    ): Boolean {
        if (current.phase == TurnPhase.CANCELLING && derived in setOf(
                TurnPhase.RUNNING,
                TurnPhase.AWAITING_APPROVAL,
                TurnPhase.CANCELLING,
            )
        ) {
            return true
        }
        if (pendingWasObserved) return false
        if (pendingMessage?.status == PendingMessageStatus.FAILED && current.phase == TurnPhase.FAILED) {
            return derived != TurnPhase.FAILED
        }
        return pendingMessage != null &&
                current.phase in setOf(TurnPhase.SENDING, TurnPhase.RUNNING, TurnPhase.CANCELLING) &&
                derived in setOf(TurnPhase.IDLE, TurnPhase.COMPLETED)
    }

    private fun Message.isUserMessage(expectedText: String): Boolean =
        role.trim().equals("user", ignoreCase = true) && text == expectedText

    /** Loads only a catalog whose persisted origin and device ID still match this credential. */
    private suspend fun loadCachedProjects(credential: DeviceCredential): List<Project> = try {
        projectCatalogStore.load(credential).orEmpty()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        emptyList()
    }

    /** Cache writes are best-effort; a DataStore failure must not invalidate desktop data. */
    private suspend fun saveProjectCatalog(credential: DeviceCredential, projects: List<Project>) {
        try {
            projectCatalogStore.save(credential, projects)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The authenticated desktop response remains usable even if its UI cache cannot persist.
        }
    }

    private suspend fun syncV2Projects(credential: DeviceCredential, candidate: ZedRepository) {
        try {
            val projects = candidate.projects()
            saveProjectCatalog(credential, projects)
            if (activeV2Credential == credential) {
                mutable.value = mutable.value.copy(projects = projects, gatewayUnavailable = false)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: GatewayAuthenticationException) {
            handleRevokedCredential()
        } catch (_: Exception) {
            if (activeV2Credential == credential) {
                // Keep cached projects visible but disable actions until desktop responds again.
                mutable.value = mutable.value.copy(gatewayUnavailable = true)
            }
        }
    }

    private suspend fun handleRevokedCredential() {
        invalidateChatSessionFromCurrentJob()
        clearPersistentPairingData()
        mutable.value = MobileState(notice = "This desktop removed the pairing. Scan a new QR code to reconnect.")
    }

    private suspend fun clearProjectCatalog() {
        try {
            projectCatalogStore.clear()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The cache is UI-only and no cached project data is logged.
        }
    }

    private suspend fun clearPersistentPairingData() {
        activeV2Credential = null
        repository = null
        clearProjectCatalog()
        try {
            credentialStore.clear()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Both stores are private; failures are intentionally not logged with their contents.
        }
    }

    private fun navigate(page: Page) {
        epoch++
        mutable.value = mutable.value.copy(page = page, error = null, pollError = null, notice = null)
    }

    private fun task(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        val version = epoch
        mutable.value = mutable.value.copy(busy = true, error = null, notice = null)
        action = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: GatewayAuthenticationException) {
                if (activeV2Credential != null) {
                    handleRevokedCredential()
                } else if (version == epoch) {
                    mutable.value = mutable.value.copy(error = message(e))
                }
            } catch (e: Exception) {
                if (version == epoch) mutable.value = mutable.value.copy(error = message(e))
            } finally {
                if (version == epoch) mutable.value = mutable.value.copy(busy = false)
            }
        }
    }

    private fun message(e: Exception) = when (e) {
        is GatewayException, is CredentialStoreException, is IllegalArgumentException -> e.message ?: "Request failed."
        else -> "Unexpected response. Refresh or reconnect to the desktop."
    }

    private companion object {
        const val DEFAULT_DEVICE_LABEL = "Zed Mobile"
        const val OPEN_PROJECT_POLL_ATTEMPTS = 15
        const val OPEN_PROJECT_POLL_INTERVAL_MILLIS = 2_000L
    }
}
