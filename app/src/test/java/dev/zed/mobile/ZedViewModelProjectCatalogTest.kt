package dev.zed.mobile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ZedViewModelProjectCatalogTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `restore renders bound cached projects before replacing them with a desktop sync`() = runTest(dispatcher) {
        val credential = credential()
        val cachedProjects = listOf(Project("cached", "Cached project", "/work/cached", isOpen = false))
        val desktopProjects = listOf(Project("live", "Live project", "/work/live", isOpen = true))
        val catalogStore = InMemoryProjectCatalogStore().apply { save(credential, cachedProjects) }
        val statusGate = CompletableDeferred<Unit>()
        val repository = CatalogRepository(projectList = desktopProjects, statusGate = statusGate)
        val viewModel = ZedViewModel(
            connectV2 = { repository },
            credentialStore = InMemoryCredentialStore(credential),
            projectCatalogStore = catalogStore,
        )

        viewModel.restorePersistentPairing()
        runCurrent()

        assertEquals(Page.PROJECTS, viewModel.state.value.page)
        assertEquals(cachedProjects, viewModel.state.value.projects)
        assertTrue(viewModel.state.value.busy)
        assertFalse(viewModel.state.value.gatewayUnavailable)
        assertEquals(0, repository.projectsCalls)

        statusGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(desktopProjects, viewModel.state.value.projects)
        assertEquals(desktopProjects, catalogStore.load(credential))
        assertEquals(1, repository.projectsCalls)
        assertFalse(viewModel.state.value.gatewayUnavailable)
        assertFalse(viewModel.state.value.busy)
    }

    @Test
    fun `offline restore retains cached catalog and prevents opening a closed project`() = runTest(dispatcher) {
        val credential = credential()
        val cachedProject = Project("cached", "Cached project", "/work/cached", isOpen = false)
        val catalogStore = InMemoryProjectCatalogStore().apply { save(credential, listOf(cachedProject)) }
        val repository = CatalogRepository(projectList = emptyList()).apply {
            statusFailure = GatewayException("Connection failed.")
        }
        val viewModel = ZedViewModel(
            connectV2 = { repository },
            credentialStore = InMemoryCredentialStore(credential),
            projectCatalogStore = catalogStore,
        )

        viewModel.restorePersistentPairing()
        advanceUntilIdle()

        assertEquals(listOf(cachedProject), viewModel.state.value.projects)
        assertTrue(viewModel.state.value.gatewayUnavailable)
        assertFalse(viewModel.state.value.busy)

        viewModel.openProject(cachedProject)

        assertEquals(0, repository.openProjectCalls)
        assertEquals(Page.PROJECTS, viewModel.state.value.page)
        assertNull(viewModel.state.value.project)
        assertTrue(viewModel.state.value.error.orEmpty().contains("gateway is unavailable"))
    }

    @Test
    fun `local disconnect clears the credential and its project catalog`() = runTest(dispatcher) {
        val credential = credential()
        val credentialStore = InMemoryCredentialStore(credential)
        val catalogStore = InMemoryProjectCatalogStore().apply {
            save(credential, listOf(Project("cached", "Cached project", "/work/cached")))
        }
        val viewModel = ZedViewModel(
            credentialStore = credentialStore,
            projectCatalogStore = catalogStore,
        )

        viewModel.disconnect()
        advanceUntilIdle()

        assertNull(credentialStore.load())
        assertNull(catalogStore.load(credential))
        assertEquals(Page.PAIR, viewModel.state.value.page)
        assertFalse(viewModel.state.value.busy)
    }

    @Test
    fun `project navigation waits for desktop thread access before changing screens`() = runTest(dispatcher) {
        val project = Project("project-1", "Live project", "/work/live", isOpen = true)
        val repository = CatalogRepository(projectList = listOf(project)).apply {
            threadsFailure = GatewayException("Desktop connection failed.")
        }
        val viewModel = ZedViewModel(
            connect = { _, _ -> repository },
            credentialStore = InMemoryCredentialStore(),
        )

        viewModel.pair("https://zed.example:7443", "token")
        advanceUntilIdle()
        viewModel.openProject(project)
        advanceUntilIdle()

        assertEquals(Page.PROJECTS, viewModel.state.value.page)
        assertNull(viewModel.state.value.project)
        assertTrue(viewModel.state.value.error.orEmpty().contains("Desktop connection failed."))
    }

    @Test
    fun `opening project polls its opaque catalog ID once before loading threads`() = runTest(dispatcher) {
        val closed = Project(
            id = "opaque-remote-id",
            name = "Remote mobile",
            path = "~/work/mobile",
            isOpen = false,
            kind = PROJECT_KIND_SSH,
            connectionName = "work-bastion",
        )
        val connecting = closed.copy(opening = true)
        val ready = closed.copy(isOpen = true)
        val repository = CatalogRepository(projectList = listOf(closed))
        val viewModel = ZedViewModel(
            connect = { _, _ -> repository },
            credentialStore = InMemoryCredentialStore(),
        )

        viewModel.pair("https://zed.example:7443", "token")
        advanceUntilIdle()
        repository.openProjectResponse = connecting
        repository.projectResponses += listOf(connecting)
        repository.projectResponses += listOf(ready)

        viewModel.openProject(closed)
        runCurrent()

        assertEquals(Page.PROJECTS, viewModel.state.value.page)
        assertEquals(connecting, viewModel.state.value.projects.single())
        assertTrue(viewModel.state.value.notice.orEmpty().contains("SSH, host-key, or MFA"))
        assertEquals(1, repository.openProjectCalls)
        assertEquals(listOf("opaque-remote-id"), repository.openedProjectIds)
        assertEquals(0, repository.threadsCalls)

        advanceUntilIdle()

        assertEquals(Page.THREADS, viewModel.state.value.page)
        assertEquals(ready, viewModel.state.value.project)
        assertEquals(1, repository.openProjectCalls)
        assertEquals(1, repository.threadsCalls)
        assertTrue(repository.projectsCalls >= 3) // Initial pairing load plus authenticated catalog polling.
    }

    private fun credential() = DeviceCredential(
        origin = "https://zed.example:7443",
        deviceId = "device-123",
        credential = "credential-ABC_123",
    )

    private class CatalogRepository(
        val projectList: List<Project>,
        private val statusGate: CompletableDeferred<Unit>? = null,
    ) : ZedRepository {
        var projectsCalls = 0
        var openProjectCalls = 0
        var threadsCalls = 0
        val openedProjectIds = mutableListOf<String>()
        val projectResponses = mutableListOf<List<Project>>()
        var openProjectResponse: Project? = null
        var statusFailure: Exception? = null
        var projectsFailure: Exception? = null
        var threadsFailure: Exception? = null

        override suspend fun checkStatus() {
            statusGate?.await()
            statusFailure?.let { throw it }
        }

        override suspend fun projects(): List<Project> {
            projectsCalls++
            projectsFailure?.let { throw it }
            return if (projectResponses.isEmpty()) projectList else projectResponses.removeAt(0)
        }

        override suspend fun openProject(id: String): Project {
            openProjectCalls++
            openedProjectIds += id
            return openProjectResponse ?: projectList.first { it.id == id }.copy(isOpen = true)
        }

        override suspend fun threads(projectId: String): List<ThreadSummary> {
            threadsCalls++
            threadsFailure?.let { throw it }
            return emptyList()
        }

        override suspend fun thread(id: String): ThreadSnapshot = unsupported()
        override suspend fun createThread(projectId: String): ThreadSummary = unsupported()
        override suspend fun send(id: String, text: String) = unsupported<Unit>()
        override suspend fun cancel(id: String) = unsupported<Unit>()
        override suspend fun changes(id: String): List<ChangedFile> = unsupported()

        private fun <T> unsupported(): T = error("This operation is not used by project catalog tests.")
    }
}
