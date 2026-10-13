package dev.zed.mobile

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class HttpZedRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: HttpZedRepository

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        repo = HttpZedRepository("http://127.0.0.1:${server.port}", "fixture-token", false)
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    private fun respond(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    private fun request(method: String, path: String, body: String? = null) {
        val recorded = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals(method, recorded.method)
        assertEquals(path, recorded.path)
        assertEquals("Bearer fixture-token", recorded.getHeader("Authorization"))
        assertEquals("application/json", recorded.getHeader("Accept"))
        if (body != null) assertEquals(JsonParser.parseString(body), JsonParser.parseString(recorded.body.readUtf8()))
    }

    @Test
    fun completeV1ContractAndEncodedIds() {
        runBlocking {
            respond("""{"api_version":1,"name":"Zed-Desktop","future":true}""")
            repo.checkStatus(); request("GET", "/v1/status")
            respond("""{"projects":[{"id":"project /?%#","name":"Zed","path":"/work/zed"}]}""")
            val legacyProject = repo.projects().single()
            assertEquals("Zed", legacyProject.name)
            assertEquals(PROJECT_KIND_LOCAL, legacyProject.kind)
            assertFalse(legacyProject.opening)
            assertNull(legacyProject.connectionName)
            request("GET", "/v1/projects")
            respond("""{"threads":[{"id":"thread /?%#","title":"Fix bug","status":"running"}]}""")
            val summary = repo.threads("project /?%#").single()
            request("GET", "/v1/projects/project%20%2F%3F%25%23/threads")
            respond("""{"id":"thread /?%#","title":"Fix bug","status":"running","messages":[{"role":"user","text":"Fix it"},{"role":"tool","text":"Running tests"}]}""")
            assertEquals("tool", repo.thread(summary.id).messages.last().role)
            request("GET", "/v1/threads/thread%20%2F%3F%25%23")
            respond("""{"id":"new","title":"New thread","status":"idle"}""")
            assertEquals("new", repo.createThread("project /?%#").id)
            request("POST", "/v1/threads", """{"project_id":"project /?%#"}""")
            respond("""{"accepted":true}"""); repo.send(summary.id, "A \"quote\"\nnew line")
            request("POST", "/v1/threads/thread%20%2F%3F%25%23/messages", """{"text":"A \"quote\"\nnew line"}""")
            respond("""{"accepted":true}"""); repo.cancel(summary.id)
            request("POST", "/v1/threads/thread%20%2F%3F%25%23/cancel", "{}")
            respond("""{"files":[{"path":"a.kt","diff":"--- a/a.kt\n+++ b/a.kt\n@@ -1 +1 @@\n-old\n+new","additions":1,"deletions":1}]}""")
            assertTrue(repo.changes(summary.id).single().diff.contains("+new"))
            request("GET", "/v1/threads/thread%20%2F%3F%25%23/changes")
        }
    }

    @Test
    fun `v2 catalog maps remote source and connecting state`() {
        runBlocking {
            val v2Repo = HttpZedRepository.v2(
                host = "http://127.0.0.1:${server.port}",
                credential = "fixture-token",
                debug = false,
            )
            respond(
                """{"projects":[{"id":"opaque-catalog-id","name":"Remote Zed","path":"~/src/zed","is_open":false,"kind":"ssh","connection_name":"work-bastion","opening":true}]}""",
            )

            val project = v2Repo.projects().single()

            assertEquals("opaque-catalog-id", project.id)
            assertEquals(PROJECT_KIND_SSH, project.kind)
            assertEquals("work-bastion", project.connectionName)
            assertFalse(project.isOpen)
            assertTrue(project.opening)
            request("GET", "/v2/projects/catalog")
        }
    }

    @Test
    fun rejectsVersionMismatchMalformedPayloadAndUnacceptedActions() {
        runBlocking {
            respond("""{"api_version":2,"name":"Zed-Desktop"}""")
            expectFailure { repo.checkStatus() }
            respond("<html>bad gateway</html>"); expectFailure { repo.projects() }
            respond("""{"projects":[{"id":"p"}]}"""); expectFailure { repo.projects() }
            respond("""{"accepted":false}"""); expectFailure { repo.send("t", "hello") }
            respond("""{"accepted":"true"}"""); expectFailure { repo.cancel("t") }
        }
    }

    @Test
    fun surfacesErrorsAndNeverFollowsRedirects() {
        runBlocking {
            respond("""{"error":"Thread not found"}""", 404)
            assertTrue(expectFailure { repo.thread("missing") }.contains("Thread not found"))
            respond("""{"error":"secret server detail"}""", 401)
            assertTrue(expectFailure { repo.projects() }.contains("Pairing rejected"))
            MockWebServer().use { foreign ->
                foreign.start()
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", foreign.url("/stolen")))
                assertTrue(expectFailure { repo.projects() }.contains("Redirect refused"))
                assertNull(foreign.takeRequest(200, TimeUnit.MILLISECONDS))
            }
        }
    }

    @Test
    fun rejectsOversizedBodiesAndDotSegmentIds() {
        runBlocking {
            respond("x".repeat(4 * 1024 * 1024 + 1))
            assertTrue(expectFailure { repo.projects() }.contains("4 MiB"))
            try {
                repo.thread(".."); fail("Expected invalid ID")
            } catch (_: IllegalArgumentException) {
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `v2 tool details use an opaque scoped route and parse both outcomes`() {
        runBlocking {
            val v2Repo = HttpZedRepository.v2(
                host = "http://127.0.0.1:${server.port}",
                credential = "fixture-token",
                debug = false,
            )
            respond(
                """{"tool_call_id":"call /?%#","status":"completed","available":true,"format":"markdown","output":"line one\nline two","truncated":true}""",
            )

            val available = v2Repo.toolDetails("thread /?%#", "call /?%#")

            assertEquals("call /?%#", available.toolCallId)
            assertEquals("completed", available.status)
            assertTrue(available.available)
            assertEquals("markdown", available.format)
            assertEquals("line one\nline two", available.output)
            assertTrue(available.truncated)
            request(
                "GET",
                "/v2/threads/thread%20%2F%3F%25%23/tool-calls/call%20%2F%3F%25%23/output",
            )

            respond("""{"tool_call_id":"call /?%#","status":"completed","available":false}""")
            val unavailable = v2Repo.toolDetails("thread /?%#", "call /?%#")

            assertFalse(unavailable.available)
            assertNull(unavailable.format)
            assertNull(unavailable.output)
            assertFalse(unavailable.truncated)
            request(
                "GET",
                "/v2/threads/thread%20%2F%3F%25%23/tool-calls/call%20%2F%3F%25%23/output",
            )
        }
    }

    @Test
    fun `tool details reject malformed payloads invalid ids and v1 access before a request`() {
        runBlocking {
            val v2Repo = HttpZedRepository.v2(
                host = "http://127.0.0.1:${server.port}",
                credential = "fixture-token",
                debug = false,
            )
            respond("""{"tool_call_id":"call","status":"completed","available":true,"format":"markdown","truncated":false}""")
            assertTrue(expectFailure { v2Repo.toolDetails("thread", "call") }.contains("output"))

            respond("""{"error":"removed"}""", 401)
            try {
                v2Repo.toolDetails("thread", "call")
                fail("Expected device authentication failure")
            } catch (_: GatewayAuthenticationException) {
            }

            val requestsBeforeLocalValidation = server.requestCount
            assertTrue(expectFailure { repo.toolDetails("thread", "call") }.contains("API v2"))
            try {
                v2Repo.toolDetails("..", "call")
                fail("Expected invalid thread ID")
            } catch (_: IllegalArgumentException) {
            }
            try {
                v2Repo.toolDetails("thread", "..")
                fail("Expected invalid tool ID")
            } catch (_: IllegalArgumentException) {
            }
            assertEquals(requestsBeforeLocalValidation, server.requestCount)
        }
    }

    @Test
    fun `v2 external agents are listed, created, and configured by desktop-issued ids`() {
        runBlocking {
            val v2Repo = HttpZedRepository.v2(
                host = "http://127.0.0.1:${server.port}",
                credential = "fixture-token",
                debug = false,
            )
            respond("""{"agents":[{"id":"Zed Agent","name":"Zed Agent","is_native":true},{"id":"claude-acp","name":"Claude Agent","is_native":false}]}""")
            val agents = v2Repo.agents("project")
            assertEquals(listOf(true, false), agents.map { it.isNative })
            request("GET", "/v2/projects/project/agents")

            respond("""{"id":"t1","title":"New thread","status":"needs_authentication","agent":{"id":"claude-acp","name":"Claude Agent","is_native":false}}""")
            val created = v2Repo.createThread("project", "claude-acp")
            assertEquals(THREAD_STATUS_NEEDS_AUTHENTICATION, created.status)
            assertEquals("Claude Agent", created.agent?.name)
            request("POST", "/v2/threads", """{"project_id":"project","agent_id":"claude-acp"}""")

            val configuration = """{"agent":{"id":"claude-acp","name":"Claude Agent","is_native":false},"modes":null,"options":[
                {"id":"model","name":"Model","description":null,"category":"model","type":"select","current_value":"sonnet",
                 "values":[{"value":"sonnet","name":"Sonnet","description":null,"group":null},{"value":"opus","name":"Opus","description":"Most capable","group":"Claude"}]},
                {"id":"fast","name":"Fast mode","description":null,"category":null,"type":"boolean","current_value":false},
                {"id":"future","name":"Future","type":"slider","current_value":3}]}"""
            respond(configuration)
            val parsed = v2Repo.configuration("t1")
            assertTrue(parsed.isExternalAgent)
            assertFalse(parsed.thinkingEnabled)
            assertEquals(listOf("model", "fast"), parsed.options.map { it.id })
            assertEquals("Claude", parsed.options.first().values.last().group)
            assertEquals(false, parsed.options.last().currentBoolean)
            request("GET", "/v2/threads/t1/configuration")

            respond(configuration)
            v2Repo.setAgentOption("t1", "model", AgentOptionValue.Choice("opus"))
            request("PUT", "/v2/threads/t1/configuration", """{"config_option":{"id":"model","value":"opus"}}""")
            respond(configuration)
            v2Repo.setAgentOption("t1", "fast", AgentOptionValue.Toggle(true))
            request("PUT", "/v2/threads/t1/configuration", """{"config_option":{"id":"fast","value":true}}""")

            respond("""{"agent":{"id":"gemini","name":"Gemini CLI","is_native":false},"modes":{"current":"default","available":[{"id":"default","name":"Default","description":null},{"id":"yolo","name":"YOLO","description":"Auto-approve"}]},"options":[]}""")
            val modes = requireNotNull(v2Repo.setAgentMode("t2", "yolo").modes)
            assertEquals("default", modes.current)
            assertEquals("Auto-approve", modes.available.last().description)
            request("PUT", "/v2/threads/t2/configuration", """{"mode_id":"yolo"}""")
        }
    }

    @Test
    fun `external agents require v2 before any request`() {
        runBlocking {
            assertTrue(expectFailure { repo.agents("project") }.contains("v2"))
            assertTrue(expectFailure { repo.createThread("project", "claude-acp") }.contains("v2"))
            assertEquals(0, server.requestCount)
        }
    }

    private suspend fun expectFailure(block: suspend () -> Unit): String {
        try {
            block(); fail("Expected GatewayException")
        } catch (e: GatewayException) {
            return e.message.orEmpty()
        }
        error("unreachable")
    }
}
