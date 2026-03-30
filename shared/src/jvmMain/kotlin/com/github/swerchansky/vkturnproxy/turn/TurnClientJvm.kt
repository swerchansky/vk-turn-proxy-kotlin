package com.github.swerchansky.vkturnproxy.turn

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * High-level TURN client (RFC 5766) over TCP or UDP.
 *
 * Usage:
 * ```
 * val client = TurnClient.connect(serverAddr, creds, useUdp = false)
 * client.allocate()
 * val relay = client.relayAddress()
 * client.channelBind(peerIp, peerPort)
 * client.sendChannelData(bytes)
 * val data = client.receiveChannelData()
 * client.close()
 * ```
 */
class TurnClient private constructor(
    private val transport: TurnTransportJvm,
    private val credentials: TurnCredentials,
    private val addressFamily: RequestedAddressFamily,
) : Closeable {

    private var realm: String = ""
    private var nonce: String = ""
    private var allocatedIp: ByteArray = ByteArray(4)
    private var allocatedPort: Int = 0
    private var boundChannel: Int = -1

    // ── lifecycle ──────────────────────────────────────────────────────────

    companion object {
        fun connect(
            serverAddr: InetSocketAddress,
            credentials: TurnCredentials,
            useUdp: Boolean,
            addressFamily: RequestedAddressFamily = RequestedAddressFamily.IPv4,
            connectTimeoutMs: Int = 5_000,
        ): TurnClient {
            val transport = if (useUdp) {
                UdpTurnTransport(serverAddr)
            } else {
                TcpTurnTransport(serverAddr, connectTimeoutMs)
            }
            return TurnClient(transport, credentials, addressFamily)
        }
    }

    override fun close() = transport.close()

    // ── public API ─────────────────────────────────────────────────────────

    /** Allocates a relay socket. Must be called once before send/receive. */
    fun allocate() {
        // First attempt — server will reply 401 with REALM + NONCE
        val req1 = buildAllocateRequest(authenticated = false)
        val resp1 = transport.sendReceive(req1)
            ?: error("TURN Allocate: no response")

        if (resp1.cls == StunClass.ERROR) {
            val errCode = parseErrorCode(resp1)
            if (errCode == 401) {
                realm = resp1.getAttr(StunAttr.REALM)?.decodeToString() ?: ""
                nonce = resp1.getAttr(StunAttr.NONCE)?.decodeToString() ?: ""
                val req2 = buildAllocateRequest(authenticated = true)
                addMessageIntegrity(req2)
                val resp2 = transport.sendReceive(req2)
                    ?: error("TURN Allocate (authenticated): no response")
                check(resp2.cls == StunClass.SUCCESS) {
                    "TURN Allocate failed: ${decodeErrorCode(resp2.getAttr(StunAttr.ERROR_CODE))}"
                }
                extractRelayAddress(resp2)
                return
            }
            error("TURN Allocate failed: ${decodeErrorCode(resp1.getAttr(StunAttr.ERROR_CODE))}")
        }
        check(resp1.cls == StunClass.SUCCESS) {
            "TURN Allocate failed: ${decodeErrorCode(resp1.getAttr(StunAttr.ERROR_CODE))}"
        }
        extractRelayAddress(resp1)
    }

    /** Returns the relay address assigned by the TURN server. */
    fun relayAddress(): InetSocketAddress {
        val ip = allocatedIp.joinToString(".") { (it.toInt() and 0xFF).toString() }
        return InetSocketAddress(ip, allocatedPort)
    }

    /**
     * Binds a channel to the given peer for efficient ChannelData framing.
     * [channel] must be in 0x4000..0x7FFE range.
     */
    fun channelBind(peerIp: ByteArray, peerPort: Int, channel: Int = 0x4000) {
        check(channel in CHANNEL_DATA_MIN until CHANNEL_DATA_MAX) { "Invalid channel number" }
        boundChannel = channel

        val req = StunMessage(StunMethod.CHANNEL_BIND, StunClass.REQUEST)
        // CHANNEL-NUMBER: 2-byte channel + 2-byte reserved
        val chBuf = ByteArray(4)
        chBuf[0] = (channel ushr 8).toByte()
        chBuf[1] = (channel and 0xFF).toByte()
        req.addAttr(StunAttr.CHANNEL_NUMBER, chBuf)
        req.addXorIpv4Attr(StunAttr.XOR_PEER_ADDRESS, peerIp, peerPort)
        addMessageIntegrity(req)

        val resp = transport.sendReceive(req)
            ?: error("ChannelBind: no response")
        check(resp.cls == StunClass.SUCCESS) {
            "ChannelBind failed: ${decodeErrorCode(resp.getAttr(StunAttr.ERROR_CODE))}"
        }
    }

    /** Sends data via ChannelData framing (requires prior channelBind). */
    fun send(data: ByteArray) {
        check(boundChannel >= 0) { "Must call channelBind before send" }
        val frame = StunMessage.encodeChannelData(boundChannel, data)
        transport.sendRaw(frame)
    }

    /**
     * Reads the next packet arriving on the relay.
     * Returns payload bytes, or null for non-data STUN messages.
     */
    fun receive(): ByteArray? {
        val raw = transport.receiveRaw()
        if (StunMessage.isChannelData(raw)) {
            val (ch, payload) = StunMessage.decodeChannelData(raw) ?: return null
            if (ch == boundChannel) return payload
        }
        return null
    }

    // ── internals ──────────────────────────────────────────────────────────

    private fun buildAllocateRequest(authenticated: Boolean): StunMessage {
        val req = StunMessage(StunMethod.ALLOCATE, StunClass.REQUEST)
        // REQUESTED-TRANSPORT: UDP (17)
        req.addAttr(StunAttr.REQUESTED_TRANSPORT, byteArrayOf(17, 0, 0, 0))
        // REQUESTED-ADDRESS-FAMILY (RFC 6156)
        req.addAttr(StunAttr.REQUESTED_ADDRESS_FAMILY, byteArrayOf(addressFamily.code.toByte(), 0, 0, 0))
        if (authenticated && realm.isNotEmpty()) {
            req.addStringAttr(StunAttr.USERNAME, credentials.username)
            req.addStringAttr(StunAttr.REALM, realm)
            req.addStringAttr(StunAttr.NONCE, nonce)
        }
        return req
    }

    private fun extractRelayAddress(resp: StunMessage) {
        val attr = resp.getAttr(StunAttr.XOR_RELAYED_ADDRESS)
            ?: error("Missing XOR-RELAYED-ADDRESS in Allocate response")
        val (ip, port) = decodeXorIpv4Address(attr)
            ?: error("Cannot decode XOR-RELAYED-ADDRESS (IPv6 not supported)")
        allocatedIp = ip
        allocatedPort = port
    }

    /**
     * Appends USERNAME, REALM, NONCE (if we have them) and MESSAGE-INTEGRITY
     * to the message using TURN long-term credentials (RFC 5389 §10.2).
     */
    private fun addMessageIntegrity(msg: StunMessage) {
        if (realm.isNotEmpty()) {
            msg.addStringAttr(StunAttr.USERNAME, credentials.username)
            msg.addStringAttr(StunAttr.REALM, realm)
            msg.addStringAttr(StunAttr.NONCE, nonce)
        }
        // Key = MD5(username ":" realm ":" password)
        val keyInput = "${credentials.username}:$realm:${credentials.password}"
        val key = MessageDigest.getInstance("MD5").digest(keyInput.encodeToByteArray())
        // Data to MAC = message with length field set as if MESSAGE-INTEGRITY were included
        val macData = msg.encodeForHmac()
        val mac = Mac.getInstance("HmacSHA1").apply {
            init(SecretKeySpec(key, "HmacSHA1"))
        }.doFinal(macData)
        msg.addAttr(StunAttr.MESSAGE_INTEGRITY, mac)
    }

    private fun parseErrorCode(msg: StunMessage): Int {
        val attr = msg.getAttr(StunAttr.ERROR_CODE) ?: return 0
        if (attr.size < 4) return 0
        return (attr[2].toInt() and 0xFF) * 100 + (attr[3].toInt() and 0xFF)
    }
}

