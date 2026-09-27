package dev.zed.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zeddesktop.mobile.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Page { PAIR, PROJECTS, THREADS, CHAT, CHANGES, SETTINGS }
data class MobileState(
    val page: Page = Page.PAIR,
    val host: String = "",
    val projects: List<Project> = emptyList(),
    val project: Project? = null,
    val threads: List<ThreadSummary> = emptyList(),
    val selectedThread: ThreadSummary? = null,
    val snapshot: ThreadSnapshot? = null,
    val files: List<ChangedFile> = emptyList(),
    val approvals: List<ToolApproval> = emptyList(),
    val models: List<ModelCapability> = emptyList(),
    val configuration: ThreadConfiguration? = null,
    val draft: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val pollError: String? = null,
    val notice: String? = null,
    val lastUpdated: Long? = null,
)

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
) : ViewModel() {
    private val mutable = MutableStateFlow(MobileState())
    val state = mutable.asStateFlow()
    private var repository: ZedRepository? = null
    private var epoch = 0L
    private var action: Job? = null

    /** Existing manual host/token flow. This is retained as the v1 fallback. */
    fun pair(host: String, token: String) = task {
        val candidate = connect(host, token)
        candidate.checkStatus()
        val projects = candidate.projects()
        repository = candidate
        mutable.value = MobileState(page = Page.PROJECTS, host = host.trim(), projects = projects, busy = true)
    }

    /** Claims a ZEDM2 QR ticket, verifies v2 access, then persists the per-device credential. */
    fun pairQr(qrValue: String, deviceLabel: String = DEFAULT_DEVICE_LABEL) = task {
        val ticket = QrPairingContract.parse(qrValue, BuildConfig.DEBUG)
        val credential = pairingRepository(ticket.origin).claim(ticket, deviceLabel)
        val candidate = connectV2(credential)
        candidate.checkStatus()
        credentialStore.save(credential)
        repository = candidate
        mutable.value = MobileState(page = Page.PROJECTS, host = credential.origin.trim(), busy = true)
        val projects = candidate.projects()
        mutable.value = mutable.value.copy(projects = projects)
    }

    /** Restores a prior v2 QR pairing, if one has been securely saved. */
    fun restorePersistentPairing() = task {
        val credential = credentialStore.load() ?: return@task
        val candidate = connectV2(credential)
        try {
            candidate.checkStatus()
        } catch (error: GatewayAuthenticationException) {
            credentialStore.clear()
            repository = null
            mutable.value = MobileState(notice = "This desktop removed the pairing. Scan a new QR code to reconnect.")
            return@task
        }
        repository = candidate
        mutable.value = MobileState(page = Page.PROJECTS, host = credential.origin.trim(), busy = true)
        val projects = candidate.projects()
        mutable.value = mutable.value.copy(projects = projects)
    }

    /** Removes only this phone's encrypted local credential. Desktop revocation is controlled in Settings → Mobile Gateway. */
    fun disconnect() {
        epoch++
        action?.cancel()
        repository = null
        val version = epoch
        mutable.value = MobileState(busy = true)
        action = viewModelScope.launch {
            try {
                credentialStore.clear()
                if (version == epoch) mutable.value = MobileState(notice = "Pairing removed from this phone.")
            } catch (error: Exception) {
                if (version == epoch) mutable.value = MobileState(error = message(error))
            } finally {
                if (version == epoch) mutable.value = mutable.value.copy(busy = false)
            }
        }
    }

    fun openProject(project: Project) {
        if (mutable.value.busy) return
        navigate(Page.THREADS)
        mutable.value = mutable.value.copy(project = project, threads = emptyList())
        refresh()
    }

    fun openThread(thread: ThreadSummary) {
        if (mutable.value.busy) return
        navigate(Page.CHAT)
        mutable.value = mutable.value.copy(
            selectedThread = thread,
            snapshot = null,
            approvals = emptyList(),
            models = emptyList(),
            configuration = null,
            draft = "",
            lastUpdated = null,
        )
        // The visible chat owns the first fetch and subsequent lifecycle-aware polling.
    }

    fun createThread() {
        val project = mutable.value.project ?: return
        task {
            val thread = requireNotNull(repository).createThread(project.id)
            mutable.value = mutable.value.copy(
                page = Page.CHAT, selectedThread = thread, snapshot = null,
                approvals = emptyList(), models = emptyList(), configuration = null, draft = "", lastUpdated = null,
                notice = "Thread created. Send your first prompt."
            )
        }
    }

    fun editDraft(text: String) {
        mutable.value = mutable.value.copy(draft = text)
    }

    fun send() {
        val id = mutable.value.selectedThread?.id ?: return
        val text = mutable.value.draft
        if (text.isBlank()) return
        task {
            requireNotNull(repository).send(id, text)
            mutable.value = mutable.value.copy(draft = "", notice = "Prompt accepted. Waiting for desktop…")
        }
    }

    fun cancel() {
        val id = mutable.value.selectedThread?.id ?: return
        task {
            requireNotNull(repository).cancel(id)
            mutable.value = mutable.value.copy(notice = "Cancellation requested. Desktop status will update shortly.")
        }
    }

    fun showChanges() {
        if (mutable.value.busy) return
        navigate(Page.CHANGES)
        mutable.value = mutable.value.copy(files = emptyList())
        refresh()
    }

    fun showProjects() {
        if (mutable.value.busy) return
        navigate(Page.PROJECTS)
        refresh()
    }

    fun showThreads() {
        if (mutable.value.busy || mutable.value.project == null) return
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
                navigate(Page.THREADS); mutable.value =
                    mutable.value.copy(
                        draft = "",
                        snapshot = null,
                        approvals = emptyList(),
                        models = emptyList(),
                        configuration = null,
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
            when (current.page) {
                Page.PROJECTS -> mutable.value = mutable.value.copy(projects = repo.projects())
                Page.THREADS -> mutable.value =
                    mutable.value.copy(threads = repo.threads(requireNotNull(current.project).id))

                Page.CHANGES -> mutable.value =
                    mutable.value.copy(files = repo.changes(requireNotNull(current.selectedThread).id))

                else -> Unit
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
        try {
            val snapshot = repo.thread(id)
            val approvals = runCatching { repo.approvals(id) }.getOrElse { error ->
                if (error is GatewayException && (
                            error.message?.contains("Remote tool approvals are disabled") == true ||
                                    error.message?.contains("requires Zed-Desktop Mobile Gateway API v2") == true
                            )
                ) {
                    emptyList()
                } else {
                    throw error
                }
            }
            val configuration = runCatching { repo.configuration(id) }.getOrNull()
            if (version == epoch && mutable.value.page == Page.CHAT && mutable.value.selectedThread?.id == id) {
                mutable.value = mutable.value.copy(
                    snapshot = snapshot,
                    approvals = approvals,
                    configuration = configuration,
                    pollError = null,
                    lastUpdated = System.currentTimeMillis(),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (version == epoch && mutable.value.page == Page.CHAT) mutable.value =
                mutable.value.copy(pollError = message(e))
        }
    }

    fun resolveApproval(toolCallId: String, allow: Boolean) {
        val threadId = mutable.value.selectedThread?.id ?: return
        task {
            requireNotNull(repository).resolveApproval(threadId, toolCallId, allow)
            mutable.value =
                mutable.value.copy(notice = if (allow) "Tool approved on this phone." else "Tool denied on this phone.")
        }
    }

    fun updateConfiguration(
        model: ModelCapability? = null,
        thinkingEnabled: Boolean? = null,
        thinkingEffort: String? = null,
        clearThinkingEffort: Boolean = false,
    ) {
        val threadId = mutable.value.selectedThread?.id ?: return
        task {
            val configuration = requireNotNull(repository).updateConfiguration(
                id = threadId,
                model = model,
                thinkingEnabled = thinkingEnabled,
                thinkingEffort = thinkingEffort,
                clearThinkingEffort = clearThinkingEffort,
            )
            mutable.value = mutable.value.copy(configuration = configuration, notice = "Thread configuration updated.")
        }
    }

    fun loadModels() {
        val projectId = mutable.value.project?.id ?: return
        task {
            val models = requireNotNull(repository).models(projectId)
            mutable.value = mutable.value.copy(
                models = models,
                notice = if (models.isEmpty()) "No authenticated desktop models are available." else null,
            )
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
    }
}
