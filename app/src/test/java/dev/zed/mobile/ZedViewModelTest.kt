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
    var failSend = false
    var snapshotGate: CompletableDeferred<ThreadSnapshot>? = null
    val project = Project("p", "Project", "/work")
    val summary = ThreadSummary("t", "Thread", "running")
    override suspend fun checkStatus() = Unit
    override suspend fun projects() = listOf(project)
    override suspend fun threads(projectId: String) = listOf(summary)
    override suspend fun thread(id: String) =
        snapshotGate?.await() ?: ThreadSnapshot(id, "Thread", "running", listOf(Message("tool", "Testing")))

    override suspend fun createThread(projectId: String) = summary
    override suspend fun send(id: String, text: String) {
        sends++; if (failSend) throw GatewayException("Offline")
    }

    override suspend fun cancel(id: String) {
        cancels++
    }

    override suspend fun changes(id: String) = listOf(ChangedFile("a.kt", "+test", 1, 0))
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
    fun navigationCreateSendCancelAndChanges() {
        runTest(dispatcher) {
            val repo = FakeRepository()
            val vm = ZedViewModel(connect = { _, _ -> repo }, credentialStore = InMemoryCredentialStore())
            vm.pair("https://desktop", "token"); advanceUntilIdle()
            assertEquals(Page.PROJECTS, vm.state.value.page)
            vm.openProject(repo.project); advanceUntilIdle()
            vm.createThread(); vm.createThread(); advanceUntilIdle()
            assertEquals(Page.CHAT, vm.state.value.page)
            vm.pollThread(); assertEquals("tool", vm.state.value.snapshot?.messages?.single()?.role)
            vm.editDraft("hello"); vm.send(); vm.send(); advanceUntilIdle()
            assertEquals(1, repo.sends); assertEquals("", vm.state.value.draft)
            vm.cancel(); advanceUntilIdle(); assertEquals(1, repo.cancels)
            vm.showChanges(); advanceUntilIdle(); assertEquals(1, vm.state.value.files.size)
            vm.back(); assertEquals(Page.CHAT, vm.state.value.page)
            vm.disconnect(); advanceUntilIdle(); assertEquals(Page.PAIR, vm.state.value.page); assertEquals("Pairing removed from this phone.", vm.state.value.notice)
        }
    }

    @Test
    fun preservesFailedDraftAndDoesNotRetryMutation() {
        runTest(dispatcher) {
            val repo = FakeRepository().apply { failSend = true }
            val vm = ZedViewModel(connect = { _, _ -> repo }, credentialStore = InMemoryCredentialStore())
            vm.pair("https://desktop", "token"); advanceUntilIdle()
            vm.openThread(repo.summary); vm.editDraft("keep me"); vm.send(); advanceUntilIdle()
            assertEquals("keep me", vm.state.value.draft)
            assertEquals("Offline", vm.state.value.error)
            vm.pollThread(); vm.pollThread()
            assertEquals(1, repo.sends); assertEquals("Offline", vm.state.value.error)
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
}
