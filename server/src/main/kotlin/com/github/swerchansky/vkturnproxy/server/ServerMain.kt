package com.github.swerchansky.vkturnproxy.server

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.swerchansky.vkturnproxy.dtls.DtlsServer
import com.github.swerchansky.vkturnproxy.dtls.DtlsServerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.logging.Logger
import kotlin.system.exitProcess

private val log: Logger = Logger.getLogger("server")

fun main(args: Array<String>) {
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

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        // Graceful shutdown on SIGTERM / SIGINT
        Runtime.getRuntime().addShutdownHook(Thread {
            log.info("Terminating...")
            scope.cancel()
        })

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
                    scope.launch {
                        handleConnection(conn, connectAddr)
                    }
                }
            } finally {
                server.close()
            }
        }
    }
}

private suspend fun handleConnection(conn: DtlsServerConnection, wgAddr: InetSocketAddress) {
    log.info("Connection established, relaying to $wgAddr")
    val wgSocket = DatagramSocket().also { it.connect(wgAddr) }
    val buf = ByteArray(1600)
    val wgBuf = ByteArray(1600)

    val job1 = CoroutineScope(Dispatchers.IO).launch {
        // DTLS → WireGuard
        try {
            while (true) {
                val n = conn.receive(buf)
                if (n < 0) break
                wgSocket.send(DatagramPacket(buf, n, wgAddr))
            }
        } catch (e: Exception) {
            log.fine("DTLS→WG relay ended: ${e.message}")
        }
    }

    val job2 = CoroutineScope(Dispatchers.IO).launch {
        // WireGuard → DTLS
        try {
            wgSocket.soTimeout = 30 * 60 * 1000 // 30 min
            while (true) {
                val pkt = DatagramPacket(wgBuf, wgBuf.size)
                wgSocket.receive(pkt)
                conn.send(wgBuf.copyOf(pkt.length))
            }
        } catch (e: Exception) {
            log.fine("WG→DTLS relay ended: ${e.message}")
        }
    }

    job1.join()
    job2.cancel()
    job1.cancel()
    wgSocket.close()
    conn.close()
    log.info("Connection closed")
}

private fun parseAddr(addr: String): InetSocketAddress {
    val lastColon = addr.lastIndexOf(':')
    require(lastColon > 0) { "Invalid address: $addr (expected host:port)" }
    val host = addr.substring(0, lastColon)
    val port = addr.substring(lastColon + 1).toInt()
    return InetSocketAddress(host, port)
}
