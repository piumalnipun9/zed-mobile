package dev.zed.mobile

data class Project(val id: String, val name: String, val path: String)
data class ThreadSummary(val id: String, val title: String, val status: String)
data class Message(val role: String, val text: String)

data class TranscriptChunk(val kind: String, val text: String)

data class TranscriptEntry(
    val index: Int,
    val kind: String,
    val text: String? = null,
    val chunks: List<TranscriptChunk> = emptyList(),
    val toolCallId: String? = null,
    val title: String? = null,
    val toolName: String? = null,
    val status: String? = null,
    val content: List<String> = emptyList(),
    val rawInput: String? = null,
    val rawOutput: String? = null,
    val planEntries: List<PlanEntry> = emptyList(),
)

data class PlanEntry(val text: String, val status: String)

data class ThreadSnapshot(
    val id: String,
    val title: String,
    val status: String,
    val messages: List<Message>,
    val transcript: List<TranscriptEntry> = emptyList(),
)

data class ChangedFile(val path: String, val diff: String, val additions: Int, val deletions: Int)

data class ToolApproval(
    val toolCallId: String,
    val title: String,
    val toolName: String?,
    val content: List<String>,
    val rawInput: String?,
    val allowOnceAvailable: Boolean,
    val denyOnceAvailable: Boolean,
)

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
)

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
    suspend fun threads(projectId: String): List<ThreadSummary>
    suspend fun thread(id: String): ThreadSnapshot
    suspend fun createThread(projectId: String): ThreadSummary
    suspend fun send(id: String, text: String)
    suspend fun cancel(id: String)
    suspend fun changes(id: String): List<ChangedFile>

    suspend fun models(projectId: String): List<ModelCapability> = unsupported("Model selection")
    suspend fun configuration(id: String): ThreadConfiguration = unsupported("Thread configuration")
    suspend fun updateConfiguration(
        id: String,
        model: ModelCapability? = null,
        thinkingEnabled: Boolean? = null,
        thinkingEffort: String? = null,
        clearThinkingEffort: Boolean = false,
    ): ThreadConfiguration = unsupported("Thread configuration")

    suspend fun approvals(id: String): List<ToolApproval> = unsupported("Tool approvals")
    suspend fun resolveApproval(id: String, toolCallId: String, allow: Boolean) = unsupported<Unit>("Tool approvals")
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
