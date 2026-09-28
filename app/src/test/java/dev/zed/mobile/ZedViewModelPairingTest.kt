package dev.zed.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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
class ZedViewModelPairingTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `pairQr claims verifies and saves the in-memory credential`() = runTest {
        val ticket = PairingTicket(
            origin = "https://zed.example:7443",
            ticketId = "ticket-123",
            secret = "secret-ABC_123",
        )
        val credential = DeviceCredential(
            origin = ticket.origin,
            deviceId = "device-123",
            credential = "credential-ABC_123",
        )
        val store = InMemoryCredentialStore()
        val catalogStore = InMemoryProjectCatalogStore()
        val pairing = RecordingPairingRepository(credential)
        val repository = RecordingZedRepository(listOf(Project("project-1", "Mobile", "/work/mobile")))
        var pairingEndpoint: String? = null
        var connectedCredential: DeviceCredential? = null
        val viewModel = ZedViewModel(
            connectV2 = {
                connectedCredential = it
                repository
            },
            pairingRepository = {
                pairingEndpoint = it
                pairing
            },
            credentialStore = store,
            projectCatalogStore = catalogStore,
        )

        viewModel.pairQr(QrPairingContract.encode(ticket, debug = false))

        assertEquals(ticket.origin, pairingEndpoint)
        assertEquals(ticket, pairing.claimedTicket)
        assertEquals("Zed Mobile", pairing.deviceLabel)
        assertEquals(credential, connectedCredential)
        assertEquals(credential, store.load())
        assertEquals(repository.projectList, catalogStore.load(credential))
        assertEquals(1, repository.statusChecks)
        assertEquals(Page.PROJECTS, viewModel.state.value.page)
        assertTrue(viewModel.state.value.hasPersistentPairing)
        assertEquals(ticket.origin, viewModel.state.value.host)
        assertEquals(repository.projectList, viewModel.state.value.projects)
        assertFalse(viewModel.state.value.busy)
    }

    @Test
    fun `restored pairing clears the credential when desktop has revoked it`() = runTest {
        val credential = DeviceCredential(
            origin = "https://zed.example:7443",
            deviceId = "device-123",
            credential = "credential-ABC_123",
        )
        val store = InMemoryCredentialStore(credential)
        val catalogStore = InMemoryProjectCatalogStore().apply {
            save(credential, listOf(Project("project-1", "Cached", "/work/cached", isOpen = false)))
        }
        val repository = RecordingZedRepository(emptyList()).apply {
            statusError = GatewayAuthenticationException("Device credential rejected.")
        }
        val viewModel = ZedViewModel(
            connectV2 = { repository },
            credentialStore = store,
            projectCatalogStore = catalogStore,
        )

        viewModel.restorePersistentPairing()

        assertEquals(1, repository.statusChecks)
        assertNull(store.load())
        assertNull(catalogStore.load(credential))
        assertFalse(viewModel.state.value.hasPersistentPairing)
        assertEquals(Page.PAIR, viewModel.state.value.page)
        assertEquals("This desktop removed the pairing. Scan a new QR code to reconnect.", viewModel.state.value.notice)
        assertFalse(viewModel.state.value.busy)
    }

    @Test
    fun `restorePersistentPairing loads a stored credential and opens projects`() = runTest {
        val credential = DeviceCredential(
            origin = "https://zed.example:7443",
            deviceId = "device-123",
            credential = "credential-ABC_123",
        )
        val store = InMemoryCredentialStore(credential)
        val catalogStore = InMemoryProjectCatalogStore()
        val repository = RecordingZedRepository(listOf(Project("project-1", "Mobile", "/work/mobile")))
        var connectedCredential: DeviceCredential? = null
        val viewModel = ZedViewModel(
            connectV2 = {
                connectedCredential = it
                repository
            },
            credentialStore = store,
            projectCatalogStore = catalogStore,
        )

        viewModel.restorePersistentPairing()

        assertEquals(credential, connectedCredential)
        assertEquals(1, repository.statusChecks)
        assertEquals(Page.PROJECTS, viewModel.state.value.page)
        assertTrue(viewModel.state.value.hasPersistentPairing)
        assertEquals(credential.origin, viewModel.state.value.host)
        assertEquals(repository.projectList, viewModel.state.value.projects)
        assertEquals(repository.projectList, catalogStore.load(credential))
        assertFalse(viewModel.state.value.busy)
    }

    private class RecordingPairingRepository(
        private val claimResponse: DeviceCredential,
    ) : PairingRepository {
        var claimedTicket: PairingTicket? = null
        var deviceLabel: String? = null

        override suspend fun claim(ticket: PairingTicket, deviceLabel: String): DeviceCredential {
            claimedTicket = ticket
            this.deviceLabel = deviceLabel
            return claimResponse
        }
    }

    private class RecordingZedRepository(
        val projectList: List<Project>,
    ) : ZedRepository {
        var statusChecks = 0
        var statusError: Exception? = null

        override suspend fun checkStatus() {
            statusChecks++
            statusError?.let { throw it }
        }

        override suspend fun projects(): List<Project> = projectList

        override suspend fun threads(projectId: String): List<ThreadSummary> = unsupported()

        override suspend fun thread(id: String): ThreadSnapshot = unsupported()

        override suspend fun createThread(projectId: String): ThreadSummary = unsupported()

        override suspend fun send(id: String, text: String) = unsupported<Unit>()

        override suspend fun cancel(id: String) = unsupported<Unit>()

        override suspend fun changes(id: String): List<ChangedFile> = unsupported()

        private fun <T> unsupported(): T = error("This operation is not used by the pairing tests.")
    }
}
