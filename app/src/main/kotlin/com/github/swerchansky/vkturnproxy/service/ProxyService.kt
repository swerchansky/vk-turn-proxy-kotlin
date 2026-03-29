package com.github.swerchansky.vkturnproxy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.github.swerchansky.vkturnproxy.App
import com.github.swerchansky.vkturnproxy.credentials.CredentialProvider
import com.github.swerchansky.vkturnproxy.credentials.VkCredentialProvider
import com.github.swerchansky.vkturnproxy.credentials.YandexCredentialProvider
import com.github.swerchansky.vkturnproxy.dtls.DtlsClient
import com.github.swerchansky.vkturnproxy.turn.RequestedAddressFamily
import com.github.swerchansky.vkturnproxy.turn.TurnClient
import com.github.swerchansky.vkturnproxy.R
import com.github.swerchansky.vkturnproxy.ui.main.MainActivity
import com.github.swerchansky.vkturnproxy.ui.main.ProxyState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

@Suppress("TooManyFunctions")
class ProxyService : Service() {

    companion object {
        val state = MutableStateFlow<ProxyState>(ProxyState.Idle)
        val log = MutableStateFlow("")

        const val ACTION_START = "com.github.swerchansky.vkturnproxy.START"
        const val ACTION_STOP = "com.github.swerchansky.vkturnproxy.STOP"

        const val EXTRA_LINK = "link"
        const val EXTRA_PEER = "peer"
        const val EXTRA_PORT = "port"
        const val EXTRA_UDP = "udp"
        const val EXTRA_IS_VK = "is_vk"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "proxy_channel"
    }

    @Inject lateinit var vkProvider: VkCredentialProvider
    @Inject lateinit var yandexProvider: YandexCredentialProvider

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var proxyJob: Job? = null
    private var localSocket: DatagramSocket? = null

