package com.github.swerchansky.vkturnproxy.credentials

import com.github.swerchansky.vkturnproxy.turn.TurnCredentials
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.call.body
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0"

private val json = Json { ignoreUnknownKeys = true }

/**
 * Extracts TURN credentials from a Yandex Telemost invite link.
 *
 * Flow:
 *  1. GET cloud-api.yandex.ru  → conference info (wss URL, roomId, peerId, credentials)
 *  2. WebSocket connect to media server
 *  3. Send "hello" message
 *  4. Parse "serverHello" response → ICE servers → filter UDP TURN
 */
class YandexCredentialProvider(private val client: HttpClient) : CredentialProvider {

    override suspend fun getCredentials(link: String): TurnCredentials {
        // Step 1: conference info
        val confPath = "/telemost_front/v2/telemost/conferences/" +
                "https%3A%2F%2Ftelemost.yandex.ru%2Fj%2F$link/connection" +
                "?next_gen_media_platform_allowed=false"
        val confJson: String = client.get("https://cloud-api.yandex.ru$confPath") {
            headers {
                append("User-Agent", USER_AGENT)
                append("Referer", "https://telemost.yandex.ru/")
                append("Origin", "https://telemost.yandex.ru")
                append("Client-Instance-Id", generateUUID())
            }
        }.body()

        val confObj = json.parseToJsonElement(confJson).jsonObject
        val wssUrl = confObj["client_configuration"]!!.jsonObject["media_server_url"]!!.jsonPrimitive.content
        val roomId = confObj["room_id"]!!.jsonPrimitive.content
        val peerId = confObj["peer_id"]!!.jsonPrimitive.content
        val confCreds = confObj["credentials"]!!.jsonPrimitive.content

        // Step 2 & 3: WebSocket + hello
        var result: TurnCredentials? = null
        client.webSocket(
            urlString = wssUrl,
            request = {
                headers {
                    append("Origin", "https://telemost.yandex.ru")
                    append("User-Agent", USER_AGENT)
                }
            }
        ) {
            val hello = buildHelloMessage(peerId, roomId, confCreds)
            send(Frame.Text(json.encodeToString(hello)))

            // Step 4: read until serverHello
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val text = frame.readText()
                val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue

                // Skip ACK messages
                if (obj.containsKey("ack")) continue

                // Parse serverHello
                val serverHello = obj["serverHello"]?.jsonObject ?: continue
                val iceServers = serverHello["rtcConfiguration"]
                    ?.jsonObject?.get("iceServers")?.jsonArray ?: continue

                for (server in iceServers) {
                    val serverObj = server.jsonObject
                    val urls = serverObj["urls"]?.let { parseFlexUrls(it) } ?: continue
                    val username = serverObj["username"]?.jsonPrimitive?.content ?: continue
                    val credential = serverObj["credential"]?.jsonPrimitive?.content ?: continue

                    for (url in urls) {
                        if (!url.startsWith("turn:") && !url.startsWith("turns:")) continue
                        if (url.contains("transport=tcp")) continue
                        val address = url.split("?")[0]
                            .removePrefix("turns:")
                            .removePrefix("turn:")
                        result = TurnCredentials(username, credential, address)
                        return@webSocket
                    }
                }
            }
        }

        return result ?: error("No UDP TURN server found in Yandex Telemost response")
    }

    private fun parseFlexUrls(element: kotlinx.serialization.json.JsonElement): List<String> =
        when (element) {
            is kotlinx.serialization.json.JsonArray -> element.map { it.jsonPrimitive.content }
            else -> listOf(element.jsonPrimitive.content)
        }
}

// ── Hello message serialization ───────────────────────────────────────────

private fun buildHelloMessage(participantId: String, roomId: String, credentials: String): HelloRequest =
    HelloRequest(
        uid = generateUUID(),
        hello = HelloPayload(
            participantMeta = PartMeta(name = "Гость", role = "SPEAKER"),
            participantAttributes = PartAttrs(name = "Гость", role = "SPEAKER"),
            participantId = participantId,
            roomId = roomId,
            serviceName = "telemost",
            credentials = credentials,
            sdkInfo = SdkInfo(
                implementation = "browser",
                version = "5.15.0",
                userAgent = USER_AGENT,
                hwConcurrency = 4,
            ),
            sdkInitializationId = generateUUID(),
            capabilitiesOffer = buildCapabilities(),
        ),
    )

