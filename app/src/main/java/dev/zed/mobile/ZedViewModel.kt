package dev.zed.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zeddesktop.mobile.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Page { PAIR, PROJECTS, THREADS, CHAT, CHANGES, SETTINGS }
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
    private val projectCatalogStore: ProjectCatalogStore = ApplicationProjectCatalogStore(),
) : ViewModel() {
    private val mutable = MutableStateFlow(MobileState())
    val state = mutable.asStateFlow()
    private var repository: ZedRepository? = null
    private var activeV2Credential: DeviceCredential? = null
    private var epoch = 0L
    private var action: Job? = null

    /** Existing manual host/token flow. This is retained as the v1 fallback. */
    fun pair(host: String, token: String) = task {
        val candidate = connect(host, token)
        candidate.checkStatus()
        val projects = candidate.projects()
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
        task {
            val repo = requireNotNull(repository)
            // The catalog ID is opaque and desktop-owned. Never send a phone path or retry the POST.
            val opened = if (project.isOpen) project else openAndAwaitProject(repo, project)
            // Keep the catalog visible until both the desktop open and thread request have succeeded.
            val threads = repo.threads(opened.id)
            mutable.value = mutable.value.copy(
                page = Page.THREADS,
                project = opened,
                threads = threads,
                selectedThread = null,
                snapshot = null,
                notice = if (project.isOpen) null else "Opened on Desktop. Your Agent threads are ready.",
            )
        }
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
            try {
                when (current.page) {
                    Page.PROJECTS -> {
                        val projects = repo.projects()
                        activeV2Credential?.let { saveProjectCatalog(it, projects) }
                        mutable.value = mutable.value.copy(projects = projects, gatewayUnavailable = false)
                    }

                    Page.THREADS -> mutable.value =
                        mutable.value.copy(threads = repo.threads(requireNotNull(current.project).id))

                    Page.CHANGES -> mutable.value =
                        mutable.value.copy(files = repo.changes(requireNotNull(current.selectedThread).id))

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
        } catch (e: GatewayAuthenticationException) {
            if (activeV2Credential != null) {
                handleRevokedCredential()
            } else if (version == epoch && mutable.value.page == Page.CHAT) {
                mutable.value = mutable.value.copy(pollError = message(e))
            }
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
