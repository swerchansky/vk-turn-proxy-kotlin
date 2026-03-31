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
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("server")
private val connCounter = AtomicInteger(0)

fun main(args: Array<String>) {
    val logConfig = object {}.javaClass.classLoader?.getResourceAsStream("logging.properties")
    if (logConfig != null) {
        java.util.logging.LogManager.getLogManager().readConfiguration(logConfig)
    }
    Security.addProvider(BouncyCastleProvider())
    ServerCommand().main(args)
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "%dm%02ds".format(s / 60, s % 60)
        else -> "%dh%02dm".format(s / 3600, (s % 3600) / 60)
    }
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
        log.info("Listening on $listen · WireGuard → $connect")

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
    val id = connCounter.incrementAndGet()
    val tag = "#$id"
    val startMs = System.currentTimeMillis()

    val wgSocket = DatagramSocket().also {
        it.connect(wgAddr)
        it.soTimeout = 30 * 60 * 1000
    }
    log.info("$tag DTLS connection established · WG local port: ${wgSocket.localPort} → $wgAddr")

    val buf = ByteArray(1600)
    val wgBuf = ByteArray(1600)
    var dtlsToWg = 0
    var wgToDtls = 0

    try {
        coroutineScope {
            // DTLS → WireGuard
            launch(Dispatchers.IO) {
                try {
                    while (true) {
                        val n = conn.receive(buf)
                        if (n < 0) break
                        dtlsToWg++
                        wgSocket.send(DatagramPacket(buf, n, wgAddr))
                    }
                } catch (e: Exception) {
                    log.fine("$tag DTLS→WG ended: ${e.javaClass.simpleName}")
                } finally {
                    wgSocket.close()
                }
            }

            // WireGuard → DTLS
            launch(Dispatchers.IO) {
                try {
                    while (true) {
                        val pkt = DatagramPacket(wgBuf, wgBuf.size)
                        wgSocket.receive(pkt)
                        wgToDtls++
                        conn.send(wgBuf.copyOf(pkt.length))
                    }
                } catch (e: Exception) {
                    log.fine("$tag WG→DTLS ended: ${e.javaClass.simpleName}")
                } finally {
                    conn.close()
                }
            }
        }
    } finally {
        wgSocket.close()
        conn.close()
    }

    val dur = formatDuration(System.currentTimeMillis() - startMs)
    log.info("$tag Connection closed · ↑$wgToDtls ↓$dtlsToWg pkts · up: $dur")
}

private fun parseAddr(addr: String): InetSocketAddress {
    val lastColon = addr.lastIndexOf(':')
    require(lastColon > 0) { "Invalid address: $addr (expected host:port)" }
    val host = addr.substring(0, lastColon)
    val port = addr.substring(lastColon + 1).toInt()
    return InetSocketAddress(host, port)
}
