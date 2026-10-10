package dev.zed.mobile

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.EOFException
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource

/** A validated, ephemeral completion notification from the paired desktop. */
data class AgentCompletionEvent(
    val type: AgentCompletionEventType,
    val projectId: String,
    /** Bounded desktop-derived label used only for a private notification display. */
    val projectName: String,
    val threadId: String,
    /** Bounded desktop-derived label used only for a private notification display. */
    val threadTitle: String,
    val turnId: String,
)

enum class AgentCompletionEventType {
    COMPLETED,
    FAILED,
}

/**
 * Authenticated SSE transport for `/v2/events`.
 *
 * This deliberately does not retain SSE IDs, retry instructions, or event payloads. A new
 * connection therefore only receives future desktop events; it cannot ask the gateway to replay
 * events missed while the phone was offline.
 */
class GatewayEventClient(
    credential: DeviceCredential,
    debug: Boolean,
    private val client: OkHttpClient = newGatewayEventHttpClient(),
) {
    private val origin: HttpUrl = HostPolicy.parse(credential.origin, debug)
    private val bearer: String = HostPolicy.token(credential.credential)

    suspend fun collect(onEvent: (AgentCompletionEvent) -> Unit) {
        val request = Request.Builder()
            .url(
                origin.newBuilder()
                    .addPathSegment("v2")
                    .addPathSegment("events")
                    .build(),
            )
            .header("Authorization", "Bearer $bearer")
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            // Do not add Origin or Last-Event-ID: this is a direct, non-replayable API client.
            .get()
            .build()
        collect(client.newCall(request), onEvent)
    }

    private suspend fun collect(call: Call, onEvent: (AgentCompletionEvent) -> Unit) {
        suspendCancellableCoroutine<Unit> { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            GatewayEventException("Unable to connect to desktop events."),
                        )
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use { checkedResponse ->
                            validateResponse(checkedResponse)
                            val source = checkedResponse.body?.source()
                                ?: throw GatewayEventException("Desktop returned an empty event stream.")
                            readEventStream(source, onEvent)
                        }
                        if (continuation.isActive) continuation.resume(Unit)
                    } catch (error: Exception) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                if (error is GatewayEventException) error
                                else GatewayEventException("Unable to read desktop events."),
                            )
                        }
                    }
                }
            })
        }
    }

    private fun validateResponse(response: Response) {
        when (response.code) {
            401, 403 -> throw GatewayEventAuthenticationException("Device credential rejected.")
            in 300..399 -> throw GatewayEventException("Desktop event connection redirect refused.")
        }
        if (!response.isSuccessful) {
            throw GatewayEventException("Desktop event connection failed (HTTP ${response.code}).")
        }
        val contentType = response.body?.contentType()
        if (contentType?.type != "text" || contentType.subtype != "event-stream") {
            throw GatewayEventException("Desktop returned an invalid event stream.")
        }
    }
}

/** The direct event client never permits OkHttp to retry or redirect a stream automatically. */
private fun newGatewayEventHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .callTimeout(0, TimeUnit.MILLISECONDS)
    .build()

private fun readEventStream(source: BufferedSource, onEvent: (AgentCompletionEvent) -> Unit) {
    val event = SseEventAccumulator()
    while (true) {
        val line = readBoundedSseLine(source) ?: return
        if (line.isEmpty()) {
            event.dispatch(onEvent)
            event.reset()
        } else if (!line.startsWith(':')) {
            event.add(line)
        }
    }
}

/** Reads one wire line without ever accumulating an unbounded string from an untrusted peer. */
private fun readBoundedSseLine(source: BufferedSource): String? {
    val line = Buffer()
    var bytes = 0
    while (!source.exhausted()) {
        val byte = source.readByte()
        if (byte == LF) {
            var value = line.readString(StandardCharsets.UTF_8)
            if (value.endsWith('\r')) value = value.dropLast(1)
            return value
        }
        if (bytes >= MAX_SSE_LINE_BYTES) {
            throw GatewayEventProtocolException("Desktop event stream line is too large.")
        }
        line.writeByte(byte.toInt())
        bytes++
    }
    if (bytes == 0) return null
    throw EOFException("Desktop event stream ended mid-line.")
}

private class SseEventAccumulator {
    private var eventName: String? = null
    private val data = StringBuilder()
    private var eventBytes = 0
    private var dataBytes = 0

