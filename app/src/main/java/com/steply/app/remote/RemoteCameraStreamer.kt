package com.steply.app.remote

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.steply.app.sync.SteplyTlsClientFactory
import com.steply.app.sync.SteplyWebSessionPayload
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import com.steply.app.domain.model.ASSESSMENT_SESSION_SCHEMA_VERSION
import com.steply.app.sync.LANDMARK_SERIES_FINALIZED_TYPE
import com.steply.app.care.CARE_AGENT_STATE_SCHEMA_VERSION
import com.steply.app.care.CareAgentProjection
import com.steply.app.care.CareAgentProjectionFactory
import com.steply.app.care.CareAgentProjectionJsonCodec
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

private const val MAX_PENDING_BYTES = 1_500_000L
private const val MAX_FRAME_AGE_MS = 500L

/**
 * Sends JPEG camera frames from Android to the local Steply PC WebSocket server.
 * It also listens for final PC analysis results so the phone can keep a local history.
 */
class RemoteCameraStreamer(
    private val session: SteplyWebSessionPayload,
    private val onStatus: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val assessmentSessionId: String? = null,
    private val lastRevision: () -> Long = { 0L },
    private val sessionSnapshot: () -> String? = { null },
    private val careAgentProjection: () -> CareAgentProjection? = { null },
    private val onAssessmentUpdate: (String) -> Unit = {},
    private val onLandmarkSeries: (String) -> Unit = {},
    private val onCameraFrameAck: (RemoteCameraFrameAck) -> Unit = {},
    private val onFinalResult: (String) -> Unit = {},
) : AutoCloseable {
    private val serverUrl = session.webSocketUrl
    private val client = SteplyTlsClientFactory.build(
        baseClient = OkHttpClient(),
        tlsCertSha256 = session.tlsCertSha256,
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var connected = false
    @Volatile private var closeRequested = false
    @Volatile private var reconnectAttempt = 0
    private var reconnectRunnable: Runnable? = null
    private val mobileFrameSequence = AtomicLong(0L)
    private val acknowledgedCareStateVersion = AtomicLong(0L)
    @Volatile private var careResumeCompleted = false

    fun connect() {
        closeRequested = false
        reconnectAttempt = 0
        openSocket()
    }

    private fun openSocket() {
        if (connected || webSocket != null) return
        if (!serverUrl.startsWith("wss://", ignoreCase = true)) {
            emitError("Refusing to stream camera frames over an unencrypted WebSocket. Scan a fresh HTTPS QR code.")
            return
        }
        if (session.isExpired() && assessmentSessionId == null) {
            emitError("The PC pairing QR code has expired. Refresh the QR code on the PC and scan it again.")
            return
        }

        val request = Request.Builder()
            .url(serverUrl)
            .build()

        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    connected = true
                    reconnectAttempt = 0
                    val hello = JSONObject()
                        .put("type", "hello")
                        .put("role", "sender")
                        .put("source", "android")
                        .toString()
                    webSocket.send(hello)
                    if (assessmentSessionId != null) {
                        val snapshot = sessionSnapshot()?.let { runCatching { JSONObject(it) }.getOrNull() }
                        val resume = JSONObject()
                            .put("type", "assessment-session.resume")
                            .put("schemaVersion", ASSESSMENT_SESSION_SCHEMA_VERSION)
                            .put("messageId", UUID.randomUUID().toString())
                            .put("assessmentSessionId", assessmentSessionId)
                            .put("knownRevision", lastRevision())
                            .put("session", snapshot ?: JSONObject.NULL)
                        webSocket.send(resume.toString())
                    }
                    careResumeCompleted = false
                    careAgentProjection()?.let { projection ->
                        webSocket.send(
                            JSONObject()
                                .put("type", "care-agent.resume")
                                .put("schemaVersion", CARE_AGENT_STATE_SCHEMA_VERSION)
                                .put("messageId", "care-resume:${projection.profileId}:${projection.stateVersion}")
                                .put("profileId", projection.profileId)
                                .put("knownStateVersion", acknowledgedCareStateVersion.get())
                                .toString(),
                        )
                    }
                    emitStatus("PC connected: $serverUrl")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        val json = JSONObject(text)
                        when (json.optString("type")) {
                            "assessment-session.updated" -> {
                                mainHandler.post { onAssessmentUpdate(text) }
                                emitStatus("Assessment update received from PC.")
                            }
                            LANDMARK_SERIES_FINALIZED_TYPE -> {
                                mainHandler.post { onLandmarkSeries(text) }
                                emitStatus("Landmark series received from PC.")
                            }
                            "remote-camera-frame-ack" -> {
                                parseRemoteCameraFrameAck(json)?.let { ack ->
                                    mainHandler.post { onCameraFrameAck(ack) }
                                }
                            }
                            "final" -> {
                                val result = json.optJSONObject("result") ?: return@runCatching
                                mainHandler.post { onFinalResult(result.toString()) }
                            }
                            "care-agent.ack" -> {
                                acknowledgedCareStateVersion.set(json.optLong("stateVersion", 0L))
                                careResumeCompleted = true
                                sendLatestCareAgentProjection()
                            }
                            "care-agent.projection" -> {
                                val projection = json.optJSONObject("projection")
                                val webState = projection?.let {
                                    runCatching { CareAgentProjectionJsonCodec.decodeProjection(it.toString()) }.getOrNull()
                                }
                                acknowledgedCareStateVersion.set(webState?.stateVersion ?: json.optLong("stateVersion", 0L))
                                careResumeCompleted = true
                                sendLatestCareAgentProjection()
                            }
                            "care-agent.error" -> {
                                val projection = json.optJSONObject("projection")
                                acknowledgedCareStateVersion.set(projection?.optLong("stateVersion", 0L) ?: 0L)
                                careResumeCompleted = true
                                sendLatestCareAgentProjection()
                            }
                        }
                    }.onFailure { error ->
                        Log.w("RemoteCamera", "Failed to parse websocket message: ${error.message}")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    connected = false
                    this@RemoteCameraStreamer.webSocket = null
                    Log.e("RemoteCamera", "onFailure: ${t.message}", t)
                    scheduleReconnect("PC connection interrupted: ${t.message ?: "unknown error"}")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    connected = false
                    this@RemoteCameraStreamer.webSocket = null
                    Log.d("RemoteCamera", "onClosed: $code / $reason")
                    if (!closeRequested) scheduleReconnect("PC connection closed: $code / $reason")
                }
            },
        )
    }

    fun sendJpeg(
        bytes: ByteArray,
        capturedAtMs: Long = SystemClock.uptimeMillis(),
        maxFrameAgeMs: Long = MAX_FRAME_AGE_MS,
    ): Boolean {
        val socket = webSocket
        Log.d(
            "RemoteCamera",
            "sendJpeg called: bytes=${bytes.size}, socketNull=${socket == null}, connected=$connected",
        )

        if (socket == null) return false
        if (!connected) return false
        if (SystemClock.uptimeMillis() - capturedAtMs > maxFrameAgeMs) {
            Log.w("RemoteCamera", "dropping stale frame before websocket send")
            return false
        }

        // Avoid piling up stale frames. If the network cannot keep up, drop this frame
        // and let CameraX provide the latest one on the next analyzer callback.
        if (socket.queueSize() > MAX_PENDING_BYTES) {
            Log.w("RemoteCamera", "dropping frame because websocket queue is ${socket.queueSize()} bytes")
            return false
        }

        val sequence = mobileFrameSequence.incrementAndGet()
        val metadataSent = socket.send(buildCameraFrameMetadata(
            mobileSequence = sequence,
            capturedAtUptimeMs = capturedAtMs,
            sentAtEpochMs = System.currentTimeMillis(),
            byteLength = bytes.size,
        ))
        if (!metadataSent) return false
        val result = socket.send(bytes.toByteString())
        Log.d("RemoteCamera", "websocket send result=$result")
        return result
    }

    fun sendAssessmentAck(messageId: String, assessmentId: String, revision: Long): Boolean {
        val socket = webSocket ?: return false
        if (!connected) return false
        return socket.send(
            JSONObject()
                .put("type", "assessment-session.ack")
                .put("schemaVersion", ASSESSMENT_SESSION_SCHEMA_VERSION)
                .put("messageId", messageId)
                .put("assessmentSessionId", assessmentId)
                .put("revision", revision)
                .toString(),
        )
    }

    fun sendLandmarkSeriesAck(ackJson: String): Boolean {
        val socket = webSocket ?: return false
        if (!connected) return false
        return socket.send(ackJson)
    }

    fun sendLatestCareAgentProjection(): Boolean {
        val socket = webSocket ?: return false
        if (!connected || !careResumeCompleted) return false
        val projection = careAgentProjection() ?: return false
        val baseVersion = acknowledgedCareStateVersion.get()
        if (projection.stateVersion <= baseVersion) return true
        val update = CareAgentProjectionFactory.update(baseVersion, projection)
        return socket.send(CareAgentProjectionJsonCodec.encodeUpdate(update))
    }

    private fun scheduleReconnect(reason: String) {
        if (closeRequested) return
        if (reconnectAttempt >= RECONNECT_DELAYS_MS.size) {
            emitError("$reason. Automatic reconnect limit reached; tap Start camera stream to retry.")
            return
        }
        val delayMs = RECONNECT_DELAYS_MS[reconnectAttempt]
        reconnectAttempt += 1
        emitStatus("$reason. Reconnecting (${reconnectAttempt}/${RECONNECT_DELAYS_MS.size})...")
        reconnectRunnable?.let(mainHandler::removeCallbacks)
        reconnectRunnable = Runnable {
            reconnectRunnable = null
            if (!closeRequested) openSocket()
        }.also { mainHandler.postDelayed(it, delayMs) }
    }

    private fun emitStatus(message: String) {
        mainHandler.post { onStatus(message) }
    }

    private fun emitError(message: String) {
        mainHandler.post { onError(message) }
    }

    fun disconnect() {
        closeRequested = true
        reconnectRunnable?.let(mainHandler::removeCallbacks)
        reconnectRunnable = null
        connected = false
        careResumeCompleted = false
        webSocket?.send(JSONObject().put("type", "stopped").toString())
        webSocket?.close(1000, "Android camera stopped")
        webSocket = null
    }

    override fun close() {
        disconnect()
        client.dispatcher.executorService.shutdown()
    }
}

