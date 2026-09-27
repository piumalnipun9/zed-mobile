package dev.zed.mobile

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Device-authenticated Zed API client. Its public constructor preserves the v1 host/token flow;
 * use [v2] after a successful QR claim to use the same operations under `/v2`.
 */
class HttpZedRepository private constructor(
    host: String,
    credential: String,
    debug: Boolean,
    private val apiVersion: Int,
    private val client: OkHttpClient,
) : ZedRepository {
    private val origin = HostPolicy.parse(host, debug)
    private val bearer = HostPolicy.token(credential)

    constructor(host: String, token: String, debug: Boolean) : this(
        host = host,
        credential = token,
        debug = debug,
        apiVersion = API_VERSION_V1,
        client = newZedHttpClient(),
    )

    override suspend fun checkStatus() {
        val value = request("GET", listOf("status"))
        if (value.number("api_version") != apiVersion || value.string("name") != "Zed-Desktop") {
            throw GatewayException("Incompatible desktop. Expected Zed-Desktop API v$apiVersion.")
        }
    }

    override suspend fun projects() = request("GET", listOf("projects")).objects("projects").map {
        Project(it.string("id"), it.string("name"), it.string("path"))
    }

    override suspend fun threads(projectId: String) = request("GET", listOf("projects", projectId, "threads"))
        .objects("threads").map { it.summary() }

    override suspend fun thread(id: String): ThreadSnapshot {
        val obj = request("GET", listOf("threads", id))
        return ThreadSnapshot(
            id = obj.string("id"),
            title = obj.string("title"),
            status = obj.string("status"),
            messages = obj.objects("messages").map { Message(it.string("role"), it.string("text")) },
            transcript = obj.optionalObjects("transcript").map { it.transcriptEntry() },
        )
    }

    override suspend fun createThread(projectId: String) = request(
        "POST",
        listOf("threads"),
        JsonObject().apply { addProperty("project_id", projectId) },
    ).summary()

    override suspend fun send(id: String, text: String) {
        accepted(
            request(
                "POST",
                listOf("threads", id, "messages"),
                JsonObject().apply { addProperty("text", text) },
            ),
        )
    }

    override suspend fun cancel(id: String) {
        accepted(request("POST", listOf("threads", id, "cancel")))
    }

    override suspend fun changes(id: String) = request("GET", listOf("threads", id, "changes")).objects("files").map {
        ChangedFile(it.string("path"), it.string("diff"), it.number("additions"), it.number("deletions"))
    }

    override suspend fun models(projectId: String): List<ModelCapability> = request(
        "GET",
        listOf("projects", projectId, "models"),
    ).objects("models").map { it.modelCapability() }

    override suspend fun configuration(id: String): ThreadConfiguration = request(
        "GET",
        listOf("threads", id, "configuration"),
    ).threadConfiguration()

    override suspend fun updateConfiguration(
        id: String,
        model: ModelCapability?,
        thinkingEnabled: Boolean?,
        thinkingEffort: String?,
        clearThinkingEffort: Boolean,
    ): ThreadConfiguration {
        require(model != null || thinkingEnabled != null || thinkingEffort != null || clearThinkingEffort) {
            "Choose a model, thinking setting, or thinking effort first."
        }
        val body = JsonObject().apply {
            model?.let {
                add("model", JsonObject().apply {
                    addProperty("provider_id", it.providerId)
                    addProperty("model_id", it.modelId)
                })
            }
            thinkingEnabled?.let { addProperty("thinking_enabled", it) }
            when {
                thinkingEffort != null -> addProperty("thinking_effort", thinkingEffort)
                clearThinkingEffort -> add("thinking_effort", JsonNull.INSTANCE)
            }
        }
        return request("PUT", listOf("threads", id, "configuration"), body).threadConfiguration()
    }

    override suspend fun approvals(id: String): List<ToolApproval> = request(
        "GET",
        listOf("threads", id, "approvals"),
    ).objects("approvals").map { approval ->
        ToolApproval(
            toolCallId = approval.string("tool_call_id"),
            title = approval.string("title"),
            toolName = approval.optionalString("tool_name"),
            content = approval.optionalStrings("content"),
            rawInput = approval.optionalValue("raw_input"),
            allowOnceAvailable = approval.bool("allow_once_available"),
            denyOnceAvailable = approval.bool("deny_once_available"),
        )
    }

    override suspend fun resolveApproval(id: String, toolCallId: String, allow: Boolean) {
        accepted(
            request(
                "POST",
                listOf("threads", id, "approvals", toolCallId, "resolve"),
                JsonObject().apply { addProperty("decision", if (allow) "allow" else "deny") },
            ),
        )
    }

    override suspend fun revokeCurrentDevice() {
        if (apiVersion != API_VERSION_V2) return
        val response = request("POST", listOf("devices", "current", "revoke"))
        val value = response.get("revoked")
        if (value == null || !value.isJsonPrimitive || !value.asBoolean) {
            throw GatewayException("Desktop did not revoke this device.")
        }
    }

    private fun accepted(obj: JsonObject) {
        val value = obj.get("accepted")
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean || !value.asBoolean) {
            throw GatewayException("Desktop did not accept the request.")
        }
    }

    private suspend fun request(method: String, segments: List<String>, body: JsonObject? = null): JsonObject {
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) {
            "Desktop returned an invalid empty or dot-segment ID."
        }
        require(method in setOf("GET", "POST", "PUT")) { "Unsupported desktop request method." }
        val url = origin.newBuilder().addPathSegment("v$apiVersion").apply {
            segments.forEach { addPathSegment(it) }
        }.build()
        val requestBody = when (method) {
            "POST", "PUT" -> (body?.toString() ?: "{}").toRequestBody(JSON_MEDIA_TYPE)
            else -> null
        }
        val call = client.newCall(
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $bearer")
                .header("Accept", "application/json")
                .method(method, requestBody)
                .build(),
        )
        val rejectedMessage = if (apiVersion == API_VERSION_V1) {
            "Pairing rejected. Check or rotate your desktop token."
        } else {
            "Device credential rejected. Pair this device again."
        }
        return executeJson(call, rejectedMessage, apiVersion == API_VERSION_V2)
    }

    companion object {
        private const val API_VERSION_V1 = 1
        private const val API_VERSION_V2 = 2

        /** Creates a v2 device-authenticated repository from a saved claim response. */
        fun v2(
            credential: DeviceCredential,
            debug: Boolean,
            client: OkHttpClient = newZedHttpClient(),
        ): HttpZedRepository = HttpZedRepository(
            host = credential.origin,
            credential = credential.credential,
            debug = debug,
            apiVersion = API_VERSION_V2,
            client = client,
        )

        /** Kept for callers that do not retain the device ID, such as focused HTTP tests. */
        fun v2(
            host: String,
            credential: String,
            debug: Boolean,
            client: OkHttpClient = newZedHttpClient(),
        ): HttpZedRepository = HttpZedRepository(
            host = host,
            credential = credential,
            debug = debug,
            apiVersion = API_VERSION_V2,
            client = client,
        )
    }
}

