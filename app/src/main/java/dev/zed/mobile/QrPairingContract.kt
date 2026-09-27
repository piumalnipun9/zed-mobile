package dev.zed.mobile

import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * The v2 QR wire format is `ZEDM2:` followed by unpadded Base64URL-encoded UTF-8 JSON:
 * `{"origin":"https://...","ticket_id":"...","secret":"..."}`.
 */
object QrPairingContract {
    const val PREFIX = "ZEDM2:"
    private const val MAX_ENCODED_BYTES = 16 * 1024

    fun parse(value: String, debug: Boolean): PairingTicket {
        require(value.startsWith(PREFIX)) { "This is not a Zed Mobile v2 pairing QR code." }
        val encoded = value.removePrefix(PREFIX)
        require(encoded.isNotEmpty() && encoded.length <= MAX_ENCODED_BYTES) {
            "The pairing QR code is empty or too large."
        }
        require(encoded.all { it.isAsciiLetterOrDigit() || it == '-' || it == '_' }) {
            "The pairing QR code must use unpadded Base64URL data."
        }
        require(encoded.length % 4 != 1) { "The pairing QR code has invalid Base64URL data." }

        val bytes = try {
            Base64.getUrlDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("The pairing QR code has invalid Base64URL data.")
        }
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == encoded) {
            "The pairing QR code has non-canonical Base64URL data."
        }
        val fields = readFields(decodeUtf8(bytes))
        require(fields.keys == REQUIRED_FIELDS) { "The pairing QR code must contain only origin, ticket_id, and secret." }

        val ticket = PairingTicket(
            origin = requireNotNull(fields["origin"]),
            ticketId = requireNotNull(fields["ticket_id"]),
            secret = requireNotNull(fields["secret"]),
        )
        V2PairingValidation.ticket(ticket, debug)
        return ticket
    }

    /** Useful for tests and tooling that generate a QR code; mobile only consumes this format. */
    fun encode(ticket: PairingTicket, debug: Boolean): String {
        V2PairingValidation.ticket(ticket, debug)
        val objectValue = JsonObject().apply {
            addProperty("origin", ticket.origin)
            addProperty("ticket_id", ticket.ticketId)
            addProperty("secret", ticket.secret)
        }
        return PREFIX + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(objectValue.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun readFields(json: String): Map<String, String> = try {
        JsonReader(StringReader(json)).use { reader ->
            reader.isLenient = false
            require(reader.peek() == JsonToken.BEGIN_OBJECT) { "The pairing QR payload must be a JSON object." }
            val fields = linkedMapOf<String, String>()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                require(name in REQUIRED_FIELDS && name !in fields) { "The pairing QR code has invalid JSON fields." }
                require(reader.peek() == JsonToken.STRING) { "The pairing QR code fields must be strings." }
                fields[name] = reader.nextString()
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT) { "The pairing QR payload has trailing data." }
            fields
        }
    } catch (error: IllegalArgumentException) {
        throw error
    } catch (_: Exception) {
        throw IllegalArgumentException("The pairing QR payload is not valid JSON.")
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        throw IllegalArgumentException("The pairing QR payload is not valid UTF-8.")
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private val REQUIRED_FIELDS = setOf("origin", "ticket_id", "secret")
}

/** Shared validation for QR payloads, pairing requests, and stored v2 credentials. */
internal object V2PairingValidation {
    private const val MAX_ORIGIN_LENGTH = 2_048
    private const val MAX_TICKET_ID_LENGTH = 1_024
    private const val MAX_SECRET_LENGTH = 4_096
    private const val MAX_DEVICE_ID_LENGTH = 1_024
    private const val MAX_CREDENTIAL_LENGTH = 4_096
    private const val MAX_DEVICE_LABEL_LENGTH = 128

    fun ticket(ticket: PairingTicket, debug: Boolean) {
        origin(ticket.origin, debug)
        opaque(ticket.ticketId, "ticket_id", MAX_TICKET_ID_LENGTH)
        opaque(ticket.secret, "secret", MAX_SECRET_LENGTH)
    }

    fun deviceCredential(credential: DeviceCredential, debug: Boolean) {
        origin(credential.origin, debug)
        opaque(credential.deviceId, "device_id", MAX_DEVICE_ID_LENGTH)
        opaque(credential.credential, "credential", MAX_CREDENTIAL_LENGTH)
    }

    fun deviceLabel(value: String): String {
        require(value.length in 1..MAX_DEVICE_LABEL_LENGTH && value.all { it.code !in 0..0x1f && it.code != 0x7f }) {
            "Device label must be 1–$MAX_DEVICE_LABEL_LENGTH visible characters without control characters."
        }
        return value
    }

    private fun origin(value: String, debug: Boolean) {
        require(value.length in 1..MAX_ORIGIN_LENGTH) { "origin must be at most $MAX_ORIGIN_LENGTH characters." }
        HostPolicy.parse(value, debug)
    }

    private fun opaque(value: String, field: String, maximumLength: Int): String {
        require(value.length in 1..maximumLength && value.all { it.code in 0x21..0x7e }) {
            "$field must be 1–$maximumLength printable ASCII characters without whitespace."
        }
        return value
    }
}