    override fun onCreate() {
        super.onCreate()
        (applicationContext as App).appComponent.inject(this)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("ReturnCount")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val link = intent.getStringExtra(EXTRA_LINK) ?: return START_NOT_STICKY
                val peer = intent.getStringExtra(EXTRA_PEER) ?: return START_NOT_STICKY
                val port = intent.getIntExtra(EXTRA_PORT, 9000)
                val udp = intent.getBooleanExtra(EXTRA_UDP, false)
                val isVk = intent.getBooleanExtra(EXTRA_IS_VK, true)
                startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))
                proxyJob?.cancel()
                proxyJob = scope.launch { runProxy(link, peer, port, udp, isVk) }
            }
            ACTION_STOP -> stopProxy()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        proxyJob?.cancel()
        localSocket?.close()
        state.value = ProxyState.Idle
        scope.cancel()
    }

    private fun stopProxy() {
        proxyJob?.cancel()
        proxyJob = null
        localSocket?.close()
        localSocket = null
        appendLog("--- Disconnected ---")
        state.value = ProxyState.Idle
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    private fun appendLog(line: String) {
        log.value = if (log.value.isEmpty()) line else "${log.value}\n$line"
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ProxyService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Good TURN")
            .setContentText(text)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Proxy Service",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "VK/Yandex TURN proxy tunnel" }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount", "LongMethod", "CyclomaticComplexMethod")
    private suspend fun runProxy(
        rawLink: String,
        peerAddress: String,
        listenPort: Int,
        useUdp: Boolean,
        isVk: Boolean,
    ) {
        val provider: CredentialProvider = if (isVk) vkProvider else yandexProvider
        val peerAddr = parseAddr(peerAddress)

        appendLog("Getting TURN credentials...")
        state.value = ProxyState.Connecting("Getting TURN credentials...")
        updateNotification("Getting TURN credentials...")

        val creds = try {
            provider.getCredentials(rawLink)
        } catch (e: Exception) {
            val msg = "Credentials failed: ${e.message}"
            appendLog(msg)
            state.value = ProxyState.Error(msg)
            finishWithError("Credentials failed")
            return
        }
        appendLog("Credentials OK: ${creds.address}")

        val turnAddr = InetSocketAddress(
            creds.address.substringBefore(":"),
            creds.address.substringAfter(":", "3478").toIntOrNull() ?: 3478,
        )
        val addrFamily = if (peerAddr.address.address.size == 4) RequestedAddressFamily.IPv4
        else RequestedAddressFamily.IPv6

        appendLog("TURN connect to $turnAddr...")
        state.value = ProxyState.Connecting("TURN allocate...")
        updateNotification("TURN allocate...")

        val turnClient = try {
            TurnClient.connect(turnAddr, creds, useUdp, addrFamily, logger = ::appendLog)
        } catch (e: Exception) {
            val msg = "TURN connect failed: ${e.javaClass.simpleName}: ${e.message}"
            appendLog(msg)
            state.value = ProxyState.Error(msg)
            finishWithError("TURN connect failed")
            return
        }

        val dtls = DtlsClient()
        try {
            appendLog("TURN allocate...")
            turnClient.allocate()
            appendLog("TURN relay: ${turnClient.relayAddress()}")
            turnClient.channelBind(peerAddr.address.address, peerAddr.port)
            appendLog("TURN channel bound to $peerAddress")

            appendLog("DTLS handshake via TURN to $peerAddress...")
            state.value = ProxyState.Connecting("DTLS handshake...")
            updateNotification("DTLS handshake...")

            val dtlsStart = System.currentTimeMillis()
            try {
                dtls.connectOverTurn(turnClient, ::appendLog)
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - dtlsStart
                val msg = buildString {
                    append("DTLS failed after ${elapsed}ms\n")
                    var ex: Throwable? = e
                    var depth = 0
                    while (ex != null) {
                        val indent = if (depth == 0) "" else "  ".repeat(depth) + "Caused by: "
                        append("${indent}${ex.javaClass.name}: ${ex.message}\n")
                        for (frame in ex.stackTrace.take(8)) {
                            append("  ${"  ".repeat(depth)}at $frame\n")
                        }
                        ex = ex.cause
                        depth++
                        if (depth > 4) break
                    }
                }
                appendLog(msg)
                state.value = ProxyState.Error("DTLS failed")
                finishWithError("DTLS handshake failed")
                return
            }
            appendLog("DTLS handshake OK after ${System.currentTimeMillis() - dtlsStart}ms")

            val socket = DatagramSocket(InetSocketAddress("127.0.0.1", listenPort))
            localSocket = socket
            val relayAddr = turnClient.relayAddress().toString()
            appendLog("Connected! Relay: $relayAddr\nListening on 127.0.0.1:$listenPort")
            state.value = ProxyState.Connected(relayAddr)
            updateNotification("Connected — $relayAddr")

            val buf = ByteArray(1600)
            val dtlsBuf = ByteArray(1600)
            val lastLocalAddr = AtomicReference<InetSocketAddress>()
            var toServerPkts = 0
            var fromServerPkts = 0

            // coroutineScope: если любое из направлений упадёт — оба останавливаются
            coroutineScope {
                // WireGuard → DTLS → TURN → server
                launch {
                    runCatching {
                        while (true) {
                            val pkt = DatagramPacket(buf, buf.size)
                            socket.receive(pkt)
                            val from = InetSocketAddress(pkt.address, pkt.port)
                            lastLocalAddr.set(from)
                            toServerPkts++
                            if (toServerPkts <= 3 || toServerPkts % 50 == 0)
                                appendLog("→server pkt #$toServerPkts ${pkt.length}B from $from")
                            dtls.send(buf.copyOf(pkt.length))
                        }
                    }.onFailure {
                        appendLog("WG→TURN ended after $toServerPkts pkts: ${it.javaClass.simpleName}: ${it.message}")
                    }
                }

                // server → TURN → DTLS → WireGuard
                @Suppress("LoopWithTooManyJumpStatements")
                launch {
                    appendLog("←server direction: waiting for first DTLS packet from server...")
                    runCatching {
                        while (true) {
                            val n = dtls.receive(dtlsBuf)
                            if (n < 0) {
                                appendLog("←server: dtls.receive=-1 (timeout/close), ending relay")
                                break
                            }
                            if (n == 1 && dtlsBuf[0] == 0.toByte()) continue // keepalive
                            fromServerPkts++
                            val addr = lastLocalAddr.get()
                            if (fromServerPkts <= 3 || fromServerPkts % 50 == 0)
                                appendLog("←server pkt #$fromServerPkts ${n}B → localAddr=$addr")
                            if (addr == null) {
                                appendLog("←server: no WireGuard addr yet, dropping pkt #$fromServerPkts")
                                continue
                            }
                            socket.send(DatagramPacket(dtlsBuf, n, addr))
                        }
                    }.onFailure {
                        appendLog("TURN→WG ended after $fromServerPkts pkts: ${it.javaClass.simpleName}: ${it.message}")
                    }
                }
            }
            appendLog("Relay done: →server=$toServerPkts, ←server=$fromServerPkts pkts")
        } catch (e: Exception) {
            val msg = "Relay error: ${e.javaClass.simpleName}: ${e.message}"
            appendLog(msg)
            state.value = ProxyState.Error(msg)
        } finally {
            dtls.close()
            turnClient.close()
            @Suppress("DEPRECATION")
            stopForeground(true)
            stopSelf()
        }
    }

    private fun finishWithError(notifText: String) {
        updateNotification("Error: $notifText")
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    private fun parseAddr(addr: String): InetSocketAddress {
        val lastColon = addr.lastIndexOf(':')
        require(lastColon > 0) { "Invalid address: $addr" }
        return InetSocketAddress(addr.substring(0, lastColon), addr.substring(lastColon + 1).toInt())
    }
}