/**
 * Unauthenticated one-time pairing client. Only `POST /v2/pair/claim` is sent without a bearer.
 */
class V2PairingRepository(
    origin: String,
    private val debug: Boolean,
    private val client: OkHttpClient = newZedHttpClient(),
) : PairingRepository {
    private val origin: HttpUrl = HostPolicy.parse(origin, debug)

    override suspend fun claim(ticket: PairingTicket, deviceLabel: String): DeviceCredential {
        V2PairingValidation.ticket(ticket, debug)
        require(HostPolicy.parse(ticket.origin, debug) == origin) {
            "The pairing ticket origin does not match the pairing endpoint."
        }
        V2PairingValidation.deviceLabel(deviceLabel)
        val body = JsonObject().apply {
            addProperty("ticket_id", ticket.ticketId)
            addProperty("secret", ticket.secret)
            addProperty("device_label", deviceLabel)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val url = origin.newBuilder()
            .addPathSegment("v2")
            .addPathSegment("pair")
            .addPathSegment("claim")
            .build()
        val response = executeJson(
            client.newCall(
                Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .post(body)
                    .build(),
            ),
            "Pairing ticket rejected or expired.",
            deviceAuthentication = false,
        )
        val credential = DeviceCredential(
            origin = ticket.origin,
            deviceId = response.string("device_id"),
            credential = response.string("credential"),
        )
        V2PairingValidation.deviceCredential(credential, debug)
        return credential
    }
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024

private fun newZedHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    .build()

private suspend fun executeJson(
    call: Call,
    rejectedMessage: String,
    deviceAuthentication: Boolean,
): JsonObject = withContext(Dispatchers.IO) {
    val payload: String = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        GatewayException(
                            "Connection failed. Check Tailscale, host, and TLS. " +
                                    "An action may have reached desktop; refresh before retrying.",
                        ),
                    )
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val text = response.use {
                        val source = it.body?.source() ?: throw GatewayException("Desktop returned an empty response.")
                        if (source.request(MAX_RESPONSE_BYTES + 1)) {
                            throw GatewayException("Response exceeds 4 MiB. Open this thread on desktop.")
                        }
                        val raw = source.readUtf8()
                        if (!it.isSuccessful) throw responseFailure(it.code, raw, rejectedMessage, deviceAuthentication)
                        raw
                    }
                    if (continuation.isActive) continuation.resume(text)
                } catch (error: Exception) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            if (error is GatewayException) error
                            else GatewayException("Unable to read desktop response. Refresh before retrying an action."),
                        )
                    }
                }
            }
        })
    }
    try {
        JsonParser.parseString(payload).asJsonObject
    } catch (_: Exception) {
        throw GatewayException("Desktop returned invalid JSON.")
    }
}

