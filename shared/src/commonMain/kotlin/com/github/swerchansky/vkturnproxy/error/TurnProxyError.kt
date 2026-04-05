package com.github.swerchansky.vkturnproxy.error

sealed class TurnProxyError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    data class CredentialFetchFailed(val reason: String, val cause0: Throwable? = null) :
        TurnProxyError("Credential fetch failed: $reason", cause0)

    data class TurnAllocationFailed(val reason: String) :
        TurnProxyError("TURN allocation failed: $reason")

    data class DtlsHandshakeFailed(val reason: String, val cause0: Throwable? = null) :
        TurnProxyError("DTLS handshake failed: $reason", cause0)

    data class TransportError(val reason: String, val cause0: Throwable? = null) :
        TurnProxyError("Transport error: $reason", cause0)

    data class DnsResolutionFailed(val host: String) :
        TurnProxyError("DNS resolution failed for host: $host")

    data object StaleNonce : TurnProxyError("TURN nonce is stale (438)")
}
