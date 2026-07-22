package com.srthub.cam

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket client that connects to the SRT Camera Hub for telemetry.
 *
 * Lifecycle:
 *   connect()    — opens WS, registers camera on open
 *   disconnect() — clean teardown with backoff reset
 *   reconnect automatically with exponential backoff on failure/close.
 */
class HubClient(
    private val hubHost: String,
    private val hubPort: Int,
    private val cameraId: String,
    private val srtPort: Int,
    private val onCommand: (type: String, value: Any) -> Unit,
    private val onTally: (state: String) -> Unit,
    private val getCameraList: () -> List<Triple<String, String, String>>, // (id, name, facing)
    private val getActiveCameraId: () -> String,
    private val fallbackHosts: List<String> = emptyList(),
    private val onBitrateAdjustmentRequested: ((targetBitrateKbps: Int) -> Unit)? = null
) {
    companion object {
        private const val TAG = "HubClient"
        private const val PING_MS = 10_000L
        private const val FAST_RECONNECT_MS = 500L
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(800, TimeUnit.MILLISECONDS)
        .build()

    private var ws: WebSocket? = null
    private var destroyed = false
    private var reconnectDelay = FAST_RECONNECT_MS
    private var currentHostIndex = 0
    private val allHosts: List<String> get() = (listOf(hubHost) + fallbackHosts).distinct()

    private val handler = Handler(Looper.getMainLooper())
    private val pingExecutor = java.util.concurrent.ScheduledThreadPoolExecutor(1)
    private var pingTask: java.util.concurrent.ScheduledFuture<*>? = null

    fun connect() {
        if (destroyed) return
        val host = allHosts.getOrElse(currentHostIndex) { hubHost }
        val req = Request.Builder().url("ws://$host:$hubPort").build()
        Log.i(TAG, "Connecting to hub at ws://$host:$hubPort (target #$currentHostIndex)")

        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Connected to hub at $host")
                reconnectDelay = FAST_RECONNECT_MS
                register(webSocket)
                startPing(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = JSONObject(text)
                    val type = obj.optString("type")
                    when (type) {
                        "tally" -> {
                            val state = obj.optString("state", "OFF AIR")
                            handler.post { onTally(state) }
                        }
                        "abr_adjust" -> {
                            val targetKbps = obj.optInt("bitrate", 3000)
                            handler.post { onBitrateAdjustmentRequested?.invoke(targetKbps) }
                        }
                        "zoom", "torch", "exposure", "select_camera" -> {
                            val value: Any = when (type) {
                                "torch" -> obj.optBoolean("value", false)
                                "select_camera" -> obj.optString("value", "0")
                                else -> obj.optDouble("value", 1.0).toFloat()
                            }
                            handler.post { onCommand(type, value) }
                        }
                        "error" -> Log.w(TAG, "Hub error: ${obj.optString("message")}")
                        "welcome" -> Log.i(TAG, "Registered as ${obj.optString("id")}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Parse error", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WS failure at $host: ${t.message}")
                rotateHostAndReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (code != 1000) rotateHostAndReconnect()
            }
        })
    }

    private fun rotateHostAndReconnect() {
        if (destroyed) return
        val hosts = allHosts
        if (hosts.isNotEmpty()) {
            currentHostIndex = (currentHostIndex + 1) % hosts.size
        }
        val delay = reconnectDelay
        reconnectDelay = minOf(delay * 2, 5_000L)
        handler.postDelayed({ connect() }, delay)
    }

    private fun register(webSocket: WebSocket) {
        val cams = JSONArray()
        for (c in getCameraList()) {
            cams.put(JSONObject().apply {
                put("id", c.first)
                put("name", c.second)
                put("facing", c.third)
            })
        }
        webSocket.send(JSONObject().apply {
            put("type", "register")
            put("id", cameraId)
            put("port", srtPort)
            put("cameras", cams)
            put("activeCameraId", getActiveCameraId())
        }.toString())
    }

    fun sendCameraUpdate(activeCameraId: String) {
        ws?.send(JSONObject().apply {
            put("type", "camera_update")
            put("id", cameraId)
            put("activeCameraId", activeCameraId)
        }.toString())
    }

    fun sendStats(lossPct: Float, rttMs: Int, droppedFrames: Long) {
        ws?.send(JSONObject().apply {
            put("type", "telemetry")
            put("id", cameraId)
            put("lossPct", lossPct)
            put("rttMs", rttMs)
            put("droppedFrames", droppedFrames)
        }.toString())
    }

    private fun startPing(webSocket: WebSocket) {
        pingTask?.cancel(true)
        pingTask = pingExecutor.scheduleAtFixedRate({
            if (destroyed) return@scheduleAtFixedRate
            try {
                webSocket.send("{\"type\":\"ping\",\"t\":${System.currentTimeMillis()}}")
            } catch (_: Exception) {}
        }, PING_MS, PING_MS, TimeUnit.MILLISECONDS)
    }

    fun disconnect() {
        destroyed = true
        pingTask?.cancel(true)
        pingExecutor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        ws?.close(1000, "bye")
        ws = null
    }
}
