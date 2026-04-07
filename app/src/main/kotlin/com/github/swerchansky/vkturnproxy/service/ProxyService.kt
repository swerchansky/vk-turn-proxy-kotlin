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
import com.github.swerchansky.vkturnproxy.credentials.vk.VkCaptchaHandler
import com.github.swerchansky.vkturnproxy.credentials.vk.VkCredentialProvider
import com.github.swerchansky.vkturnproxy.domain.ProxyConnectionState
import com.github.swerchansky.vkturnproxy.domain.ProxyStats
import com.github.swerchansky.vkturnproxy.logging.AndroidProxyLogger
import com.github.swerchansky.vkturnproxy.proxy.TunnelEvent
import com.github.swerchansky.vkturnproxy.proxy.TunnelParams
import com.github.swerchansky.vkturnproxy.proxy.TurnProxyEngine
import com.github.swerchansky.vkturnproxy.proxy.formatTurnProxyDuration
import com.github.swerchansky.vkturnproxy.proxy.parseTurnProxyAddr
import com.github.swerchansky.vkturnproxy.ui.main.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
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
        const val EXTRA_N = "n_connections"

        @Volatile
        private var captchaDeferred: CompletableDeferred<String>? = null
        @Volatile
        private var captchaCancelled = false
        private val captchaMutex = Mutex()

        // Tracked while runProxy is active so cancelCaptcha() can transition immediately.
        @Volatile
        private var liveReadyCount = 0
        @Volatile
        private var liveTotalConnections = 0
        @Volatile
        private var liveRelayAddr = ""

        fun submitCaptchaResult(successToken: String) {
            captchaDeferred?.complete(successToken)
        }

        fun cancelCaptcha() {
            captchaCancelled = true
            captchaDeferred?.completeExceptionally(CaptchaSkippedException())
            // Immediately transition to Connected/Idle so the UI isn't stuck in Connecting
            // while the remaining connections time out in the background.
            val ready = liveReadyCount
            val total = liveTotalConnections
            val relay = liveRelayAddr
            if (ready > 0 && relay.isNotEmpty()) {
                state.value = ProxyConnectionState.Connected(relay, ready, total)
            }
        }

        class CaptchaSkippedException : Exception("Captcha skipped by user")

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
    lateinit var credentialProvider: VkCredentialProvider
    @Inject
    lateinit var captchaHandler: VkCaptchaHandler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var proxyJob: Job? = null
    private var localSocket: DatagramSocket? = null

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
                val n = intent.getIntExtra(EXTRA_N, 0)
                startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))
                proxyJob?.cancel()
                stats.value = ProxyStats()
                proxyJob = scope.launch { runProxy(link, peer, port, n) }
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
        val currentStats = stats.value
        val dur = currentStats.connectedSince
            .let { if (it > 0) " · ${formatDuration(System.currentTimeMillis() - it)}" else "" }
        appendLog(
            "Disconnected$dur · ↑${formatPackets(currentStats.toServerPkts)} ↓${
                formatPackets(
                    currentStats.fromServerPkts
                )
            } pkts"
        )
        proxyJob?.cancel()
        proxyJob = null
        localSocket?.close()
        localSocket = null
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

    @Suppress("LongMethod")
    private suspend fun runProxy(
        rawLink: String,
        peerAddress: String,
        listenPort: Int,
        nConnections: Int,
    ) {
        captchaDeferred = null
        captchaCancelled = false
        liveReadyCount = 0
        liveTotalConnections = if (nConnections > 0) nConnections else 16
        liveRelayAddr = ""
        captchaHandler.onFallbackRequired = { captchaUrl ->
            if (captchaCancelled) throw CaptchaSkippedException()
            val myDeferred = CompletableDeferred<String>()
            captchaMutex.withLock {
                if (captchaCancelled) throw CaptchaSkippedException()
                captchaDeferred = myDeferred
                state.value = ProxyConnectionState.CaptchaRequired(captchaUrl)
                myDeferred.await()
            }
        }

        val peerAddr = parseTurnProxyAddr(peerAddress)
        val n = if (nConnections > 0) nConnections else 16
        val sessionStartMs = System.currentTimeMillis()

        appendLog("Provider: VK · $n connections · peer: $peerAddress · listen: 127.0.0.1:$listenPort")
        state.value = ProxyConnectionState.Connecting("Resolving DNS", 0, n)
        updateNotification("Connecting...")

        val socket = DatagramSocket(InetSocketAddress("127.0.0.1", listenPort))
        localSocket = socket

        val readyCountRef = AtomicInteger(0)
        val relayAddrRef = AtomicReference("")

        val proxyLogger = AndroidProxyLogger(onUiLog = ::appendLog)
        val engine = TurnProxyEngine(credentialProvider, proxyLogger)

        try {
            val statsJob = scope.launch {
                var prevTo = 0L
                var prevFrom = 0L
                while (true) {
                    delay(1000)
                    val to = engine.toServerPackets.get()
                    val from = engine.fromServerPackets.get()
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

            try {
                engine.runConnections(
                    TunnelParams(
                        link = rawLink,
                        peerAddr = peerAddr,
                        localSocket = socket,
                        nConnections = n,
                    )
                ).collect { event ->
                    when (event) {
                        is TunnelEvent.StepChanged ->
                            state.value = ProxyConnectionState.Connecting(event.step, 0, n)

                        is TunnelEvent.FirstReady -> {
                            relayAddrRef.set(event.relayAddr)
                            liveRelayAddr = event.relayAddr
                            val connectedAt = System.currentTimeMillis()
                            appendLog(
                                "Tunnel up in ${formatTurnProxyDuration(connectedAt - sessionStartMs)}" +
                                        " · relay: ${event.relayAddr} · WG: 127.0.0.1:$listenPort"
                            )
                            stats.value = ProxyStats(
                                connectedSince = connectedAt,
                                relayAddr = event.relayAddr,
                            )
                        }

                        is TunnelEvent.ConnectionReady -> {
                            readyCountRef.set(event.count)
                            liveReadyCount = event.count
                            if (liveRelayAddr.isEmpty()) liveRelayAddr = event.relayAddr
                            val done = event.count >= event.total || event.allSettled
                            if (done) {
                                val msg = if (event.count < event.total)
                                    "${event.count}/${event.total} connections established (${event.total - event.count} failed)"
                                else
                                    "All ${event.total}/${event.total} connections established"
                                appendLog(msg)
                                state.value = ProxyConnectionState.Connected(
                                    event.relayAddr, event.count, event.total
                                )
                                updateNotification("Connected ×${event.count} — ${event.relayAddr}")
                            } else {
                                state.value = ProxyConnectionState.Connecting(
                                    "Establishing connections", event.count, event.total
                                )
                            }
                        }
                    }
                }
            } finally {
                statsJob.cancel()
            }

            val dur = formatTurnProxyDuration(System.currentTimeMillis() - sessionStartMs)
            val to = engine.toServerPackets.get()
            val from = engine.fromServerPackets.get()
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
            captchaHandler.onFallbackRequired = null
            captchaDeferred = null
            socket.close()
            @Suppress("DEPRECATION")
            stopForeground(true)
            stopSelf()
        }
    }
}
