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

    fun connect() {
        if (connected || webSocket != null) return
        if (!serverUrl.startsWith("wss://", ignoreCase = true)) {
            emitError("Refusing to stream camera frames over an unencrypted WebSocket. Scan a fresh HTTPS QR code.")
            return
        }
        if (session.isExpired()) {
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
                    val hello = JSONObject()
                        .put("type", "hello")
                        .put("role", "sender")
                        .put("source", "android")
                        .toString()
                    webSocket.send(hello)
                    emitStatus("PC connected: $serverUrl")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        val json = JSONObject(text)
                        if (json.optString("type") == "final") {
                            val result = json.optJSONObject("result") ?: return@runCatching
                            mainHandler.post { onFinalResult(result.toString()) }
                            emitStatus("PC analysis result was saved to phone history.")
                        }
                    }.onFailure { error ->
                        Log.w("RemoteCamera", "Failed to parse websocket message: ${error.message}")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    connected = false
                    this@RemoteCameraStreamer.webSocket = null
                    Log.e("RemoteCamera", "onFailure: ${t.message}", t)
                    emitError("PC connection failed: ${t.message ?: "unknown error"}")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    connected = false
                    this@RemoteCameraStreamer.webSocket = null
                    Log.d("RemoteCamera", "onClosed: $code / $reason")
                    emitStatus("PC connection closed")
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

        val result = socket.send(bytes.toByteString())
        Log.d("RemoteCamera", "websocket send result=$result")
        return result
    }

    fun isConnected(): Boolean = connected && webSocket != null

    private fun emitStatus(message: String) {
        mainHandler.post { onStatus(message) }
    }

    private fun emitError(message: String) {
        mainHandler.post { onError(message) }
    }

    fun disconnect() {
        connected = false
        webSocket?.send(JSONObject().put("type", "stopped").toString())
        webSocket?.close(1000, "Android camera stopped")
        webSocket = null
    }

    override fun close() {
        disconnect()
        client.dispatcher.executorService.shutdown()
    }
}