    fun add(line: String) {
        val lineBytes = line.toByteArray(StandardCharsets.UTF_8).size + 1
        eventBytes += lineBytes
        if (eventBytes > MAX_SSE_EVENT_BYTES) {
            throw GatewayEventProtocolException("Desktop event stream event is too large.")
        }

        val colon = line.indexOf(':')
        val field = if (colon >= 0) line.substring(0, colon) else line
        var value = if (colon >= 0) line.substring(colon + 1) else ""
        if (value.startsWith(' ')) value = value.drop(1)

        when (field) {
            "event" -> {
                if (value.toByteArray(StandardCharsets.UTF_8).size > MAX_SSE_EVENT_NAME_BYTES) {
                    throw GatewayEventProtocolException("Desktop event name is too large.")
                }
                eventName = value
            }

            "data" -> {
                val valueBytes = value.toByteArray(StandardCharsets.UTF_8).size + 1
                dataBytes += valueBytes
                if (dataBytes > MAX_SSE_DATA_BYTES) {
                    throw GatewayEventProtocolException("Desktop event data is too large.")
                }
                data.append(value).append('\n')
            }
        }
    }

    fun dispatch(onEvent: (AgentCompletionEvent) -> Unit) {
        if (data.isEmpty()) return
        val payload = data.toString().dropLast(1)
        parseAgentCompletionEvent(eventName, payload)?.let(onEvent)
    }

    fun reset() {
        eventName = null
        data.clear()
        eventBytes = 0
        dataBytes = 0
    }
}

/**
 * Parses the exact v2 event envelope: the completion kind is the SSE `event:` name, while the
 * JSON `data:` object contains opaque IDs plus bounded, desktop-derived project/thread labels
 * intended only for the private Android notification display.
 */
private fun parseAgentCompletionEvent(eventName: String?, payload: String): AgentCompletionEvent? {
    val type = agentCompletionEventTypeFromSseName(eventName) ?: return null
    val value = try {
        JsonParser.parseString(payload)
    } catch (_: Exception) {
        return null
    }
    if (!value.isJsonObject) return null
    val objectValue = value.asJsonObject
    if (objectValue.keySet() != COMPLETION_EVENT_FIELDS) return null

    val projectId = objectValue.requiredEventString("project_id") ?: return null
    val projectName = objectValue.requiredEventLabel("project_name") ?: return null
    val threadId = objectValue.requiredEventString("thread_id") ?: return null
    val threadTitle = objectValue.requiredEventLabel("thread_title") ?: return null
    val turnId = objectValue.requiredEventString("turn_id") ?: return null
    return AgentCompletionEvent(type, projectId, projectName, threadId, threadTitle, turnId)
}

private fun JsonObject.requiredEventString(name: String): String? {
    val value = get(name) ?: return null
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
    val string = value.asString
    if (string.isEmpty() || string.toByteArray(StandardCharsets.UTF_8).size > MAX_SSE_IDENTIFIER_BYTES) return null
    return string
}

private fun JsonObject.requiredEventLabel(name: String): String? {
    val value = get(name) ?: return null
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
    val string = value.asString
    if (
        string.isBlank() ||
        string.any { character -> character.code < 0x20 || character.code == 0x7f } ||
        string.toByteArray(StandardCharsets.UTF_8).size > MAX_SSE_LABEL_BYTES
    ) {
        return null
    }
    return string
}

private fun agentCompletionEventTypeFromSseName(name: String?): AgentCompletionEventType? = when (name) {
    "thread_completed" -> AgentCompletionEventType.COMPLETED
    "thread_failed" -> AgentCompletionEventType.FAILED
    else -> null
}

open class GatewayEventException(message: String) : Exception(message)
class GatewayEventAuthenticationException(message: String) : GatewayEventException(message)
private class GatewayEventProtocolException(message: String) : GatewayEventException(message)

private const val MAX_SSE_LINE_BYTES = 16 * 1024
private const val MAX_SSE_EVENT_BYTES = 64 * 1024
private const val MAX_SSE_DATA_BYTES = 64 * 1024
private const val MAX_SSE_EVENT_NAME_BYTES = 128
private const val MAX_SSE_IDENTIFIER_BYTES = 4 * 1024
private const val MAX_SSE_LABEL_BYTES = 256
private val COMPLETION_EVENT_FIELDS = setOf(
    "project_id",
    "project_name",
    "thread_id",
    "thread_title",
    "turn_id",
)
private const val LF: Byte = 0x0a
