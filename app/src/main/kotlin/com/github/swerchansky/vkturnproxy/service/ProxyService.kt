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
import com.github.swerchansky.vkturnproxy.R
import com.github.swerchansky.vkturnproxy.credentials.CredentialProvider
import com.github.swerchansky.vkturnproxy.credentials.VkCredentialProvider
import com.github.swerchansky.vkturnproxy.credentials.YandexCredentialProvider
import com.github.swerchansky.vkturnproxy.dtls.DtlsClient
import com.github.swerchansky.vkturnproxy.turn.RequestedAddressFamily
import com.github.swerchansky.vkturnproxy.turn.TurnClient
import com.github.swerchansky.vkturnproxy.ui.main.MainActivity
import com.github.swerchansky.vkturnproxy.ui.main.ProxyState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

data class ProxyStats(
    val toServerPkts: Long = 0,
    val fromServerPkts: Long = 0,
    val connectedSince: Long = 0,
    val toServerPps: Float = 0f,
    val fromServerPps: Float = 0f,
    val relayAddr: String = "",
)

@Suppress("TooManyFunctions")
class ProxyService : Service() {

    companion object {
        val state = MutableStateFlow<ProxyState>(ProxyState.Idle)
        val log = MutableStateFlow("")
        val stats = MutableStateFlow(ProxyStats())

        const val ACTION_START = "com.github.swerchansky.vkturnproxy.START"
        const val ACTION_STOP = "com.github.swerchansky.vkturnproxy.STOP"

        const val EXTRA_LINK = "link"
        const val EXTRA_PEER = "peer"
        const val EXTRA_PORT = "port"
        const val EXTRA_IS_VK = "is_vk"
        const val EXTRA_N = "n_connections"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "proxy_channel"

        @Suppress("ImplicitDefaultLocale")
        fun formatPackets(count: Long): String {
            return when {
                count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000f)
                count >= 1_000 -> String.format("%.1fK", count / 1_000f)
                else -> count.toString()
            }
        }
    }

    @Inject lateinit var vkProvider: VkCredentialProvider
    @Inject lateinit var yandexProvider: YandexCredentialProvider

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var proxyJob: Job? = null
    private var localSocket: DatagramSocket? = null

    private val toServerCounter = AtomicLong(0)
    private val fromServerCounter = AtomicLong(0)

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
                val isVk = intent.getBooleanExtra(EXTRA_IS_VK, true)
                val n = intent.getIntExtra(EXTRA_N, 0)
                startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))
                proxyJob?.cancel()
                toServerCounter.set(0)
                fromServerCounter.set(0)
                stats.value = ProxyStats()
                proxyJob = scope.launch { runProxy(link, peer, port, isVk, n) }
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
        stats.value = ProxyStats()
        scope.cancel()
    }

    private fun stopProxy() {
        proxyJob?.cancel()
        proxyJob = null
        localSocket?.close()
        localSocket = null
        appendLog("--- Disconnected ---")
        state.value = ProxyState.Idle
        stats.value = ProxyStats()
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    private fun appendLog(line: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$time] $line"
        log.value = if (log.value.isEmpty()) entry else "${log.value}\n$entry"
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
            .setContentTitle("vk-turn-proxy")
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
        ).apply { description = "VK TURN proxy tunnel" }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private suspend fun runProxy(
        rawLink: String,
        peerAddress: String,
        listenPort: Int,
        isVk: Boolean,
        nConnections: Int,
    ) {
        val provider: CredentialProvider = if (isVk) vkProvider else yandexProvider
        val peerAddr = parseAddr(peerAddress)
        val n = if (nConnections > 0) nConnections else 16
        val addrFamily = if (peerAddr.address.address.size == 4) RequestedAddressFamily.IPv4
        else RequestedAddressFamily.IPv6

        state.value = ProxyState.Connecting("Connecting...")
        updateNotification("Connecting...")

        val socket = DatagramSocket(InetSocketAddress("127.0.0.1", listenPort))
        localSocket = socket

        try {
            coroutineScope {
                val firstReady = CompletableDeferred<String>()

                launch {
                    runSingleDtlsConnection(
                        rawLink, provider, peerAddr, socket,
                        addrFamily, firstReady, ::appendLog,
                        onToServer = { toServerCounter.incrementAndGet() },
                        onFromServer = { fromServerCounter.incrementAndGet() },
                    )
                }

                val relayAddr = firstReady.await()
                val connectedAt = System.currentTimeMillis()
                appendLog("Connected! Relay: $relayAddr\nListening on 127.0.0.1:$listenPort")
                state.value = ProxyState.Connected(relayAddr)
                stats.value = ProxyStats(connectedSince = connectedAt, relayAddr = relayAddr)
                updateNotification("Connected ×$n — $relayAddr")

                // Stats updater: refresh every second
                launch {
                    var prevTo = 0L
                    var prevFrom = 0L
                    while (true) {
                        delay(1000)
                        val to = toServerCounter.get()
                        val from = fromServerCounter.get()
                        stats.value = stats.value.copy(
                            toServerPkts = to,
                            fromServerPkts = from,
                            toServerPps = (to - prevTo).toFloat(),
                            fromServerPps = (from - prevFrom).toFloat(),
                        )
                        prevTo = to
                        prevFrom = from
                        val toFmt = formatPackets(to)
                        val fromFmt = formatPackets(from)
                        updateNotification("↑$toFmt ↓$fromFmt pkts — $relayAddr")
                    }
                }

                repeat(n - 1) { idx ->
                    delay(200L * (idx + 1))
                    launch {
                        runSingleDtlsConnection(
                            rawLink, provider, peerAddr, socket,
                            addrFamily, null, ::appendLog,
                            onToServer = { toServerCounter.incrementAndGet() },
                            onFromServer = { fromServerCounter.incrementAndGet() },
                        )
                    }
                }
            }
            appendLog("All connections closed")
        } catch (e: Exception) {
            val msg = "Error: ${e.javaClass.simpleName}: ${e.message}"
            appendLog(msg)
            state.value = ProxyState.Error(msg)
            updateNotification("Error")
        } finally {
            socket.close()
            @Suppress("DEPRECATION")
            stopForeground(true)
            stopSelf()
        }
    }

    private fun parseAddr(addr: String): InetSocketAddress {
        val lastColon = addr.lastIndexOf(':')
        require(lastColon > 0) { "Invalid address: $addr" }
        return InetSocketAddress(addr.substring(0, lastColon), addr.substring(lastColon + 1).toInt())
    }
}

