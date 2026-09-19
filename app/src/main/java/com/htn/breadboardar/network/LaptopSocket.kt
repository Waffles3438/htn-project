package com.htn.breadboardar.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.htn.breadboardar.ar.BoardCalibration
import com.htn.breadboardar.ar.CameraState
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LaptopSocket(
    private val listener: Listener,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build(),
) {
    interface Listener {
        fun onSocketStatus(message: String, connected: Boolean)
        fun onOverlayFrame(bitmap: Bitmap)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    private var sessionId = ""

    fun connect(rawUrl: String, requestedSessionId: String) {
        close()
        sessionId = requestedSessionId
        val url = rawUrl.trim()
            .replaceFirst(Regex("^http:"), "ws:")
            .replaceFirst(Regex("^https:"), "wss:")
        require(url.startsWith("ws://") || url.startsWith("wss://")) {
            "Enter a ws:// or wss:// laptop URL."
        }
        socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (socket !== webSocket) return
                    postStatus("Connected to laptop", true)
                    webSocket.send(
                        JSONObject()
                            .put("type", "hello")
                            .put("protocolVersion", 1)
                            .put("sessionId", sessionId)
                            .put("client", "android-ar-viewer")
                            .toString(),
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (socket !== webSocket) return
                    handleMessage(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (socket !== webSocket) return
                    postStatus("Connection failed: ${t.message ?: "unknown error"}", false)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (socket !== webSocket) return
                    postStatus("Disconnected", false)
                }
            },
        )
    }

    fun sendCalibration(calibration: BoardCalibration) {
        send(
            JSONObject()
                .put("type", "calibration")
                .put("sessionId", sessionId)
                .put("coordinateSystem", "ARCore world space, meters, right-handed")
                .put(
                    "boardFrame",
                    JSONObject()
                        .put("originMeters", array(calibration.originMeters))
                        .put("xAxis", array(calibration.xAxis))
                        .put("yAxis", array(calibration.yAxis))
                        .put("zAxis", array(calibration.zAxis))
                        .put("xExtentMeters", calibration.xExtentMeters)
                        .put("yExtentMeters", calibration.yExtentMeters),
                ),
        )
    }

    /**
     * Board pose relative to the camera, solved from each frame's image.
     *
     * Sent alongside the world-space `calibration` message rather than replacing it.
     * Unity can use whichever it trusts: this one needs no ARCore world tracking, so
     * it stays correct even when that tracking has diverged.
     */
    fun sendBoardPoseInCamera(translation: FloatArray, quaternion: FloatArray) {
        send(
            JSONObject()
                .put("type", "board_pose_camera")
                .put("sessionId", sessionId)
                .put("coordinateSystem", "ARCore camera space, meters, right-handed, -Z forward")
                .put("translationMeters", array(translation))
                .put("rotationQuaternion", array(quaternion)),
        )
    }

    fun sendCameraState(state: CameraState) {
        send(
            JSONObject()
                .put("type", "camera_pose")
                .put("sessionId", sessionId)
                .put("timestampNs", state.timestampNs)
                .put("translationMeters", array(state.translationMeters))
                .put("rotationQuaternion", array(state.rotationQuaternion))
                .put(
                    "intrinsics",
                    JSONObject()
                        .put("focalLengthPixels", array(state.focalLengthPixels))
                        .put("principalPointPixels", array(state.principalPointPixels))
                        .put("imageDimensions", array(state.imageDimensions))
                        .put("viewport", JSONArray().put(state.viewportWidth).put(state.viewportHeight)),
                ),
        )
    }

    fun close() {
        socket?.close(NORMAL_CLOSE, "viewer closed")
        socket = null
    }

    private fun handleMessage(text: String) {
        runCatching {
            val message = JSONObject(text)
            val incomingSessionId = message.optString("sessionId", sessionId)
            if (incomingSessionId != sessionId) return
            when (message.getString("type")) {
                "overlay_frame" -> {
                    val bytes = Base64.decode(message.getString("pngBase64"), Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?.let { bitmap -> mainHandler.post { listener.onOverlayFrame(bitmap) } }
                }

                "status" -> postStatus(message.optString("message", "Laptop status update"), true)
            }
        }.onFailure {
            postStatus("Ignored invalid laptop message", true)
        }
    }

    private fun send(message: JSONObject) {
        socket?.send(message.toString())
    }

    private fun postStatus(message: String, connected: Boolean) {
        mainHandler.post { listener.onSocketStatus(message, connected) }
    }

    private fun array(values: FloatArray): JSONArray = JSONArray().apply { values.forEach(::put) }
    private fun array(values: IntArray): JSONArray = JSONArray().apply { values.forEach(::put) }

    private companion object {
        const val NORMAL_CLOSE = 1000
    }
}
