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
import com.github.swerchansky.vkturnproxy.credentials.VkCredentialProvider
import com.github.swerchansky.vkturnproxy.credentials.YandexCredentialProvider
import com.github.swerchansky.vkturnproxy.domain.model.ProxyConnectionState
import com.github.swerchansky.vkturnproxy.domain.model.ProxyStats
import com.github.swerchansky.vkturnproxy.proxy.formatTurnProxyDuration
import com.github.swerchansky.vkturnproxy.proxy.parseTurnProxyAddr
import com.github.swerchansky.vkturnproxy.proxy.runProxyConnections
import com.github.swerchansky.vkturnproxy.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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

    @Inject
    lateinit var vkProvider: VkCredentialProvider
    @Inject
    lateinit var yandexProvider: YandexCredentialProvider

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
        val provider = if (isVk) vkProvider else yandexProvider
        val providerName = if (isVk) "VK" else "Yandex"
        val peerAddr = parseTurnProxyAddr(peerAddress)
        val n = if (nConnections > 0) nConnections else 16
        val sessionStartMs = System.currentTimeMillis()

        appendLog("Provider: $providerName · $n connections · peer: $peerAddress · listen: 127.0.0.1:$listenPort")
        state.value = ProxyConnectionState.Connecting("Resolving DNS", 0, n)
        updateNotification("Connecting...")

        val socket = DatagramSocket(InetSocketAddress("127.0.0.1", listenPort))
        localSocket = socket

        // Tracks ready count for notification text while connecting
        val readyCountRef = AtomicInteger(0)
        val relayAddrRef = AtomicReference("")

        try {
            coroutineScope {
                // Stats updater — starts immediately, reads relayAddrRef once tunnel is up
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
                        val relayAddr = relayAddrRef.get()
                        if (relayAddr.isEmpty()) continue
                        val c = readyCountRef.get()
                        updateNotification(
                            if (c >= n) "↑${formatPackets(to)} ↓${formatPackets(from)} pkts — $relayAddr"
                            else "Establishing $c/$n — ↑${formatPackets(to)} ↓${formatPackets(from)}"
                        )
                    }
                }

                runProxyConnections(
                    link = rawLink,
                    peerAddr = peerAddr,
                    localSocket = socket,
                    provider = provider,
                    nConnections = n,
                    useUdp = true, // TODO: remove this option
                    logger = ::appendLog,
                    onStepChange = { step ->
                        state.value = ProxyConnectionState.Connecting(step, 0, n)
                    },
                    onFirstReady = { relayAddr ->
                        relayAddrRef.set(relayAddr)
                        val connectedAt = System.currentTimeMillis()
                        appendLog(
                            "Tunnel up in " +
                                    "${formatTurnProxyDuration(connectedAt - sessionStartMs)} " +
                                    "· relay: $relayAddr · WG: 127.0.0.1:$listenPort"
                        )
                        stats.value =
                            ProxyStats(connectedSince = connectedAt, relayAddr = relayAddr)
                    },
                    onConnectionReady = { c, total, relayAddr ->
                        readyCountRef.set(c)
                        if (c >= total) {
                            appendLog("All $total/$total connections established")
                            state.value = ProxyConnectionState.Connected(relayAddr)
                            updateNotification("Connected ×$total — $relayAddr")
                        } else {
                            state.value = ProxyConnectionState.Connecting(
                                "Establishing connections",
                                c,
                                total
                            )
                        }
                    },
                    onPacketToServer = { toServerCounter.incrementAndGet() },
                    onPacketFromServer = { fromServerCounter.incrementAndGet() },
                )
            }

            val dur = formatTurnProxyDuration(System.currentTimeMillis() - sessionStartMs)
            val to = toServerCounter.get()
            val from = fromServerCounter.get()
            appendLog(
                "All connections closed · session: $dur · ↑${formatPackets(to)} ↓${
                    formatPackets(
                        from
                    )
                } pkts"
            )
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
}
