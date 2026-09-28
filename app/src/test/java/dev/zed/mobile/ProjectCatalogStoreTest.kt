package dev.zed.mobile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectCatalogStoreTest {
    @Test
    fun `cache is served only to its exact origin and device binding`() = runBlocking {
        val store = InMemoryProjectCatalogStore()
        val credential = credential(origin = "https://zed.example:7443", deviceId = "device-a")
        val projects = listOf(Project("project-1", "Mobile", "/work/mobile", isOpen = false))

        store.save(credential, projects)

        assertEquals(projects, store.load(credential))
        assertNull(store.load(credential.copy(deviceId = "device-b")))
        // A mismatched credential destroys the old entry rather than leaving it for a later load.
        assertNull(store.load(credential))
    }

    @Test
    fun `origin mismatch also destroys the catalog`() = runBlocking {
        val store = InMemoryProjectCatalogStore()
        val credential = credential(origin = "https://zed.example:7443", deviceId = "device-a")

        store.save(credential, listOf(Project("project-1", "Mobile", "/work/mobile")))

        assertNull(store.load(credential.copy(origin = "https://other.example:7443")))
        assertNull(store.load(credential))
    }

    @Test
    fun `clear removes the bound catalog`() = runBlocking {
        val store = InMemoryProjectCatalogStore()
        val credential = credential(origin = "https://zed.example:7443", deviceId = "device-a")

        store.save(credential, listOf(Project("project-1", "Mobile", "/work/mobile")))
        store.clear()

        assertNull(store.load(credential))
    }

    @Test
    fun `cache schema retains remote source and reads legacy entries as local`() {
        val credential = credential(origin = "https://zed.example:7443", deviceId = "device-a")
        val remote = Project(
            id = "remote-1",
            name = "Remote mobile",
            path = "~/work/mobile",
            isOpen = false,
            kind = PROJECT_KIND_SSH,
            connectionName = "work-bastion",
            opening = true,
        )

        assertEquals(
            listOf(remote),
            decodeCachedProjectCatalog(encodeCachedProjectCatalog(credential, listOf(remote))).projects,
        )

        val legacy =
            """{"origin":"https://zed.example:7443","device_id":"device-a","projects":[{"id":"local-1","name":"Local mobile","path":"/work/mobile","is_open":false}]}"""
        assertEquals(
            Project("local-1", "Local mobile", "/work/mobile", isOpen = false),
            decodeCachedProjectCatalog(legacy).projects.single(),
        )
    }

    private fun credential(origin: String, deviceId: String) = DeviceCredential(
        origin = origin,
        deviceId = deviceId,
        credential = "credential-ABC_123",
    )
}
