package dev.zed.mobile

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class QrPairingContractTest {
    @Test
    fun `encode and parse round trip a valid v2 ticket`() {
        val ticket = PairingTicket(
            origin = "https://zed.example:7443",
            ticketId = "ticket-123",
            secret = "secret-ABC_123",
        )

        val parsed = QrPairingContract.parse(QrPairingContract.encode(ticket, debug = false), debug = false)

        assertEquals(ticket, parsed)
    }

    @Test
    fun `parse rejects malformed QR values`() {
        assertInvalid(
            QrPairingContract.PREFIX,
            "The pairing QR code is empty or too large.",
        )
        assertInvalid(
            "${QrPairingContract.PREFIX}YWJj=",
            "The pairing QR code must use unpadded Base64URL data.",
        )
        assertInvalid(
            qrFor("""{"origin":"https://zed.example","ticket_id":"ticket","secret":"secret","extra":"value"}"""),
            "The pairing QR code has invalid JSON fields.",
        )
        assertInvalid(
            qrFor("""{"origin":"https://zed.example","ticket_id":1,"secret":"secret"}"""),
            "The pairing QR code fields must be strings.",
        )
        assertInvalid(
            qrFor("""{"origin":"https://zed.example","ticket_id":"ticket","secret":"contains space"}"""),
            "secret must be 1–4096 printable ASCII characters without whitespace.",
        )
    }

    private fun assertInvalid(value: String, message: String) {
        val error = assertThrows(IllegalArgumentException::class.java) {
            QrPairingContract.parse(value, debug = false)
        }

        assertEquals(message, error.message)
    }

    private fun qrFor(json: String): String = QrPairingContract.PREFIX +
            Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(StandardCharsets.UTF_8))
}
