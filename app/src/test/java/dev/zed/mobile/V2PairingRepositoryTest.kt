package dev.zed.mobile

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class V2PairingRepositoryTest {
    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun `claim posts v2 wire payload and maps the credential response`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"device_id":"device-123","credential":"credential-ABC_123"}"""),
        )
        val origin = "http://127.0.0.1:${server.port}"
        val ticket = PairingTicket(
            origin = origin,
            ticketId = "ticket-123",
            secret = "secret-ABC_123",
        )
        val repository = V2PairingRepository(origin, debug = false, client = OkHttpClient())

        val credential = repository.claim(ticket, "Pixel 9")

        assertEquals(
            DeviceCredential(origin, deviceId = "device-123", credential = "credential-ABC_123"),
            credential,
        )

        val request = requireNotNull(server.takeRequest())
        assertEquals("POST", request.method)
        assertEquals("/v2/pair/claim", request.path)
        assertEquals("application/json", request.getHeader("Accept"))
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
        assertNull(request.getHeader("Authorization"))

        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("ticket-123", body.get("ticket_id").asString)
        assertEquals("secret-ABC_123", body.get("secret").asString)
        assertEquals("Pixel 9", body.get("device_label").asString)
    }
}
