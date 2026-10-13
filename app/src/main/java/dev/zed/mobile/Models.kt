package dev.zed.mobile

/**
 * A desktop project that the paired phone may select. [isOpen] distinguishes a workspace which
 * is ready for Agent work from a remembered catalog entry which must be opened on the desktop
 * first. [kind] and [connectionName] identify the desktop-managed source; neither is supplied by
 * the phone. [opening] means the desktop is still establishing that source.
 */
data class Project(
    val id: String,
    val name: String,
    val path: String,
    val isOpen: Boolean = true,
    val kind: String = PROJECT_KIND_LOCAL,
    val connectionName: String? = null,
    val opening: Boolean = false,
)

const val PROJECT_KIND_LOCAL = "local"
const val PROJECT_KIND_SSH = "ssh"
const val PROJECT_KIND_WSL = "wsl"
const val PROJECT_KIND_DOCKER = "docker"
const val PROJECT_KIND_PODMAN = "podman"

val PROJECT_KINDS = setOf(
    PROJECT_KIND_LOCAL,
    PROJECT_KIND_SSH,
    PROJECT_KIND_WSL,
    PROJECT_KIND_DOCKER,
    PROJECT_KIND_PODMAN,
)

/** A desktop-configured agent. The phone can only pick from this list; it never names a command. */
data class AgentInfo(val id: String, val name: String, val isNative: Boolean)

data class ThreadSummary(
    val id: String,
    val title: String,
    val status: String,
    /** Null when an older desktop did not report the agent; such threads are always native. */
    val agent: AgentInfo? = null,
    val errorMessage: String? = null,
)

const val THREAD_STATUS_NEEDS_AUTHENTICATION = "needs_authentication"
data class Message(val role: String, val text: String)


data class TranscriptChunk(val kind: String, val text: String)

data class TranscriptEntry(
    val index: Int,
    val kind: String,
    val text: String? = null,
    val chunks: List<TranscriptChunk> = emptyList(),
    val toolCallId: String? = null,
    /** Full tool output is fetched on demand and is never included in a thread snapshot. */
    val detailsAvailable: Boolean = false,
    val title: String? = null,
    val toolName: String? = null,
    val status: String? = null,
    // Kept only to display a snapshot from an older desktop during a rolling upgrade.
    val content: List<String> = emptyList(),
    val rawInput: String? = null,
    val rawOutput: String? = null,
    val planEntries: List<PlanEntry> = emptyList(),
    /** Set on `elicitation` entries so an inline question card can find its pending form. */
    val elicitationId: String? = null,
)

data class PlanEntry(val text: String, val status: String)

/** Bounded, display-only detailed output for one retained desktop tool call. */
data class ToolDetails(
    val toolCallId: String,
    val status: String,
    val available: Boolean,
    val format: String? = null,
    val output: String? = null,
    val truncated: Boolean = false,
)

data class ThreadSnapshot(
    val id: String,
    val title: String,
    val status: String,
    val messages: List<Message>,
    val transcript: List<TranscriptEntry> = emptyList(),
    /** How many older transcript entries exist before the first one in [transcript]. */
    val transcriptOmittedEntries: Int = 0,
    val errorMessage: String? = null,
)

/** One page of older transcript entries, oldest first. */
data class TranscriptPage(val entries: List<TranscriptEntry>, val omittedEntries: Int)

data class ChangedFile(
    val path: String,
    val diff: String,
    val additions: Int,
    val deletions: Int,
    /** True when the diff was too large to embed; page it in with `changedFilePage`. */
    val truncated: Boolean = false,
    /** True while the Agent's edits to this file await Keep or Reject on desktop or phone. */
    val unreviewed: Boolean = false,
)

data class ChangesSnapshot(
    val files: List<ChangedFile>,
    val unreviewedFiles: List<String> = emptyList(),
    val canUndoReject: Boolean = false,
)

data class DiffPage(val path: String, val diff: String, val offset: Int, val nextOffset: Int?, val totalLines: Int)

const val PERMISSION_ALLOW_ONCE = "allow_once"
const val PERMISSION_ALLOW_ALWAYS = "allow_always"
const val PERMISSION_REJECT_ONCE = "reject_once"
const val PERMISSION_REJECT_ALWAYS = "reject_always"