private fun responseFailure(
    code: Int,
    raw: String,
    rejectedMessage: String,
    deviceAuthentication: Boolean,
): GatewayException {
    val detail = runCatching { JsonParser.parseString(raw).asJsonObject.string("error") }.getOrNull()
    val explanation = when (code) {
        401, 403 -> rejectedMessage
        in 300..399 -> "Redirect refused. Pair with the final host URL directly."
        else -> detail?.take(500) ?: "Desktop request failed."
    }
    val message = "HTTP $code: $explanation"
    return if (deviceAuthentication && code in 401..403) {
        GatewayAuthenticationException(message)
    } else {
        GatewayException(message)
    }
}

private fun JsonObject.string(key: String): String {
    val value = get(key)
    if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
        throw GatewayException("Invalid desktop string '$key'.")
    }
    return value.asString
}

private fun JsonObject.optionalString(key: String): String? {
    val value = get(key) ?: return null
    if (value.isJsonNull) return null
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
        throw GatewayException("Invalid desktop string '$key'.")
    }
    return value.asString
}

private fun JsonObject.number(key: String): Int {
    val value = get(key)
    if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
        throw GatewayException("Invalid desktop integer '$key'.")
    }
    return value.asString.toIntOrNull() ?: throw GatewayException("Invalid desktop integer '$key'.")
}

private fun JsonObject.bool(key: String): Boolean {
    val value = get(key)
    if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) {
        throw GatewayException("Invalid desktop boolean '$key'.")
    }
    return value.asBoolean
}

private fun JsonObject.objects(key: String): List<JsonObject> {
    val value = get(key)
    if (value == null || !value.isJsonArray || value.asJsonArray.any { !it.isJsonObject }) {
        throw GatewayException("Invalid desktop array '$key'.")
    }
    return value.asJsonArray.map { it.asJsonObject }
}

private fun JsonObject.optionalObjects(key: String): List<JsonObject> {
    val value = get(key) ?: return emptyList()
    if (!value.isJsonArray || value.asJsonArray.any { !it.isJsonObject }) {
        throw GatewayException("Invalid desktop array '$key'.")
    }
    return value.asJsonArray.map { it.asJsonObject }
}

private fun JsonObject.optionalStrings(key: String): List<String> {
    val value = get(key) ?: return emptyList()
    if (!value.isJsonArray || value.asJsonArray.any { !it.isJsonPrimitive || !it.asJsonPrimitive.isString }) {
        throw GatewayException("Invalid desktop string array '$key'.")
    }
    return value.asJsonArray.map { it.asString }
}

private fun JsonObject.optionalValue(key: String): String? {
    val value = get(key) ?: return null
    return if (value.isJsonNull) null else value.toString()
}

private fun JsonObject.summary() = ThreadSummary(string("id"), string("title"), string("status"))

private fun JsonObject.transcriptEntry(): TranscriptEntry {
    val kind = string("kind")
    val chunks = optionalObjects("chunks").map {
        TranscriptChunk(it.string("kind"), it.string("text"))
    }
    val planEntries = optionalObjects("entries").map {
        PlanEntry(it.string("text"), it.string("status"))
    }
    return TranscriptEntry(
        index = number("index"),
        kind = kind,
        text = optionalString("text"),
        chunks = chunks,
        toolCallId = optionalString("tool_call_id"),
        title = optionalString("title"),
        toolName = optionalString("tool_name"),
        status = optionalString("status"),
        content = optionalStrings("content"),
        rawInput = optionalValue("raw_input"),
        rawOutput = optionalValue("raw_output"),
        planEntries = planEntries,
    )
}

private fun JsonObject.modelCapability(): ModelCapability = ModelCapability(
    providerId = string("provider_id"),
    providerName = string("provider_name"),
    modelId = string("model_id"),
    modelName = string("model_name"),
    supportsThinking = bool("supports_thinking"),
    supportsDisablingThinking = bool("supports_disabling_thinking"),
    effortLevels = objects("effort_levels").map {
        ThinkingEffort(it.string("name"), it.string("value"), it.bool("is_default"))
    },
)

private fun JsonObject.threadConfiguration(): ThreadConfiguration {
    val model = get("model")
    val selectedModel = when {
        model == null || model.isJsonNull -> null
        model.isJsonObject -> model.asJsonObject.modelCapability()
        else -> throw GatewayException("Invalid desktop thread model.")
    }
    return ThreadConfiguration(
        model = selectedModel,
        thinkingEnabled = bool("thinking_enabled"),
        thinkingEffort = optionalString("thinking_effort"),
    )
}