// ── Transport abstractions ─────────────────────────────────────────────────

private interface TurnTransportJvm : Closeable {
    /** Send a STUN request and wait for the matching response. */
    fun sendReceive(msg: StunMessage): StunMessage?
    /** Send raw bytes (for ChannelData). */
    fun sendRaw(data: ByteArray)
    /** Receive raw bytes. */
    fun receiveRaw(): ByteArray
}

private const val RECEIVE_TIMEOUT_MS = 5_000
private const val MAX_STUN_MSG_SIZE = 65535

// ── TCP transport (RFC 4571 framing) ─────────────────────────────────────

private class TcpTurnTransport(
    addr: InetSocketAddress,
    connectTimeoutMs: Int,
) : TurnTransportJvm {
    private val socket = Socket().also { it.connect(addr, connectTimeoutMs) }
    private val out: OutputStream = socket.getOutputStream()
    private val inp: InputStream = socket.getInputStream()

    override fun sendReceive(msg: StunMessage): StunMessage? {
        sendRaw(msg.encode())
        // Drain until we find the matching transaction ID
        repeat(10) {
            val raw = receiveRaw()
            val resp = StunMessage.decode(raw) ?: return@repeat
            if (resp.transactionId.contentEquals(msg.transactionId)) return resp
        }
        return null
    }

    override fun sendRaw(data: ByteArray) {
        // RFC 4571: 2-byte big-endian length prefix
        val frame = ByteArray(2 + data.size)
        frame[0] = (data.size ushr 8).toByte()
        frame[1] = (data.size and 0xFF).toByte()
        data.copyInto(frame, 2)
        synchronized(out) { out.write(frame); out.flush() }
    }

    override fun receiveRaw(): ByteArray {
        val lenBuf = ByteArray(2)
        readFully(inp, lenBuf)
        val len = StunMessage.readUInt16(lenBuf, 0)
        val buf = ByteArray(len)
        readFully(inp, buf)
        return buf
    }

    override fun close() = socket.close()

    private fun readFully(stream: InputStream, buf: ByteArray) {
        var read = 0
        while (read < buf.size) {
            val n = stream.read(buf, read, buf.size - read)
            if (n < 0) error("Connection closed while reading TURN response")
            read += n
        }
    }
}

// ── UDP transport ──────────────────────────────────────────────────────────

private class UdpTurnTransport(private val serverAddr: InetSocketAddress) : TurnTransportJvm {
    private val socket = DatagramSocket().also {
        it.connect(serverAddr)
        it.soTimeout = RECEIVE_TIMEOUT_MS
    }

    override fun sendReceive(msg: StunMessage): StunMessage? {
        sendRaw(msg.encode())
        repeat(3) {
            try {
                val raw = receiveRaw()
                val resp = StunMessage.decode(raw) ?: return@repeat
                if (resp.transactionId.contentEquals(msg.transactionId)) return resp
            } catch (_: java.net.SocketTimeoutException) {
                return@repeat
            }
        }
        return null
    }

    override fun sendRaw(data: ByteArray) {
        socket.send(DatagramPacket(data, data.size))
    }

    override fun receiveRaw(): ByteArray {
        val buf = ByteArray(MAX_STUN_MSG_SIZE)
        val pkt = DatagramPacket(buf, buf.size)
        socket.receive(pkt)
        return pkt.data.copyOf(pkt.length)
    }

    override fun close() = socket.close()
}