/** A desktop-issued option for a pending tool call. The phone only ever echoes its ID back. */
data class PermissionChoice(
    val optionId: String,
    val label: String,
    val kind: String,
    val enabled: Boolean = true,
) {
    val allows: Boolean get() = kind != PERMISSION_REJECT_ONCE && kind != PERMISSION_REJECT_ALWAYS
    val isAlways: Boolean get() = kind == PERMISSION_ALLOW_ALWAYS || kind == PERMISSION_REJECT_ALWAYS
}

/** A command pattern (for example `cargo test *`) an always-rule can be scoped to. */
data class PermissionPattern(val index: Int, val pattern: String, val displayName: String)

data class ToolApproval(
    val toolCallId: String,
    val title: String,
    val toolName: String?,
    val content: List<String>,
    val rawInput: String?,
    val allowOnceAvailable: Boolean,
    val denyOnceAvailable: Boolean,
    /** `permission` grants or denies the call; `action_choice` picks what the tool does next. */
    val kind: String = "permission",
    val options: List<PermissionChoice> = emptyList(),
    val patterns: List<PermissionPattern> = emptyList(),
    val allowBlockedReason: String? = null,
)

/** The phone's answer to a pending approval: a desktop option, optionally scoped to patterns. */
data class ApprovalDecision(val optionId: String, val patterns: List<Int> = emptyList())

data class ElicitationChoice(val value: String, val label: String, val description: String? = null)

enum class ElicitationFieldType { TEXT, NUMBER, INTEGER, BOOLEAN, SINGLE_SELECT, MULTI_SELECT }

data class ElicitationField(
    val name: String,
    val title: String,
    val description: String?,
    val type: ElicitationFieldType,
    val required: Boolean,
    val choices: List<ElicitationChoice> = emptyList(),
    val defaultText: String? = null,
    val defaultBoolean: Boolean? = null,
    val defaultChoices: List<String> = emptyList(),
)

/** A question the Agent asked. Form questions can be answered on the phone. */
data class Elicitation(
    val id: String,
    val message: String,
    val mode: String,
    val url: String? = null,
    val fields: List<ElicitationField> = emptyList(),
) {
    val answerable: Boolean get() = mode == "form"
}

sealed interface ElicitationValue {
    data class Text(val value: String) : ElicitationValue
    data class Flag(val value: Boolean) : ElicitationValue
    data class Choices(val values: List<String>) : ElicitationValue
}

data class ModelCapability(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val modelName: String,
    val supportsThinking: Boolean,
    val supportsDisablingThinking: Boolean,
    val effortLevels: List<ThinkingEffort>,
)

data class ThinkingEffort(val name: String, val value: String, val isDefault: Boolean)

data class ThreadConfiguration(
    val model: ModelCapability?,
    val thinkingEnabled: Boolean,
    val thinkingEffort: String?,
    val agent: AgentInfo? = null,
    /** External agents only, and only when the agent reports no [options]. */
    val modes: AgentModes? = null,
    /** External agents only: the agent's own settings, such as its model or permission mode. */
    val options: List<AgentConfigOption> = emptyList(),
) {
    val isExternalAgent: Boolean get() = agent?.isNative == false
}

data class AgentMode(val id: String, val name: String, val description: String? = null)

data class AgentModes(val current: String, val available: List<AgentMode>)

data class AgentConfigValue(
    val value: String,
    val name: String,
    val description: String? = null,
    val group: String? = null,
)

data class AgentConfigOption(
    val id: String,
    val name: String,
    val description: String?,
    /** ACP category such as `mode`, `model`, or `thought_level`; null when uncategorized. */
    val category: String?,
    val type: String,
    val currentValue: String? = null,
    val currentBoolean: Boolean? = null,
    val values: List<AgentConfigValue> = emptyList(),
)

const val AGENT_OPTION_SELECT = "select"
const val AGENT_OPTION_BOOLEAN = "boolean"

sealed interface AgentOptionValue {
    data class Choice(val value: String) : AgentOptionValue
    data class Toggle(val value: Boolean) : AgentOptionValue
}

