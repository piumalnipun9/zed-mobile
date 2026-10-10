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

/** v2 contract for desktop approval options, Agent questions, edit review and paging. */
class InteractiveApprovalsTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: HttpZedRepository

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        repo = HttpZedRepository.v2("http://127.0.0.1:${server.port}", "fixture-credential", false)
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
        if (body != null) assertEquals(JsonParser.parseString(body), JsonParser.parseString(recorded.body.readUtf8()))
    }

    @Test
    fun approvalsExposeEveryDesktopOptionAndSendOnlyItsId() = runBlocking {
        respond(
            """{"approvals":[{"tool_call_id":"call-1","title":"Run cargo test","tool_name":"terminal",
               "content":[],"raw_input":null,"kind":"permission",
               "options":[
                 {"option_id":"allow","label":"Allow once","kind":"allow_once","enabled":true},
                 {"option_id":"always","label":"Always allow","kind":"allow_always","enabled":true},
                 {"option_id":"deny","label":"Deny","kind":"reject_once","enabled":true}],
               "patterns":[{"index":0,"pattern":"cargo test *","display_name":"cargo test"}],
               "allow_blocked_reason":null,
               "allow_once_available":true,"deny_once_available":true}]}""",
        )
        val approval = repo.approvals("thread-1").single()
        request("GET", "/v2/threads/thread-1/approvals")
        assertEquals(listOf("allow", "always", "deny"), approval.options.map { it.optionId })
        assertTrue(approval.options[1].isAlways)
        assertFalse(approval.options[2].allows)
        assertEquals("cargo test", approval.patterns.single().displayName)

        respond("""{"accepted":true}""")
        repo.resolveApproval("thread-1", "call-1", ApprovalDecision("always", listOf(0)))
        request(
            "POST",
            "/v2/threads/thread-1/approvals/call-1/resolve",
            """{"option_id":"always","patterns":[0]}""",
        )
    }

    @Test
    fun olderDesktopsWithoutOptionsStillParse() = runBlocking {
        respond(
            """{"approvals":[{"tool_call_id":"call-1","title":"Edit","content":[],
               "allow_once_available":true,"deny_once_available":false}]}""",
        )
        val approval = repo.approvals("thread-1").single()
        assertTrue(approval.options.isEmpty())
        assertEquals("permission", approval.kind)
    }

    @Test
    fun questionsParseSchemaAndSendTypedAnswers() = runBlocking {
        respond(
            """{"elicitations":[{"elicitation_id":"q-1","message":"How should invalid escapes behave?",
               "status":"pending","mode":"form","schema":{"type":"object","required":["strategy"],
               "properties":{
                 "strategy":{"type":"string","title":"Strategy","oneOf":[
                   {"const":"fail","title":"Fail fast"},{"const":"verbatim","title":"Verbatim fallback"}]},
                 "note":{"type":"string","title":"Note"},
                 "retries":{"type":"integer","default":2},
                 "dry_run":{"type":"boolean","default":true},
                 "targets":{"type":"array","items":{"anyOf":[
                   {"const":"linux","title":"Linux"},{"const":"mac","title":"macOS"}]}}}}}]}""",
        )
        val question = repo.elicitations("thread-1").single()
        request("GET", "/v2/threads/thread-1/elicitations")
        val fields = question.fields.associateBy { it.name }
        assertEquals(ElicitationFieldType.SINGLE_SELECT, fields.getValue("strategy").type)
        assertTrue(fields.getValue("strategy").required)
        assertEquals(listOf("Fail fast", "Verbatim fallback"), fields.getValue("strategy").choices.map { it.label })
        assertEquals(ElicitationFieldType.TEXT, fields.getValue("note").type)
        assertEquals("2", fields.getValue("retries").defaultText)
        assertEquals(true, fields.getValue("dry_run").defaultBoolean)
        assertEquals(ElicitationFieldType.MULTI_SELECT, fields.getValue("targets").type)

        respond("""{"accepted":true}""")
        repo.answerElicitation(
            "thread-1",
            "q-1",
            "accept",
            mapOf(
                "strategy" to ElicitationValue.Text("fail"),
                "dry_run" to ElicitationValue.Flag(false),
                "targets" to ElicitationValue.Choices(listOf("linux")),
            ),
        )
        request(
            "POST",
            "/v2/threads/thread-1/elicitations/q-1/respond",
            """{"action":"accept","content":{"strategy":"fail","dry_run":false,"targets":["linux"]}}""",
        )

        respond("""{"accepted":true}""")
        repo.answerElicitation("thread-1", "q-1", "decline")
        request("POST", "/v2/threads/thread-1/elicitations/q-1/respond", """{"action":"decline"}""")
    }

    @Test
    fun reviewAndPagingUseTheDesktopReportedPaths() = runBlocking {
        respond(
            """{"files":[{"path":"/w/a.rs","diff":"","diff_bytes":9000000,"additions":3,"deletions":1,
               "truncated":true,"unreviewed":true}],"truncated":true,
               "review":{"unreviewed_files":["/w/a.rs"],"can_undo_reject":false}}""",
        )
        val changes = repo.changesSnapshot("thread-1")
        request("GET", "/v2/threads/thread-1/changes")
        assertTrue(changes.files.single().truncated)
        assertEquals(listOf("/w/a.rs"), changes.unreviewedFiles)

        respond("""{"path":"/w/a.rs","diff":"@@ -1 +1 @@\n-a\n+b\n","offset":0,"next_offset":3,"total_lines":9,"additions":3,"deletions":1}""")
        val page = repo.changedFilePage("thread-1", "/w/a.rs", 0)
        request("GET", "/v2/threads/thread-1/changes/file?path=%2Fw%2Fa.rs&offset=0")
        assertEquals(3, page.nextOffset)

        respond("""{"accepted":true,"files":1}""")
        repo.reviewChanges("thread-1", keep = false, paths = listOf("/w/a.rs"))
        request("POST", "/v2/threads/thread-1/changes/review", """{"action":"reject","paths":["/w/a.rs"]}""")

        respond("""{"accepted":true,"files":1}""")
        repo.reviewChanges("thread-1", keep = true, paths = null)
        request("POST", "/v2/threads/thread-1/changes/review", """{"action":"keep"}""")

        respond("""{"accepted":true}""")
        repo.undoReject("thread-1")
        request("POST", "/v2/threads/thread-1/changes/undo-reject", "{}")

        respond("""{"entries":[{"index":4,"kind":"user_message","text":"older"}],"omitted_entries":4,"has_more":true,"total_entries":300}""")
        val older = repo.olderTranscript("thread-1", before = 5)
        request("GET", "/v2/threads/thread-1/transcript?before=5&limit=200")
        assertEquals(4, older.omittedEntries)
        assertEquals("older", older.entries.single().text)
    }
}