private fun buildCapabilities() = Capabilities(
    offerAnswerMode = listOf("SEPARATE"),
    initialSubscriberOffer = listOf("ON_HELLO"),
    slotsMode = listOf("FROM_CONTROLLER"),
    simulcastMode = listOf("DISABLED"),
    selfVadStatus = listOf("FROM_SERVER"),
    dataChannelSharing = listOf("TO_RTP"),
    videoEncoderConfig = listOf("NO_CONFIG"),
    dataChannelVideoCodec = listOf("VP8"),
    bandwidthLimitationReason = listOf("BANDWIDTH_REASON_DISABLED"),
    sdkDefaultDeviceManagement = listOf("SDK_DEFAULT_DEVICE_MANAGEMENT_DISABLED"),
    joinOrderLayout = listOf("JOIN_ORDER_LAYOUT_DISABLED"),
    pinLayout = listOf("PIN_LAYOUT_DISABLED"),
    sendSelfViewVideoSlot = listOf("SEND_SELF_VIEW_VIDEO_SLOT_DISABLED"),
    serverLayoutTransition = listOf("SERVER_LAYOUT_TRANSITION_DISABLED"),
    sdkPublisherOptimizeBitrate = listOf("SDK_PUBLISHER_OPTIMIZE_BITRATE_DISABLED"),
    sdkNetworkLostDetection = listOf("SDK_NETWORK_LOST_DETECTION_DISABLED"),
    sdkNetworkPathMonitor = listOf("SDK_NETWORK_PATH_MONITOR_DISABLED"),
    publisherVp9 = listOf("PUBLISH_VP9_DISABLED"),
    svcMode = listOf("SVC_MODE_DISABLED"),
    subscriberOfferAsyncAck = listOf("SUBSCRIBER_OFFER_ASYNC_ACK_DISABLED"),
    svcModes = listOf("FALSE"),
    reportTelemetryModes = listOf("TRUE"),
    keepDefaultDevicesModes = listOf("TRUE"),
)

@Serializable private data class HelloRequest(val uid: String, val hello: HelloPayload)
@Serializable private data class HelloPayload(
    val participantMeta: PartMeta,
    val participantAttributes: PartAttrs,
    val sendAudio: Boolean = false,
    val sendVideo: Boolean = false,
    val sendSharing: Boolean = false,
    val participantId: String,
    val roomId: String,
    val serviceName: String,
    val credentials: String,
    val sdkInfo: SdkInfo,
    val sdkInitializationId: String,
    val disablePublisher: Boolean = false,
    val disableSubscriber: Boolean = false,
    val disableSubscriberAudio: Boolean = false,
    val capabilitiesOffer: Capabilities,
)
@Serializable private data class PartMeta(val name: String, val role: String, val description: String = "", val sendAudio: Boolean = false, val sendVideo: Boolean = false)
@Serializable private data class PartAttrs(val name: String, val role: String, val description: String = "")
@Serializable private data class SdkInfo(val implementation: String, val version: String, val userAgent: String, val hwConcurrency: Int)
@Serializable private data class Capabilities(
    val offerAnswerMode: List<String>,
    val initialSubscriberOffer: List<String>,
    val slotsMode: List<String>,
    val simulcastMode: List<String>,
    val selfVadStatus: List<String>,
    val dataChannelSharing: List<String>,
    val videoEncoderConfig: List<String>,
    val dataChannelVideoCodec: List<String>,
    val bandwidthLimitationReason: List<String>,
    val sdkDefaultDeviceManagement: List<String>,
    val joinOrderLayout: List<String>,
    val pinLayout: List<String>,
    val sendSelfViewVideoSlot: List<String>,
    val serverLayoutTransition: List<String>,
    val sdkPublisherOptimizeBitrate: List<String>,
    val sdkNetworkLostDetection: List<String>,
    val sdkNetworkPathMonitor: List<String>,
    val publisherVp9: List<String>,
    val svcMode: List<String>,
    val subscriberOfferAsyncAck: List<String>,
    val svcModes: List<String>,
    val reportTelemetryModes: List<String>,
    val keepDefaultDevicesModes: List<String>,
)
