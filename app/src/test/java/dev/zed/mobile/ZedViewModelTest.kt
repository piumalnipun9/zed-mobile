package dev.zed.mobile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Test-only fixture: production always constructs HttpZedRepository. */
private class FakeRepository : ZedRepository {
    var sends = 0
    var cancels = 0
    var toolDetailsCalls = 0
    var failSend = false
    var failToolDetails = false
    var snapshotGate: CompletableDeferred<ThreadSnapshot>? = null
    var approvalGate: CompletableDeferred<Unit>? = null
    var toolDetailsGate: CompletableDeferred<ToolDetails>? = null
    var toolDetailsResponse = ToolDetails(
        toolCallId = "call-1",
        status = "completed",
        available = true,
        format = "markdown",
        output = "tool output",
    )
    var snapshot = ThreadSnapshot("t", "Thread", "idle", listOf(Message("tool", "Testing")))
    var approvalResponses: List<ToolApproval> = emptyList()
    val approvalResolutions = mutableListOf<Pair<String, Boolean>>()
    val project = Project("p", "Project", "/work")
    val summary = ThreadSummary("t", "Thread", "running")

    override suspend fun checkStatus() = Unit
    override suspend fun projects() = listOf(project)
    override suspend fun threads(projectId: String) = listOf(summary)
    override suspend fun thread(id: String) = snapshotGate?.await() ?: snapshot
    override suspend fun createThread(projectId: String) = summary

    /** Null means an older desktop without the agents route. */
    var agentList: List<AgentInfo>? = null
    val createdWithAgent = mutableListOf<String?>()
    val modeChanges = mutableListOf<String>()

    override suspend fun agents(projectId: String) = agentList ?: throw GatewayException("Requires API v2.")

    override suspend fun createThread(projectId: String, agentId: String?): ThreadSummary {
        createdWithAgent += agentId
        return summary.copy(agent = agentList?.firstOrNull { it.id == agentId })
    }

    override suspend fun setAgentMode(id: String, modeId: String): ThreadConfiguration {
        modeChanges += modeId
        return ThreadConfiguration(
            model = null,
            thinkingEnabled = false,
            thinkingEffort = null,
            agent = agentList?.firstOrNull { !it.isNative },
            modes = AgentModes(modeId, listOf(AgentMode("default", "Default"), AgentMode(modeId, "Plan"))),
        )
    }

    override suspend fun send(id: String, text: String) {
        sends++
        if (failSend) throw GatewayException("Offline")
    }

    override suspend fun cancel(id: String) {
        cancels++
    }

    override suspend fun changes(id: String) = listOf(ChangedFile("a.kt", "+test", 1, 0))

    override suspend fun toolDetails(threadId: String, toolCallId: String): ToolDetails {
        toolDetailsCalls++
        if (failToolDetails) throw GatewayException("Tool output unavailable")
        return toolDetailsGate?.await() ?: toolDetailsResponse
    }

    override suspend fun approvals(id: String) = approvalResponses

