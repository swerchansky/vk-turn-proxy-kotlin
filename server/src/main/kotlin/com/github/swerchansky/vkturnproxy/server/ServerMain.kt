package com.github.swerchansky.vkturnproxy.server

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.swerchansky.vkturnproxy.dtls.DtlsServer
import com.github.swerchansky.vkturnproxy.dtls.DtlsServerConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("server")

fun main(args: Array<String>) {
    val logConfig = object {}.javaClass.classLoader?.getResourceAsStream("logging.properties")
    if (logConfig != null) {
        java.util.logging.LogManager.getLogManager().readConfiguration(logConfig)
    }
    Security.addProvider(BouncyCastleProvider())
    ServerCommand().main(args)
}

private class ServerCommand : CliktCommand(name = "server") {
    val listen by option("--listen", help = "Address to listen for DTLS connections")
        .default("0.0.0.0:56000")
    val connect by option("--connect", help = "WireGuard interface address (host:port)")
        .required()

    override fun run() {
        val listenAddr = parseAddr(listen)
        val connectAddr = parseAddr(connect)

        val server = DtlsServer(listenAddr)
        log.info("Listening on $listen")

        runBlocking(Dispatchers.IO) {
            try {
                while (true) {
                    val conn = try {
                        server.accept()
                    } catch (e: Exception) {
                        log.warning("Accept error: ${e.message}")
                        continue
                    }
                    launch { handleConnection(conn, connectAddr) }
                }
            } finally {
                server.close()
            }
        }
    }
}

private suspend fun handleConnection(conn: DtlsServerConnection, wgAddr: InetSocketAddress) {
    log.info("Connection established, relaying to WireGuard at $wgAddr")
    val wgSocket = DatagramSocket().also {
        it.connect(wgAddr)
        it.soTimeout = 30 * 60 * 1000
    }
    log.info("WireGuard socket bound to local port ${wgSocket.localPort}, connected to $wgAddr")
    val buf = ByteArray(1600)
    val wgBuf = ByteArray(1600)

    coroutineScope {
        // DTLS → WireGuard
        launch(Dispatchers.IO) {
            var count = 0
            try {
                while (true) {
                    val n = conn.receive(buf)
                    if (n < 0) { log.info("DTLS→WG: receive returned -1 (timeout/close) after $count pkts"); break }
                    count++
                    if (count <= 5 || count % 100 == 0)
                        log.info("DTLS→WG pkt #$count ${n}B → WireGuard  hdr=${buf.take(3).joinToString("") { "%02x".format(it) }}")
                    wgSocket.send(DatagramPacket(buf, n, wgAddr))
                }
            } catch (e: Exception) {
                log.warning("DTLS→WG ended after $count pkts: ${e.javaClass.name}: ${e.message}")
            } finally {
                log.info("DTLS→WG: closing wgSocket to unblock WG→DTLS direction")
                wgSocket.close()
            }
        }

        // WireGuard → DTLS
        launch(Dispatchers.IO) {
            var count = 0
            log.info("WG→DTLS: waiting for first packet from WireGuard on port ${wgSocket.localPort}...")
            try {
                while (true) {
                    val pkt = DatagramPacket(wgBuf, wgBuf.size)
                    wgSocket.receive(pkt)
                    count++
                    if (count <= 5 || count % 100 == 0)
                        log.info("WG→DTLS pkt #$count ${pkt.length}B from WireGuard  hdr=${wgBuf.take(3).joinToString("") { "%02x".format(it) }}")
                    conn.send(wgBuf.copyOf(pkt.length))
                }
            } catch (e: Exception) {
                log.warning("WG→DTLS ended after $count pkts: ${e.javaClass.name}: ${e.message}")
            } finally {
                log.info("WG→DTLS: closing DTLS conn to unblock DTLS→WG direction")
                conn.close()
            }
        }
    }

    wgSocket.close()
    conn.close()
    log.info("Connection fully closed")
}

private fun parseAddr(addr: String): InetSocketAddress {
    val lastColon = addr.lastIndexOf(':')
    require(lastColon > 0) { "Invalid address: $addr (expected host:port)" }
    val host = addr.substring(0, lastColon)
    val port = addr.substring(lastColon + 1).toInt()
    return InetSocketAddress(host, port)
}
