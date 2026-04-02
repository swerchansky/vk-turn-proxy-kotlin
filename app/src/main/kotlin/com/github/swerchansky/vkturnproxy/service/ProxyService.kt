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
import com.github.swerchansky.vkturnproxy.domain.model.ProxyConnectionState
import com.github.swerchansky.vkturnproxy.domain.model.ProxyStats
import com.github.swerchansky.vkturnproxy.dtls.DtlsClient
import com.github.swerchansky.vkturnproxy.turn.RequestedAddressFamily
import com.github.swerchansky.vkturnproxy.turn.TurnClient
import com.github.swerchansky.vkturnproxy.ui.main.MainActivity
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

class ProxyService : Service() {

    companion object {
        val state = MutableStateFlow<ProxyConnectionState>(ProxyConnectionState.Idle)
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

        fun formatPackets(count: Long): String = when {
            count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000f)
            count >= 1_000 -> String.format("%.1fK", count / 1_000f)
            else -> count.toString()
        }

        fun formatDuration(ms: Long): String {
            val s = ms / 1000
            return when {
                s < 60 -> "${s}s"
                s < 3600 -> "%dm%02ds".format(s / 60, s % 60)
                else -> "%dh%02dm".format(s / 3600, (s % 3600) / 60)
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
        state.value = ProxyConnectionState.Idle
        stats.value = ProxyStats()
        scope.cancel()
    }

    private fun stopProxy() {
        val to = toServerCounter.get()
        val from = fromServerCounter.get()
        val dur = stats.value.connectedSince
            .let { if (it > 0) " · ${formatDuration(System.currentTimeMillis() - it)}" else "" }
        proxyJob?.cancel()
        proxyJob = null
        localSocket?.close()
        localSocket = null
        appendLog("Disconnected$dur · ↑${formatPackets(to)} ↓${formatPackets(from)} pkts")
        state.value = ProxyConnectionState.Idle
        stats.value = ProxyStats()
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    private fun appendLog(line: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
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

    private suspend fun runProxy(
        rawLink: String,
        peerAddress: String,
        listenPort: Int,
        isVk: Boolean,
        nConnections: Int,
    ) {
        val provider: CredentialProvider = if (isVk) vkProvider else yandexProvider
        val providerName = if (isVk) "VK" else "Yandex"
        val peerAddr = parseAddr(peerAddress)
        val n = if (nConnections > 0) nConnections else 16
        val addrFamily = if (peerAddr.address.address.size == 4) RequestedAddressFamily.IPv4
        else RequestedAddressFamily.IPv6

        val readyCount = AtomicInteger(0)
        val sessionStartMs = System.currentTimeMillis()

        appendLog("Provider: $providerName · $n connections · peer: $peerAddress · listen: 127.0.0.1:$listenPort")
        state.value = ProxyConnectionState.Connecting("Resolving DNS", 0, n)
        updateNotification("Connecting...")

        val socket = DatagramSocket(InetSocketAddress("127.0.0.1", listenPort))
        localSocket = socket

        try {
            coroutineScope {
                val firstReady = CompletableDeferred<String>()

                // First connection: full step logging
                launch {
                    runSingleDtlsConnection(
                        connIndex = 1,
                        connTotal = n,
                        rawLink = rawLink,
                        provider = provider,
                        peerAddr = peerAddr,
                        socket = socket,
                        addrFamily = addrFamily,
                        firstReady = firstReady,
                        onStepChange = { step ->
                            state.value = ProxyConnectionState.Connecting(step, 0, n)
                        },
                        onReady = { relayAddr ->
                            val c = readyCount.incrementAndGet()
                            onConnectionReady(c, n, relayAddr)
                        },
                        logger = ::appendLog,
                        onToServer = { toServerCounter.incrementAndGet() },
                        onFromServer = { fromServerCounter.incrementAndGet() },
                    )
                }

                val relayAddr = firstReady.await()
                val connectedAt = System.currentTimeMillis()
                appendLog("Tunnel up in ${formatDuration(connectedAt - sessionStartMs)} · relay: $relayAddr · WG: 127.0.0.1:$listenPort")
                stats.value = ProxyStats(connectedSince = connectedAt, relayAddr = relayAddr)

                // Stats updater (notification only, no log spam)
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
                        val c = readyCount.get()
                        val notifText = if (c >= n) {
                            "↑${formatPackets(to)} ↓${formatPackets(from)} pkts — $relayAddr"
                        } else {
                            "Establishing $c/$n — ↑${formatPackets(to)} ↓${formatPackets(from)}"
                        }
                        updateNotification(notifText)
                    }
                }

                // Start N-1 additional connections with staggered delay
                // Each logs only its own completion/failure
                repeat(n - 1) { idx ->
                    val connIdx = idx + 2
                    delay(200L * (idx + 1))
                    launch {
                        runSingleDtlsConnection(
                            connIndex = connIdx,
                            connTotal = n,
                            rawLink = rawLink,
                            provider = provider,
                            peerAddr = peerAddr,
                            socket = socket,
                            addrFamily = addrFamily,
                            firstReady = null,
                            onStepChange = null,
                            onReady = { _ ->
                                val c = readyCount.incrementAndGet()
                                onConnectionReady(c, n, relayAddr)
                            },
                            logger = ::appendLog,
                            onToServer = { toServerCounter.incrementAndGet() },
                            onFromServer = { fromServerCounter.incrementAndGet() },
                        )
                    }
                }
            }

            val dur = formatDuration(System.currentTimeMillis() - sessionStartMs)
            val to = toServerCounter.get()
            val from = fromServerCounter.get()
            appendLog("All connections closed · session: $dur · ↑${formatPackets(to)} ↓${formatPackets(from)} pkts")
        } catch (e: Exception) {
            val msg = "${e.javaClass.simpleName}: ${e.message}"
            appendLog("Error: $msg")
            state.value = ProxyConnectionState.Error(msg)
            updateNotification("Error")
        } finally {
            socket.close()
            @Suppress("DEPRECATION")
            stopForeground(true)
            stopSelf()
        }
    }

    private fun onConnectionReady(c: Int, n: Int, relayAddr: String) {
        if (c >= n) {
            appendLog("All $n/$n connections established")
            state.value = ProxyConnectionState.Connected(relayAddr)
            updateNotification("Connected ×$n — $relayAddr")
        } else {
            state.value = ProxyConnectionState.Connecting("Establishing connections", c, n)
        }
    }

    private fun parseAddr(addr: String): InetSocketAddress {
        val lastColon = addr.lastIndexOf(':')
        require(lastColon > 0) { "Invalid address: $addr" }
        return InetSocketAddress(addr.substring(0, lastColon), addr.substring(lastColon + 1).toInt())
    }
}

// ── Single DTLS + TURN connection ──────────────────────────────────────────

private suspend fun runSingleDtlsConnection(
    connIndex: Int,
    connTotal: Int,
    rawLink: String,
    provider: CredentialProvider,
    peerAddr: InetSocketAddress,
    socket: DatagramSocket,
    addrFamily: RequestedAddressFamily,
    firstReady: CompletableDeferred<String>?,
    onStepChange: ((String) -> Unit)?,
    onReady: (String) -> Unit,
    logger: (String) -> Unit,
    onToServer: () -> Unit = {},
    onFromServer: () -> Unit = {},
) {
    val isFirst = firstReady != null
    val tag = "[$connIndex/$connTotal]"
    val startMs = System.currentTimeMillis()

    // ── Step 1: Credentials ────────────────────────────────────────────────
    onStepChange?.invoke("Resolving DNS")
    val creds = try {
        if (isFirst) logger("$tag Getting TURN credentials...")
        provider.getCredentials(rawLink)
    } catch (e: Exception) {
        logger("$tag Credentials failed: ${e.message}")
        firstReady?.completeExceptionally(e)
        return
    }

    val turnAddr = InetSocketAddress(
        creds.address.substringBefore(":"),
        creds.address.substringAfter(":", "3478").toIntOrNull() ?: 3478,
    )
    if (isFirst) logger("$tag Credentials OK · TURN: $turnAddr · user: ${creds.username}")

    // ── Step 2: TURN allocation ────────────────────────────────────────────
    onStepChange?.invoke("Connecting TURN")
    val turnClient = try {
        if (isFirst) logger("$tag Connecting to TURN (TCP)...")
        TurnClient.connect(turnAddr, creds, true, addrFamily)
    } catch (e: Exception) {
        logger("$tag TURN connect failed: ${e.javaClass.simpleName}: ${e.message}")
        firstReady?.completeExceptionally(e)
        return
    }

    val dtls = DtlsClient()
    try {
        turnClient.allocate()
        val relayStr = turnClient.relayAddress().toString()
        if (isFirst) logger("$tag TURN relay: $relayStr · channel → ${peerAddr.address.hostAddress}:${peerAddr.port}")
        turnClient.channelBind(peerAddr.address.address, peerAddr.port)

        // ── Step 3: DTLS handshake ─────────────────────────────────────────
        onStepChange?.invoke("DTLS Handshake")
        val dtlsStart = System.currentTimeMillis()
        if (isFirst) logger("$tag DTLS handshake...")
        try {
            dtls.connectOverTurn(turnClient)
        } catch (e: Exception) {
            logger("$tag DTLS failed: ${e.javaClass.simpleName}: ${e.message}")
            firstReady?.completeExceptionally(e)
            return
        }
        val dtlsMs = System.currentTimeMillis() - dtlsStart

        if (isFirst) {
            logger("$tag DTLS OK in ${dtlsMs}ms · setup: ${System.currentTimeMillis() - startMs}ms total")
        } else {
            logger("$tag Connected ✓ relay: $relayStr · ${System.currentTimeMillis() - startMs}ms")
        }

        val relayAddr = turnClient.relayAddress().toString()
        firstReady?.complete(relayAddr)
        onReady(relayAddr)

        // ── Step 4: Relay (no per-packet logs) ────────────────────────────
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
                        lastLocalAddr.set(InetSocketAddress(pkt.address, pkt.port))
                        toServerPkts++
                        onToServer()
                        dtls.send(buf.copyOf(pkt.length))
                    }
                }.onFailure {
                    if (isFirst) logger("$tag WG→TURN closed: ${it.javaClass.simpleName}")
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
                    if (isFirst) logger("$tag TURN→WG closed: ${it.javaClass.simpleName}")
                }
            }
        }

        val dur = ProxyService.formatDuration(System.currentTimeMillis() - startMs)
        logger("$tag Relay done · ↑$toServerPkts ↓$fromServerPkts pkts · up: $dur")
    } catch (e: Exception) {
        logger("$tag Connection error: ${e.javaClass.simpleName}: ${e.message}")
        firstReady?.completeExceptionally(e)
    } finally {
        dtls.close()
        turnClient.close()
    }
}