    override suspend fun resolveApproval(id: String, toolCallId: String, allow: Boolean) {
        approvalResolutions += toolCallId to allow
        approvalGate?.await()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ZedViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `new threads use the built-in agent unless desktop offers external agents`() {
        runTest(dispatcher) {
            val repo = FakeRepository()
            val vm = ZedViewModel(connect = { _, _ -> repo }, credentialStore = InMemoryCredentialStore())
            vm.pair("https://desktop", "token"); advanceUntilIdle()
            assertTrue(vm.state.value.projectAgents["p"].orEmpty().isEmpty())
            // With one open project and no external agents, a new thread starts without asking.
            vm.startNewThread(); advanceUntilIdle()
            assertNull(vm.state.value.newThread)
            assertEquals(listOf<String?>(null), repo.createdWithAgent)

            vm.back(); advanceUntilIdle()
            repo.agentList = listOf(AgentInfo("Zed Agent", "Zed Agent", true), AgentInfo("claude-acp", "Claude Agent", false))
            vm.refresh(); advanceUntilIdle()
            assertEquals(2, vm.state.value.projectAgents["p"]?.size)
            vm.startNewThread()
            assertEquals(NewThreadRequest("p"), vm.state.value.newThread)
            vm.dismissNewThread()
            assertNull(vm.state.value.newThread)
            vm.startNewThread(); vm.createThread("p", "claude-acp"); advanceUntilIdle()
            assertNull(vm.state.value.newThread)
            assertEquals(listOf(null, "claude-acp"), repo.createdWithAgent)
            assertEquals(Page.CHAT, vm.state.value.page)
            assertEquals("Claude Agent", vm.state.value.selectedThread?.agent?.name)

            vm.setAgentMode("plan"); advanceUntilIdle()
            assertEquals(listOf("plan"), repo.modeChanges)
            assertEquals("plan", vm.state.value.configuration?.modes?.current)
            assertTrue(vm.state.value.configuration?.isExternalAgent == true)
            vm.disconnect(); advanceUntilIdle()
        }
    }

    @Test
    fun navigationCreateSendCancelAndChanges() {
        runTest(dispatcher) {
            val repo = FakeRepository()
            val vm = ZedViewModel(connect = { _, _ -> repo }, credentialStore = InMemoryCredentialStore())
            vm.pair("https://desktop", "token"); advanceUntilIdle()
            assertEquals(Page.PROJECTS, vm.state.value.page)
            vm.toggleProject(repo.project); advanceUntilIdle()
            assertTrue("p" in vm.state.value.expandedProjectIds)
            vm.createThread("p"); vm.createThread("p"); advanceUntilIdle()
            assertEquals(Page.CHAT, vm.state.value.page)
            vm.pollThread(); assertEquals("tool", vm.state.value.snapshot?.messages?.single()?.role)
            vm.editDraft("hello"); vm.send(); vm.send(); advanceUntilIdle()
            assertEquals(1, repo.sends); assertEquals("", vm.state.value.draft)
            vm.cancel(); advanceUntilIdle(); assertEquals(1, repo.cancels)
            vm.showChanges(); advanceUntilIdle(); assertEquals(1, vm.state.value.files.size)
            vm.back(); assertEquals(Page.CHAT, vm.state.value.page)
            vm.disconnect(); advanceUntilIdle(); assertEquals(
            Page.PAIR,
            vm.state.value.page
        ); assertEquals("Pairing removed from this phone.", vm.state.value.notice)
        }
    }

    @Test
    fun sendImmediatelyClearsDraftAndKeepsAnInlinePendingMessage() {
        runTest(dispatcher) {
            val repo = FakeRepository()
            val vm = ZedViewModel(connect = { _, _ -> repo }, credentialStore = InMemoryCredentialStore())
            vm.pair("https://desktop", "token"); advanceUntilIdle()
            vm.openThread(repo.summary)

            vm.editDraft("hello")
            vm.send()

            assertEquals("", vm.state.value.draft)
            assertEquals(TurnPhase.SENDING, vm.state.value.turn.phase)
            assertEquals("hello", vm.state.value.pendingMessage?.text)
            assertEquals(PendingMessageStatus.SENDING, vm.state.value.pendingMessage?.status)
            assertFalse(vm.state.value.busy)
            assertNull(vm.state.value.error)
            assertNull(vm.state.value.notice)

            advanceUntilIdle()

            assertEquals(1, repo.sends)
            assertEquals(TurnPhase.RUNNING, vm.state.value.turn.phase)
            assertEquals(PendingMessageStatus.SENT, vm.state.value.pendingMessage?.status)
            assertNull(vm.state.value.notice)
        }
    }

    @Test
    fun failedSendRestoresDraftAndStaysInlineWithoutRetrying() {
        runTest(dispatcher) {
            val repo = FakeRepository().apply { failSend = true }
            val vm = ZedViewModel(connect = { _, _ -> repo }, credentialStore = InMemoryCredentialStore())
            vm.pair("https://desktop", "token"); advanceUntilIdle()
            vm.openThread(repo.summary)

            vm.editDraft("keep me")
            vm.send()
            assertEquals("", vm.state.value.draft)
            assertEquals(PendingMessageStatus.SENDING, vm.state.value.pendingMessage?.status)

            advanceUntilIdle()

            assertEquals(1, repo.sends)
            assertEquals("keep me", vm.state.value.draft)
            assertEquals(TurnPhase.FAILED, vm.state.value.turn.phase)
            assertEquals(PendingMessageStatus.FAILED, vm.state.value.pendingMessage?.status)
            assertEquals("Offline", vm.state.value.pendingMessage?.error)
            assertNull(vm.state.value.error)
            assertNull(vm.state.value.notice)

            vm.pollThread(); vm.pollThread()

            assertEquals(1, repo.sends)
            assertEquals(PendingMessageStatus.FAILED, vm.state.value.pendingMessage?.status)
            assertNull(vm.state.value.error)
        }
    }

    @Test
    fun approvalResolutionIsTrackedWithoutUsingGlobalBusyOrNotice() {
        runTest(dispatcher) {
            val approval = ToolApproval(
                toolCallId = "call-1",
                title = "Run tests",
                toolName = "terminal",
                content = emptyList(),
                rawInput = null,
                allowOnceAvailable = true,
                denyOnceAvailable = true,
            )
            val repo = FakeRepository().apply { approvalResponses = listOf(approval) }
            val vm = ZedViewModel(connect = { _, _ -> repo }, credentialStore = InMemoryCredentialStore())
            vm.pair("https://desktop", "token"); advanceUntilIdle()
            vm.openThread(repo.summary)
            vm.pollThread()
            assertEquals(TurnPhase.AWAITING_APPROVAL, vm.state.value.turn.phase)

            val gate = CompletableDeferred<Unit>()
            repo.approvalGate = gate
            vm.resolveApproval(approval.toolCallId, allow = true)
            runCurrent()

            assertEquals(setOf(approval.toolCallId), vm.state.value.approval.resolvingToolCallIds)
            assertFalse(vm.state.value.busy)
            assertNull(vm.state.value.notice)
            assertEquals(listOf(approval.toolCallId to true), repo.approvalResolutions)

            vm.resolveApproval(approval.toolCallId, allow = true)
            runCurrent()
            assertEquals(1, repo.approvalResolutions.size)

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(setOf(approval.toolCallId), vm.state.value.approval.resolvingToolCallIds)
            repo.approvalResponses = emptyList()
            vm.pollThread()
            assertTrue(vm.state.value.approval.resolvingToolCallIds.isEmpty())
            assertNull(vm.state.value.notice)
        }
    }

    @Test
    fun staleRunningPollDoesNotReenableCancellation() {
        runTest(dispatcher) {
            val repo = FakeRepository().apply {
                snapshot = ThreadSnapshot("t", "Thread", "running", emptyList())
            }
            val vm = ZedViewModel(connect = { _, _ -> repo }, credentialStore = InMemoryCredentialStore())
            vm.pair("https://desktop", "token"); advanceUntilIdle()
            vm.openThread(repo.summary)
            vm.pollThread()
            assertEquals(TurnPhase.RUNNING, vm.state.value.turn.phase)

            vm.cancel()
            advanceUntilIdle()
            assertEquals(TurnPhase.CANCELLING, vm.state.value.turn.phase)

            vm.pollThread()
            assertEquals(TurnPhase.CANCELLING, vm.state.value.turn.phase)

            vm.cancel()
            advanceUntilIdle()
            assertEquals(1, repo.cancels)
        }
    }

    @Test
    fun staleIdlePollDoesNotClearAnAcceptedPendingSend() {
        runTest(dispatcher) {
            val repo = FakeRepository().apply {
                snapshot = ThreadSnapshot("t", "Thread", "idle", emptyList())
            }
            val vm = ZedViewModel(connect = { _, _ -> repo }, credentialStore = InMemoryCredentialStore())
            vm.pair("https://desktop", "token"); advanceUntilIdle()
            vm.openThread(repo.summary)
            vm.editDraft("hello")
            vm.send()
            advanceUntilIdle()

            val gate = CompletableDeferred<ThreadSnapshot>()
            repo.snapshotGate = gate
            val poll = launch { vm.pollThread() }
            runCurrent()
            gate.complete(ThreadSnapshot("t", "Thread", "idle", emptyList()))
            poll.join()

            assertEquals(TurnPhase.RUNNING, vm.state.value.turn.phase)
            assertEquals(PendingMessageStatus.SENT, vm.state.value.pendingMessage?.status)
        }
    }

    @Test
    fun pollDerivesTurnPhaseAndAcknowledgesMatchingPendingUserMessage() {
        runTest(dispatcher) {
            val approval = ToolApproval(
                toolCallId = "call-1",
                title = "Run tests",
                toolName = "terminal",
                content = emptyList(),
                rawInput = null,
                allowOnceAvailable = true,
                denyOnceAvailable = true,
            )
            val repo = FakeRepository().apply {
                snapshot = ThreadSnapshot("t", "Thread", "idle", emptyList())
            }
            val vm = ZedViewModel(connect = { _, _ -> repo }, credentialStore = InMemoryCredentialStore())
            vm.pair("https://desktop", "token"); advanceUntilIdle()
            vm.openThread(repo.summary)
            vm.editDraft("hello")
            vm.send()
            advanceUntilIdle()
            assertEquals(PendingMessageStatus.SENT, vm.state.value.pendingMessage?.status)

            repo.snapshot = ThreadSnapshot("t", "Thread", "running", listOf(Message("user", "hello")))
            vm.pollThread()

            assertEquals(TurnPhase.RUNNING, vm.state.value.turn.phase)
            assertNull(vm.state.value.pendingMessage)

            repo.snapshot = ThreadSnapshot("t", "Thread", "completed", listOf(Message("user", "hello")))
            repo.approvalResponses = listOf(approval)
            vm.pollThread()
            assertEquals(TurnPhase.AWAITING_APPROVAL, vm.state.value.turn.phase)

            repo.approvalResponses = emptyList()
            vm.pollThread()
            assertEquals(TurnPhase.COMPLETED, vm.state.value.turn.phase)
        }
    }

    @Test
    fun toolDetailsAreExplicitCoalescedAndCachedWithoutGlobalFeedback() {
        runTest(dispatcher) {
            val repo = FakeRepository().apply {
                snapshot = toolSnapshot("t")
                toolDetailsGate = CompletableDeferred()
            }
            val vm = ZedViewModel(connect = { _, _ -> repo }, credentialStore = InMemoryCredentialStore())
            vm.pair("https://desktop", "token"); advanceUntilIdle()
            vm.openThread(repo.summary)

            assertEquals(0, repo.toolDetailsCalls)
            vm.pollThread()
            assertEquals(0, repo.toolDetailsCalls)
            vm.loadToolDetails("not-in-transcript")
            assertEquals(0, repo.toolDetailsCalls)

            vm.loadToolDetails("call-1")
            vm.loadToolDetails("call-1")
            runCurrent()

            val key = ToolDetailsKey("t", "call-1")
            assertEquals(1, repo.toolDetailsCalls)
            assertTrue(key in vm.state.value.toolDetails.loading)
            assertFalse(vm.state.value.busy)
            assertNull(vm.state.value.notice)
            assertNull(vm.state.value.pollError)

            repo.toolDetailsGate?.complete(repo.toolDetailsResponse)
            advanceUntilIdle()

            assertEquals(repo.toolDetailsResponse, vm.state.value.toolDetails.cache[key])
            assertFalse(key in vm.state.value.toolDetails.loading)
            assertNull(vm.state.value.toolDetails.errors[key])
            vm.loadToolDetails("call-1")
            advanceUntilIdle()
            assertEquals(1, repo.toolDetailsCalls)
        }
    }

    @Test
    fun toolDetailsFailureIsCardLocalAndRetryAndSessionChangesAreSafe() {
        runTest(dispatcher) {
            val repo = FakeRepository().apply {
                snapshot = toolSnapshot("t")
                failToolDetails = true
            }
            val vm = ZedViewModel(connect = { _, _ -> repo }, credentialStore = InMemoryCredentialStore())
            vm.pair("https://desktop", "token"); advanceUntilIdle()
            vm.openThread(repo.summary)
            vm.pollThread()

            val key = ToolDetailsKey("t", "call-1")
            vm.loadToolDetails("call-1")
            advanceUntilIdle()
            assertEquals(1, repo.toolDetailsCalls)
            assertEquals("Tool output unavailable", vm.state.value.toolDetails.errors[key])
            assertFalse(vm.state.value.busy)
            assertNull(vm.state.value.notice)
            assertNull(vm.state.value.pollError)

            repo.failToolDetails = false
            vm.loadToolDetails("call-1", retry = true)
            advanceUntilIdle()
            assertEquals(2, repo.toolDetailsCalls)
            assertEquals(repo.toolDetailsResponse, vm.state.value.toolDetails.cache[key])

            val lateResult = CompletableDeferred<ToolDetails>()
            repo.toolDetailsGate = lateResult
            vm.loadToolDetails("call-1", retry = true)
            runCurrent()
            assertEquals(3, repo.toolDetailsCalls)
            vm.back()
            lateResult.complete(repo.toolDetailsResponse)
            advanceUntilIdle()

            assertEquals(Page.PROJECTS, vm.state.value.page)
            assertTrue(vm.state.value.toolDetails.cache.isEmpty())
            assertTrue(vm.state.value.toolDetails.loading.isEmpty())
            assertTrue(vm.state.value.toolDetails.errors.isEmpty())
        }
    }

    @Test
    fun toolDetailsWithTheSameIdStayScopedToTheirThread() {
        runTest(dispatcher) {
            val firstResult = ToolDetails(
                toolCallId = "call-1",
                status = "completed",
                available = true,
                format = "markdown",
                output = "first thread output",
            )
            val secondResult = firstResult.copy(output = "second thread output")
            val firstGate = CompletableDeferred<ToolDetails>()
            val repo = FakeRepository().apply {
                snapshot = toolSnapshot("t")
                toolDetailsGate = firstGate
                toolDetailsResponse = firstResult
            }
            val vm = ZedViewModel(connect = { _, _ -> repo }, credentialStore = InMemoryCredentialStore())
            vm.pair("https://desktop", "token"); advanceUntilIdle()
            vm.openThread(repo.summary)
            vm.pollThread()
            vm.loadToolDetails("call-1")
            runCurrent()
            assertEquals(1, repo.toolDetailsCalls)

            val secondThread = ThreadSummary("t-2", "Second thread", "completed")
            repo.snapshot = toolSnapshot("t-2")
            vm.openThread(secondThread)
            vm.pollThread()
            firstGate.complete(firstResult)
            advanceUntilIdle()

            repo.toolDetailsGate = null
            repo.toolDetailsResponse = secondResult
            vm.loadToolDetails("call-1")
            advanceUntilIdle()

            assertFalse(ToolDetailsKey("t", "call-1") in vm.state.value.toolDetails.cache)
            assertEquals(
                secondResult,
                vm.state.value.toolDetails.cache[ToolDetailsKey("t-2", "call-1")],
            )
        }
    }

    @Test
    fun ignoresLateSnapshotAfterUnpair() {
        runTest(dispatcher) {
            val repo = FakeRepository()
            val vm = ZedViewModel(connect = { _, _ -> repo }, credentialStore = InMemoryCredentialStore())
            vm.pair("https://desktop", "token"); advanceUntilIdle(); vm.openThread(repo.summary)
            val gate = CompletableDeferred<ThreadSnapshot>(); repo.snapshotGate = gate
            val poll = launch { vm.pollThread() }; runCurrent()
            vm.disconnect(); advanceUntilIdle()
            gate.complete(ThreadSnapshot("t", "Late", "done", emptyList())); poll.join()
            assertEquals(Page.PAIR, vm.state.value.page)
        }
    }

    private fun toolSnapshot(threadId: String) = ThreadSnapshot(
        id = threadId,
        title = "Thread",
        status = "completed",
        messages = emptyList(),
        transcript = listOf(
            TranscriptEntry(
                index = 0,
                kind = "tool_call",
                toolCallId = "call-1",
                title = "Run tests",
                toolName = "terminal",
                status = "completed",
                detailsAvailable = true,
            ),
        ),
    )
}