private val RECONNECT_DELAYS_MS = longArrayOf(1_000L, 2_000L, 4_000L)

internal fun buildCameraFrameMetadata(
    mobileSequence: Long,
    capturedAtUptimeMs: Long,
    sentAtEpochMs: Long,
    byteLength: Int,
): String = JSONObject()
    .put("type", "camera-frame-meta")
    .put("mobileSequence", mobileSequence)
    .put("capturedAtUptimeMs", capturedAtUptimeMs)
    .put("sentAtEpochMs", sentAtEpochMs)
    .put("byteLength", byteLength)
    .toString()

data class RemoteCameraFrameAck(
    val sequence: Long?,
    val mobileSequence: Long?,
    val source: String,
    val receivedAt: Long?,
    val analyzedAt: Long?,
)

internal fun parseRemoteCameraFrameAck(json: JSONObject): RemoteCameraFrameAck? {
    if (json.optString("type") != "remote-camera-frame-ack") return null
    val source = (json.opt("source") as? String)?.takeIf { it.isNotBlank() } ?: return null
    val sequence = json.nonNegativeLongOrNull("sequence")
    val mobileSequence = json.nonNegativeLongOrNull("mobileSequence")
    if (sequence == null && mobileSequence == null) return null
    return RemoteCameraFrameAck(
        sequence = sequence,
        mobileSequence = mobileSequence,
        source = source,
        receivedAt = json.nonNegativeLongOrNull("receivedAt"),
        analyzedAt = json.nonNegativeLongOrNull("analyzedAt"),
    )
}

private fun JSONObject.nonNegativeLongOrNull(name: String): Long? {
    val value = (opt(name) as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
    val long = value.toLong()
    return long.takeIf { value == it.toDouble() }
}