// ── Single DTLS + TURN connection ──────────────────────────────────────────

@Suppress(
    "TooGenericExceptionCaught",
    "ReturnCount",
    "LongMethod",
    "CyclomaticComplexMethod",
    "LongParameterList"
)
private suspend fun runSingleDtlsConnection(
    rawLink: String,
    provider: CredentialProvider,
    peerAddr: InetSocketAddress,
    socket: DatagramSocket,
    addrFamily: RequestedAddressFamily,
    firstReady: CompletableDeferred<String>?,
    logger: (String) -> Unit,
    onToServer: () -> Unit = {},
    onFromServer: () -> Unit = {},
) {
    val isFirst = firstReady != null
    val silentLogger: (String) -> Unit = { _ -> }

    val creds = try {
        if (isFirst) logger("Getting TURN credentials...")
        provider.getCredentials(rawLink)
    } catch (e: Exception) {
        logger("Credentials failed: ${e.message}")
        firstReady?.completeExceptionally(e)
        return
    }
    if (isFirst) logger("Credentials OK: ${creds.address}")

    val turnAddr = InetSocketAddress(
        creds.address.substringBefore(":"),
        creds.address.substringAfter(":", "3478").toIntOrNull() ?: 3478,
    )

    val turnClient = try {
        if (isFirst) logger("TURN connect to $turnAddr...")
        TurnClient.connect(turnAddr, creds, true, addrFamily, logger = if (isFirst) logger else silentLogger)
    } catch (e: Exception) {
        logger("TURN connect failed: ${e.javaClass.simpleName}: ${e.message}")
        firstReady?.completeExceptionally(e)
        return
    }

    val dtls = DtlsClient()
    try {
        turnClient.allocate()
        if (isFirst) {
            logger("TURN relay: ${turnClient.relayAddress()}")
            logger("TURN channel bound to ${peerAddr.address.hostAddress}:${peerAddr.port}")
        }
        turnClient.channelBind(peerAddr.address.address, peerAddr.port)

        val startMs = System.currentTimeMillis()
        if (isFirst) logger("DTLS handshake via TURN...")
        try {
            dtls.connectOverTurn(turnClient, if (isFirst) logger else silentLogger)
        } catch (e: Exception) {
            logger("DTLS failed: ${e.javaClass.simpleName}: ${e.message}")
            firstReady?.completeExceptionally(e)
            return
        }
        if (isFirst) logger("DTLS handshake OK after ${System.currentTimeMillis() - startMs}ms")

        val relayAddr = turnClient.relayAddress().toString()
        if (firstReady != null) {
            firstReady.complete(relayAddr)
        } else {
            logger("Extra conn relay: $relayAddr")
        }

        val buf = ByteArray(1600)
        val dtlsBuf = ByteArray(1600)
        val lastLocalAddr = AtomicReference<InetSocketAddress>()
        var toServerPkts = 0
        var fromServerPkts = 0

        coroutineScope {
            launch {
                runCatching {
                    while (true) {
                        val pkt = DatagramPacket(buf, buf.size)
                        socket.receive(pkt)
                        val from = InetSocketAddress(pkt.address, pkt.port)
                        lastLocalAddr.set(from)
                        toServerPkts++
                        onToServer()
                        dtls.send(buf.copyOf(pkt.length))
                    }
                }.onFailure {
                    logger("WG→TURN ended after $toServerPkts pkts: ${it.javaClass.simpleName}: ${it.message}")
                }
            }

            @Suppress("LoopWithTooManyJumpStatements")
            launch {
                runCatching {
                    while (true) {
                        val n = dtls.receive(dtlsBuf)
                        if (n < 0) break
                        if (n == 1 && dtlsBuf[0] == 0.toByte()) continue
                        fromServerPkts++
                        onFromServer()
                        val addr = lastLocalAddr.get() ?: continue
                        socket.send(DatagramPacket(dtlsBuf, n, addr))
                    }
                }.onFailure {
                    logger("TURN→WG ended after $fromServerPkts pkts: ${it.javaClass.simpleName}: ${it.message}")
                }
            }
        }
        logger("Relay done: →server=$toServerPkts, ←server=$fromServerPkts pkts")
    } catch (e: Exception) {
        logger("Connection error: ${e.javaClass.simpleName}: ${e.message}")
        firstReady?.completeExceptionally(e)
    } finally {
        dtls.close()
        turnClient.close()
    }
}
