package dev.zed.mobile

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders real app screens from fixed states to PNGs in `app/build/outputs/roborazzi` with no
 * emulator or desktop: `./gradlew recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val vm = ZedViewModel(
        connect = { _, _ -> error("Screenshots never contact a desktop") },
        connectV2 = { error("Screenshots never contact a desktop") },
        pairingRepository = { error("Screenshots never contact a desktop") },
        credentialStore = InMemoryCredentialStore(),
        projectCatalogStore = InMemoryProjectCatalogStore(),
    )

    private val project = Project("p1", "zed", "/home/dev/zed")
    private val thread = ThreadSummary("t1", "Streaming parser for LSP", "waiting_for_approval")

    private fun render(name: String, state: MobileState) {
        compose.setContent { ZedTheme { ZedAppContent(state = state, vm = vm) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun projects() = render(
        "01_projects",
        MobileState(
            page = Page.PROJECTS,
            host = "100.99.221.90:7331",
            hasPersistentPairing = true,
            projects = listOf(
                project,
                Project("p2", "tonic", "/work/rpc/tonic"),
                Project("p3", "anthropic-sdk", "/dev/ai/sdk", isOpen = false, kind = PROJECT_KIND_SSH, connectionName = "arch-box"),
            ),
        ),
    )

    @Test
    @Config(qualifiers = "w412dp-h1500dp-xxhdpi")
    fun chatWithApprovalOptions() = render(
        "02_chat_approval_options",
        chatState(
            transcript = listOf(
                TranscriptEntry(0, "user_message", text = "Fix multi-line string templates and run the tests."),
                TranscriptEntry(
                    1, "assistant_message",
                    chunks = listOf(TranscriptChunk("message", "The lexer now accumulates multi-line spans. Running the editor tests next.")),
                ),
                TranscriptEntry(2, "tool_call", toolCallId = "call-1", title = "Run `cargo test -p editor`", toolName = "terminal", status = "waiting_for_approval"),
            ),
            approvals = listOf(
                ToolApproval(
                    toolCallId = "call-1",
                    title = "Run `cargo test -p editor`",
                    toolName = "terminal",
                    content = emptyList(),
                    rawInput = null,
                    allowOnceAvailable = true,
                    denyOnceAvailable = true,
                    options = listOf(
                        PermissionChoice("allow", "Allow once", PERMISSION_ALLOW_ONCE),
                        PermissionChoice("always", "Always allow `cargo test`", PERMISSION_ALLOW_ALWAYS),
                        PermissionChoice("deny", "Deny", PERMISSION_REJECT_ONCE),
                        PermissionChoice("never", "Always deny", PERMISSION_REJECT_ALWAYS),
                    ),
                    patterns = listOf(
                        PermissionPattern(0, "cargo test *", "cargo test *"),
                        PermissionPattern(1, "cargo *", "cargo *"),
                    ),
                ),
            ),
        ),
    )

    @Test
    @Config(qualifiers = "w412dp-h1200dp-xxhdpi")
    fun chatWithQuestion() = render(
        "03_chat_question",
        chatState(
            transcript = listOf(
                TranscriptEntry(0, "user_message", text = "Handle invalid escape sequences."),
                TranscriptEntry(1, "elicitation", text = "How should invalid escapes behave?", elicitationId = "q-1"),
            ),
            elicitations = listOf(
                Elicitation(
                    id = "q-1",
                    message = "Should invalid escape sequences fail fast, or fall back to a verbatim literal?",
                    mode = "form",
                    fields = listOf(
                        ElicitationField(
                            "strategy", "Strategy", null, ElicitationFieldType.SINGLE_SELECT, required = true,
                            choices = listOf(
                                ElicitationChoice("fail", "Fail fast", "Emit a compile diagnostic"),
                                ElicitationChoice("verbatim", "Verbatim fallback", "Keep the raw text"),
                            ),
                        ),
                        ElicitationField("targets", "Apply to", null, ElicitationFieldType.MULTI_SELECT, required = false,
                            choices = listOf(ElicitationChoice("rust", "Rust"), ElicitationChoice("toml", "TOML"))),
                        ElicitationField("tests", "Add regression tests", null, ElicitationFieldType.BOOLEAN, required = false, defaultBoolean = true),
                        ElicitationField("note", "Anything else?", "Optional guidance for the Agent", ElicitationFieldType.TEXT, required = false),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun changesReview() = render(
        "04_changes_review",
        MobileState(
            page = Page.CHANGES,
            host = "100.99.221.90:7331",
            project = project,
            selectedThread = thread,
            files = listOf(
                ChangedFile(
                    "/home/dev/zed/crates/editor/src/syntax.rs",
                    "@@ -142,4 +142,6 @@ impl Lexer {\n-    quote_count == 1 => self.emit_single_line_str(),\n+    quote_count >= 1 => {\n+        self.accumulate_multiline_spans(quote_count)\n+    }\n     _ => self.fallback_raw(),",
                    3, 1, unreviewed = true,
                ),
                ChangedFile("/home/dev/zed/crates/editor/src/lexer_tests.rs", "", 412, 0, truncated = true, unreviewed = true),
                ChangedFile("/home/dev/zed/Cargo.lock", "@@ -1 +1 @@\n-old\n+new", 1, 1),
            ),
            review = ReviewState(
                unreviewedFiles = listOf(
                    "/home/dev/zed/crates/editor/src/lexer_tests.rs",
                    "/home/dev/zed/crates/editor/src/syntax.rs",
                ),
                canUndoReject = true,
            ),
        ),
    )

    @Test
    fun chatWaitingForFirstOutput() = render(
        "02b_chat_thinking",
        chatState(
            transcript = listOf(
                TranscriptEntry(0, "user_message", text = "What does the gateway do when a phone sends an unknown query parameter?"),
                TranscriptEntry(1, "assistant_message", chunks = listOf(TranscriptChunk("message", "It rejects the request with HTTP 400 instead of ignoring it, so credentials can never ride along in a URL."))),
            ),
            phase = TurnPhase.SENDING,
        ).let { it.copy(composer = it.composer.copy(pendingMessage = PendingMessage("And what happens to the open SSE stream?", PendingMessageStatus.SENT, localId = 1))) },
    )

    @Test
    fun pairing() = render("00_pair", MobileState(page = Page.PAIR))

    @Test
    fun threads() = render(
        "01b_threads",
        MobileState(
            page = Page.THREADS,
            host = "100.99.221.90:7331",
            project = project,
            threads = listOf(
                thread,
                ThreadSummary("t2", "Fix race condition in terminal PTY reader", "running"),
                ThreadSummary("t3", "Refactor tree-sitter highlight queries", "completed"),
                ThreadSummary("t4", "Investigate Wayland fractional scaling", "idle"),
            ),
        ),
    )

    private val claude = AgentInfo("claude-acp", "Claude Agent", isNative = false)
    private val agents = listOf(AgentInfo("Zed Agent", "Zed Agent", isNative = true), claude, AgentInfo("gemini", "Gemini CLI", isNative = false))

    /** The agent picker is a bottom sheet in its own window, so this captures every window. */
    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun threadsWithExternalAgents() {
        compose.setContent {
            ZedTheme {
                ZedAppContent(
                    state = MobileState(
                        page = Page.THREADS,
                        host = "100.99.221.90:7331",
                        project = project,
                        agents = agents,
                        agentPickerVisible = true,
                        threads = listOf(
                            thread,
                            ThreadSummary("t2", "Port the SSE client to OkHttp", "running", agent = claude),
                            ThreadSummary("t3", "Review the gateway threat model", THREAD_STATUS_NEEDS_AUTHENTICATION, agent = agents[2]),
                        ),
                    ),
                    vm = vm,
                )
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/01c_threads_agent_picker.png")
    }

    @Test
    fun externalAgentNeedsSignIn() = render(
        "02c_chat_external_sign_in",
        chatState(transcript = emptyList(), phase = TurnPhase.IDLE).let { state ->
            state.copy(
                selectedThread = ThreadSummary("t3", "Review the gateway threat model", THREAD_STATUS_NEEDS_AUTHENTICATION, agent = claude),
                snapshot = state.snapshot?.copy(status = THREAD_STATUS_NEEDS_AUTHENTICATION),
                configurationState = ConfigurationState(),
            )
        },
    )

    @Test
    fun settings() = render(
        "05_settings",
        MobileState(page = Page.SETTINGS, host = "100.99.221.90:7331", project = project, selectedThread = thread),
    )

    @Test
    @Config(qualifiers = "w412dp-h1300dp-xxhdpi")
    fun chatConversation() = render(
        "02a_chat_conversation",
        chatState(
            transcript = listOf(
                TranscriptEntry(0, "user_message", text = "The syntax highlighter breaks on multi-line string templates in Rust. Can you fix it and run the tests?"),
                TranscriptEntry(
                    1, "assistant_message",
                    chunks = listOf(
                        TranscriptChunk("thought", "The lexer emits a token per line; I need to keep state across newlines."),
                        TranscriptChunk("message", "I'll look at how the lexer handles string literals first."),
                    ),
                ),
                TranscriptEntry(2, "tool_call", toolCallId = "c1", title = "Read crates/editor/src/syntax.rs", toolName = "read_file", status = "completed"),
                TranscriptEntry(3, "tool_call", toolCallId = "c2", title = "Search for `emit_single_line_str`", toolName = "grep", status = "completed"),
                TranscriptEntry(
                    4, "plan",
                    planEntries = listOf(
                        PlanEntry("Track open quotes across newlines", "completed"),
                        PlanEntry("Emit a single multi-line token", "in_progress"),
                        PlanEntry("Add regression tests", "pending"),
                    ),
                ),
                TranscriptEntry(
                    5, "assistant_message",
                    chunks = listOf(TranscriptChunk("message", "The lexer now keeps quote state across newlines, so a template spanning several lines becomes one token. Running the editor tests next.")),
                ),
                TranscriptEntry(6, "tool_call", toolCallId = "c3", title = "Edit crates/editor/src/syntax.rs", toolName = "edit_file", status = "completed"),
                TranscriptEntry(7, "tool_call", toolCallId = "c4", title = "Run `cargo test -p editor`", toolName = "terminal", status = "running"),
            ),
            phase = TurnPhase.RUNNING,
        ),
    )

    private fun chatState(
        transcript: List<TranscriptEntry>,
        approvals: List<ToolApproval> = emptyList(),
        elicitations: List<Elicitation> = emptyList(),
        phase: TurnPhase = TurnPhase.AWAITING_APPROVAL,
    ) = MobileState(
        page = Page.CHAT,
        host = "100.99.221.90:7331",
        project = project,
        selectedThread = thread,
        snapshot = ThreadSnapshot(
            id = thread.id,
            title = thread.title,
            status = "waiting_for_approval",
            messages = emptyList(),
            transcript = transcript,
            transcriptOmittedEntries = 180,
        ),
        turn = ChatTurnState(phase = phase),
        configurationState = ConfigurationState(
            configuration = ThreadConfiguration(
                model = ModelCapability("anthropic", "Anthropic", "claude-sonnet", "Claude Sonnet 5.5", true, true, emptyList()),
                thinkingEnabled = true,
                thinkingEffort = null,
            ),
        ),
        approval = ApprovalState(approvals = approvals),
        questions = QuestionState(elicitations = elicitations),
    )
}