/** A validated, one-time QR pairing ticket. The secret must never be logged or displayed. */
data class PairingTicket(
    val origin: String,
    val ticketId: String,
    val secret: String,
)

/** A per-device bearer credential returned by the v2 pairing claim endpoint. */
data class DeviceCredential(
    val origin: String,
    val deviceId: String,
    val credential: String,
)

interface ZedRepository {
    suspend fun checkStatus()
    suspend fun projects(): List<Project>

    /** Opens a project from the desktop-maintained v2 catalog. It never accepts a phone path. */
    suspend fun openProject(id: String): Project = unsupported("Remote project opening")
    suspend fun threads(projectId: String): List<ThreadSummary>
    suspend fun thread(id: String): ThreadSnapshot
    suspend fun createThread(projectId: String): ThreadSummary

    /** Null [agentId] means the built-in Zed Agent. */
    suspend fun createThread(projectId: String, agentId: String?): ThreadSummary =
        if (agentId == null) createThread(projectId) else unsupported("External agents")

    suspend fun agents(projectId: String): List<AgentInfo> = unsupported("External agents")
    suspend fun send(id: String, text: String)
    suspend fun cancel(id: String)
    suspend fun changes(id: String): List<ChangedFile>
    suspend fun changesSnapshot(id: String): ChangesSnapshot = ChangesSnapshot(changes(id))
    suspend fun changedFilePage(id: String, path: String, offset: Int): DiffPage = unsupported("Paged diffs")

    /** Keeps (accepts) or rejects (reverts) unreviewed Agent edits. Null [paths] means every file. */
    suspend fun reviewChanges(id: String, keep: Boolean, paths: List<String>?) = unsupported<Unit>("Reviewing edits")
    suspend fun undoReject(id: String) = unsupported<Unit>("Reviewing edits")
    suspend fun olderTranscript(id: String, before: Int): TranscriptPage = unsupported("Older transcript entries")
    suspend fun toolDetails(threadId: String, toolCallId: String): ToolDetails =
        unsupported("Tool output details")

    suspend fun models(projectId: String): List<ModelCapability> = unsupported("Model selection")
    suspend fun configuration(id: String): ThreadConfiguration = unsupported("Thread configuration")
    suspend fun updateConfiguration(
        id: String,
        model: ModelCapability? = null,
        thinkingEnabled: Boolean? = null,
        thinkingEffort: String? = null,
        clearThinkingEffort: Boolean = false,
    ): ThreadConfiguration = unsupported("Thread configuration")

    suspend fun setAgentMode(id: String, modeId: String): ThreadConfiguration = unsupported("External agents")
    suspend fun setAgentOption(id: String, optionId: String, value: AgentOptionValue): ThreadConfiguration =
        unsupported("External agents")

    suspend fun approvals(id: String): List<ToolApproval> = unsupported("Tool approvals")
    suspend fun resolveApproval(id: String, toolCallId: String, allow: Boolean) = unsupported<Unit>("Tool approvals")
    suspend fun resolveApproval(id: String, toolCallId: String, decision: ApprovalDecision) =
        unsupported<Unit>("Tool approval options")

    suspend fun elicitations(id: String): List<Elicitation> = unsupported("Agent questions")

    /** [action] is `accept`, `decline` or `cancel`; [values] is used only to accept. */
    suspend fun answerElicitation(
        id: String,
        elicitationId: String,
        action: String,
        values: Map<String, ElicitationValue> = emptyMap(),
    ) = unsupported<Unit>("Agent questions")
    suspend fun revokeCurrentDevice() = unsupported<Unit>("Device revocation")

    private fun <T> unsupported(feature: String): T =
        throw GatewayException("$feature requires Zed-Desktop Mobile Gateway API v2.")
}

/** The unauthenticated, one-time v2 pairing operation. */
interface PairingRepository {
    suspend fun claim(ticket: PairingTicket, deviceLabel: String): DeviceCredential
}

open class GatewayException(message: String) : Exception(message)
class GatewayAuthenticationException(message: String) : GatewayException(message)
class CredentialStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)
