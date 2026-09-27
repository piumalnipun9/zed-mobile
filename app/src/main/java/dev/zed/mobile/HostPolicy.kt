package dev.zed.mobile

import java.net.InetAddress
import java.net.URI
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object HostPolicy {
    fun parse(input: String, debug: Boolean): HttpUrl {
        val text = input.trim()
        val uri = try {
            URI(text)
        } catch (_: Exception) {
            throw IllegalArgumentException("Enter a full HTTPS URL, including the port.")
        }
        require(uri.scheme == "https" || uri.scheme == "http") { "Use HTTPS or an allowed literal-IP HTTP address." }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "Do not put credentials, queries, or fragments in the host URL." }
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") { "Enter the host origin only, without an API path." }
        val host = uri.host
            ?: throw IllegalArgumentException("Invalid host. Use a DNS name with HTTPS or a literal IP address.")
        if (uri.scheme == "http") require(isAllowedHttp(host, debug)) {
            "HTTP requires a literal Tailscale (100.64.0.0/10) or loopback IP. Use HTTPS for DNS names."
        }
        return text.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid host or port.")
    }

    private fun isAllowedHttp(host: String, debug: Boolean): Boolean {
        val literal = host.removePrefix("[").removeSuffix("]")
        if (literal.contains(':')) {
            val address = runCatching { InetAddress.getByName(literal).address }.getOrNull() ?: return false
            return (address.size == 16 && address.allIndexed(15) { it == 0.toByte() } && address[15] == 1.toByte()) ||
                    (address.size == 16 && address[0] == 0xfd.toByte() && address[1] == 0x7a.toByte() &&
                            address[2] == 0x11.toByte() && address[3] == 0x5c.toByte() && address[4] == 0xa1.toByte() &&
                            address[5] == 0xe0.toByte())
        }
        val parts = literal.split('.')
        if (parts.size != 4) return false
        val octets = parts.map {
            if (!it.matches(Regex("0|[1-9][0-9]{0,2}"))) return false
            it.toInt().also { n -> if (n > 255) return false }
        }
        return octets[0] == 127 || (octets[0] == 100 && octets[1] in 64..127) ||
                (debug && octets == listOf(10, 0, 2, 2))
    }

    private inline fun ByteArray.allIndexed(endExclusive: Int, predicate: (Byte) -> Boolean): Boolean {
        for (index in 0 until endExclusive) if (!predicate(this[index])) return false
        return true
    }

    fun token(input: String): String {
        require(input.isNotEmpty() && input.all { it.code in 33..126 }) { "Token must be nonempty printable ASCII without spaces or newlines." }
        return input
    }
}
