package com.srthub.cam

import java.net.URI

/**
 * Deterministic SRT endpoint validator. Guarantees RFC 2396-compliant URLs
 * that java.net.URI (and thus RootEncoder's URL parser) will accept.
 *
 * VALIDATION:
 *   1. Port must be 1024–65535
 *   2. Strip zone ID (%), srt:// prefix, accidental trailing port
 *   3. Reject: empty, spaces, '?', underscores, non-ASCII
 *   4. Bracket-wrap bare IPv6
 *   5. Pre-validate with java.net.URI
 *
 * Throws IllegalArgumentException with a clear message on failure.
 */
data class SrtEndpoint(val host: String, val port: Int) {

    val url: String get() = "srt://$host:$port"

    companion object {
        fun fromRaw(rawHost: String, port: Int): SrtEndpoint {
            require(port in 1024..65535) {
                "Port must be 1024–65535. Got: $port"
            }
            var host = rawHost.trim()

            // Strip zone ID, scheme prefix, trailing port
            host = host.substringBefore('%')
            host = host.removePrefix("srt://").removePrefix("SRT://")
            host = host.replace(Regex("]:\\d+$"), "]")
            if (host.count { it == ':' } <= 1) {
                host = host.replace(Regex(":\\d+$"), "")
            }

            require(host.isNotEmpty()) { "Host must not be empty." }
            require(!host.contains(' ')) { "Host must not contain spaces." }
            require(!host.contains('?')) { "Host must not contain '?'." }

            // Bracket-wrap bare IPv6
            if (!host.startsWith("[") && host.count { it == ':' } >= 2) {
                host = "[$host]"
            }

            // Pre-validate with java.net.URI — catches underscores, non-ASCII
            try {
                URI("srt://$host:$port")
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "Endpoint malformed. Use plain IP or hostname " +
                    "(no underscores, spaces, or special chars). " +
                    "(${e.message})"
                )
            }

            return SrtEndpoint(host, port)
        }
    }
}
