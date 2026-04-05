package com.github.swerchansky.vkturnproxy.turn

import com.github.swerchansky.vkturnproxy.config.TurnProxyConfig
import com.github.swerchansky.vkturnproxy.error.TurnProxyError
import com.github.swerchansky.vkturnproxy.logging.NoOpLogger
import com.github.swerchansky.vkturnproxy.logging.ProxyLogger
import com.github.swerchansky.vkturnproxy.stun.StunAttr
import com.github.swerchansky.vkturnproxy.stun.StunClass
import com.github.swerchansky.vkturnproxy.stun.StunMessage
import com.github.swerchansky.vkturnproxy.stun.StunMethod
import com.github.swerchansky.vkturnproxy.stun.decodeErrorCode
import com.github.swerchansky.vkturnproxy.stun.decodeXorIpv4Address
import com.github.swerchansky.vkturnproxy.stun.encode
import com.github.swerchansky.vkturnproxy.transport.TurnTransport
import java.net.InetSocketAddress

/**
 * Handles TURN Allocate / Refresh lifecycle (RFC 5766 §6–7).
 */
internal class TurnAllocator(
    private val addressFamily: RequestedAddressFamily,
    private val logger: ProxyLogger = NoOpLogger,
) {
    private companion object {
        const val TAG = "TurnAllocator"
    }

    private var allocatedIp: ByteArray = ByteArray(4)
    private var allocatedPort: Int = 0

    /** Performs TURN allocation (two-step: unauthenticated + authenticated). */
    fun allocate(transport: TurnTransport, auth: TurnAuthenticator) {
        logger.debug(TAG, "Alloc start · family=${addressFamily.name}")

        val req1 = buildAllocateRequest()
        val resp1 = transport.sendReceive(req1)
            ?: throw TurnProxyError.TurnAllocationFailed("no response from server")

        if (resp1.cls == StunClass.ERROR) {
            val errCode = auth.parseErrorCode(resp1)
            if (errCode == 401) {
                auth.realm = resp1.getAttr(StunAttr.REALM)?.decodeToString() ?: ""
                auth.nonce = resp1.getAttr(StunAttr.NONCE)?.decodeToString() ?: ""
                logger.debug(TAG, "Auth challenge: realm='${auth.realm}'")

                val req2 = buildAllocateRequest()
                auth.addAuth(req2)

                val resp2 = transport.sendReceive(req2)
                    ?: throw TurnProxyError.TurnAllocationFailed("no response after authentication")

                if (resp2.cls != StunClass.SUCCESS)
                    throw TurnProxyError.TurnAllocationFailed(decodeErrorCode(resp2.getAttr(StunAttr.ERROR_CODE)))
                extractRelayAddress(resp2)
                logger.info(TAG, "Allocated relay=${relayAddress()}")
                return
            }
            throw TurnProxyError.TurnAllocationFailed(decodeErrorCode(resp1.getAttr(StunAttr.ERROR_CODE)))
        }
        if (resp1.cls != StunClass.SUCCESS)
            throw TurnProxyError.TurnAllocationFailed(decodeErrorCode(resp1.getAttr(StunAttr.ERROR_CODE)))
        extractRelayAddress(resp1)
        logger.info(TAG, "Allocated relay=${relayAddress()}")
    }

    /**
     * Sends a fire-and-forget TURN Refresh to keep the allocation alive.
     * Responses are consumed by the relay loop's receive() as non-ChannelData STUN messages.
     */
    fun refresh(transport: TurnTransport, auth: TurnAuthenticator) {
        val req = StunMessage(StunMethod.REFRESH, StunClass.REQUEST)
        val lifetime = TurnProxyConfig.TURN_ALLOCATION_LIFETIME_S
        req.addAttr(
            StunAttr.LIFETIME,
            byteArrayOf(0, 0, (lifetime ushr 8).toByte(), (lifetime and 0xFF).toByte())
        )
        auth.addAuth(req)
        transport.sendRaw(req.encode())
    }

    /** Returns the relay address assigned by the TURN server. */
    fun relayAddress(): InetSocketAddress {
        val ip = allocatedIp.joinToString(".") { (it.toInt() and 0xFF).toString() }
        return InetSocketAddress(ip, allocatedPort)
    }

    private fun buildAllocateRequest(): StunMessage {
        val req = StunMessage(StunMethod.ALLOCATE, StunClass.REQUEST)
        req.addAttr(StunAttr.REQUESTED_TRANSPORT, byteArrayOf(17, 0, 0, 0)) // UDP
        req.addAttr(
            StunAttr.REQUESTED_ADDRESS_FAMILY,
            byteArrayOf(addressFamily.code.toByte(), 0, 0, 0)
        )
        return req
    }

    private fun extractRelayAddress(resp: StunMessage) {
        val attr = resp.getAttr(StunAttr.XOR_RELAYED_ADDRESS)
            ?: throw TurnProxyError.TurnAllocationFailed("Missing XOR-RELAYED-ADDRESS in Allocate response")
        val (ip, port) = decodeXorIpv4Address(attr)
            ?: throw TurnProxyError.TurnAllocationFailed("Cannot decode XOR-RELAYED-ADDRESS (IPv6 not supported)")
        allocatedIp = ip
        allocatedPort = port
    }
}
