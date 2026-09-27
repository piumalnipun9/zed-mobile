package dev.zed.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HostPolicyTest {
    @Test
    fun `parse trims HTTPS origins and permits approved HTTP literal IPs`() {
        assertEquals(
            "https://zed.example:7443/",
            HostPolicy.parse("  https://zed.example:7443/  ", debug = false).toString(),
        )
        assertEquals(
            "http://127.0.0.1:8080/",
            HostPolicy.parse("http://127.0.0.1:8080", debug = false).toString(),
        )
        assertEquals(
            "http://100.100.12.34:7443/",
            HostPolicy.parse("http://100.100.12.34:7443", debug = false).toString(),
        )
        assertEquals(
            "http://10.0.2.2:7443/",
            HostPolicy.parse("http://10.0.2.2:7443", debug = true).toString(),
        )
        assertEquals(
            "http://[fd7a:115c:a1e0::1]:7443/",
            HostPolicy.parse("http://[fd7a:115c:a1e0::1]:7443", debug = false).toString(),
        )
    }

    @Test
    fun `parse rejects unsafe HTTP hosts and non-origin URL parts`() {
        assertInvalid("http://zed.example:7443", debug = false)
        assertInvalid("http://10.0.2.2:7443", debug = false)
        assertInvalid("https://user@zed.example", debug = false)
        assertInvalid("https://zed.example?token=secret", debug = false)
        assertInvalid("https://zed.example/v2/pair", debug = false)
    }

    @Test
    fun `token only accepts nonempty printable ASCII`() {
        assertEquals("device-credential_123", HostPolicy.token("device-credential_123"))
        assertThrows(IllegalArgumentException::class.java) { HostPolicy.token("contains space") }
        assertThrows(IllegalArgumentException::class.java) { HostPolicy.token("") }
    }

    private fun assertInvalid(value: String, debug: Boolean) {
        assertThrows(IllegalArgumentException::class.java) {
            HostPolicy.parse(value, debug)
        }
    }
}
