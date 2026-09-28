package dev.zed.mobile

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GatewayEventClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `receives validated completion events without browser or replay headers`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "event: thread_completed\n" +
                            "data: {\"project_id\":\"project-1\",\"thread_id\":\"thread-2\",\"turn_id\":\"turn-3\"}\n\n" +
                            "event: thread_failed\n" +
                            "data: {\"project_id\":\"project-4\",\"thread_id\":\"thread-5\",\"turn_id\":\"turn-6\"}\n\n",
                ),
        )
        val received = mutableListOf<AgentCompletionEvent>()

        GatewayEventClient(credential(), debug = false).collect { received += it }

        assertEquals(
            listOf(
                AgentCompletionEvent(
                    type = AgentCompletionEventType.COMPLETED,
                    projectId = "project-1",
                    threadId = "thread-2",
                    turnId = "turn-3",
                ),
                AgentCompletionEvent(
                    type = AgentCompletionEventType.FAILED,
                    projectId = "project-4",
                    threadId = "thread-5",
                    turnId = "turn-6",
                ),
            ),
            received,
        )
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("GET", request.method)
        assertEquals("/v2/events", request.path)
        assertEquals("Bearer fixture-credential", request.getHeader("Authorization"))
        assertEquals("text/event-stream", request.getHeader("Accept"))
        assertNull(request.getHeader("Origin"))
        assertNull(request.getHeader("Last-Event-ID"))
    }

    @Test
    fun `ignores malformed and mismatched event payloads`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "event: unrelated\n" +
                            "data: {\"project_id\":\"project-1\",\"thread_id\":\"thread-2\",\"turn_id\":\"turn-3\"}\n\n" +
                            "event: thread_failed\n" +
                            "data: {\"project_id\":false,\"thread_id\":\"thread-2\",\"turn_id\":\"turn-3\"}\n\n" +
                            "event: thread_completed\n" +
                            "data: {\"project_id\":\"project-1\",\"thread_id\":\"thread-2\",\"turn_id\":\"turn-3\",\"unexpected\":true}\n\n",
                ),
        )
        val received = mutableListOf<AgentCompletionEvent>()

        GatewayEventClient(credential(), debug = false).collect { received += it }

        assertTrue(received.isEmpty())
    }

    @Test
    fun `identifies credential rejection separately from a transient stream failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        val error = runCatching {
            GatewayEventClient(credential(), debug = false).collect { error("No event expected") }
        }.exceptionOrNull()

        assertTrue(error is GatewayEventAuthenticationException)
    }

    @Test
    fun `rejects an oversized SSE line`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: " + "x".repeat(16 * 1024 + 1)),
        )

        try {
            GatewayEventClient(credential(), debug = false).collect { error("No event expected") }
            error("Expected an oversized stream failure")
        } catch (error: GatewayEventException) {
            assertTrue(error.message.orEmpty().contains("too large"))
        }
    }

    private fun credential(): DeviceCredential = DeviceCredential(
        origin = "http://127.0.0.1:${server.port}",
        deviceId = "fixture-device",
        credential = "fixture-credential",
    )
}
