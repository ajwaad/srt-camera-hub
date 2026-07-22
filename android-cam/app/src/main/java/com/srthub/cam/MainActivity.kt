package com.srthub.cam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.hardware.camera2.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.library.srt.SrtCamera2
import com.pedro.library.view.OpenGlView

class MainActivity : AppCompatActivity(), ConnectChecker, SurfaceHolder.Callback {

    companion object {
        private const val TAG = "SRTCam"
        private const val PREFS = "srtcam_prefs"
        private const val REQ_PERMS = 100
        private val REQUIRED = arrayOf(Manifest.permission.CAMERA)
    }

    // Views
    private lateinit var glView: OpenGlView
    private lateinit var ipInput: EditText
    private lateinit var portInput: EditText
    private lateinit var streamInput: EditText
    private lateinit var goLiveBtn: Button
    private lateinit var settingsPanel: View
    private lateinit var bitrateInput: EditText
    private lateinit var codecSpinner: Spinner
    private lateinit var resSpinner: Spinner
    private lateinit var fpsSpinner: Spinner
    private lateinit var cameraSpinner: Spinner
    private lateinit var statusText: TextView
    private lateinit var bitrateLabel: TextView
    private lateinit var zoomSeek: SeekBar
    private lateinit var zoomLabel: TextView
    private lateinit var expSeek: SeekBar
    private lateinit var expLabel: TextView
    private lateinit var torchSwitch: androidx.appcompat.widget.SwitchCompat
    private lateinit var flipHSwitch: androidx.appcompat.widget.SwitchCompat
    private lateinit var controlsBody: View
    private lateinit var controlsHandle: TextView
    private lateinit var tallyBadge: TextView

    // State
    private var srtCamera: SrtCamera2? = null
    private var hubClient: HubClient? = null
    private var isStreaming = false
    private var settingsVisible = false
    private var activeEndpoint: SrtEndpoint? = null
    private var activeStreamId = ""
    private var onCreateDone = false // guard against premature surface callbacks

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setContentView(R.layout.activity_main)
            bindViews()
            setupSpinners()
            loadPrefs()
            wireButtons()
            registerNetworkMonitor()
            onCreateDone = true
            // Only now allow surface callbacks to trigger camera init
            glView.holder.addCallback(this)
        } catch (t: Throwable) {
            Log.e(TAG, "FATAL onCreate", t)
            Toast.makeText(this, "App init failed: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun bindViews() {
        glView = findViewById(R.id.glView)
        ipInput = findViewById(R.id.ipInput)
        portInput = findViewById(R.id.portInput)
        streamInput = findViewById(R.id.streamInput)
        goLiveBtn = findViewById(R.id.goLiveBtn)
        settingsPanel = findViewById(R.id.settingsPanel)
        bitrateInput = findViewById(R.id.bitrateInput)
        codecSpinner = findViewById(R.id.codecSpinner)
        resSpinner = findViewById(R.id.resSpinner)
        fpsSpinner = findViewById(R.id.fpsSpinner)
        cameraSpinner = findViewById(R.id.cameraSpinner)
        statusText = findViewById(R.id.statusText)
        bitrateLabel = findViewById(R.id.bitrateLabel)
        zoomSeek = findViewById(R.id.zoomSeek)
        zoomLabel = findViewById(R.id.zoomLabel)
        expSeek = findViewById(R.id.expSeek)
        expLabel = findViewById(R.id.expLabel)
        torchSwitch = findViewById(R.id.torchSwitch)
        flipHSwitch = findViewById(R.id.flipHSwitch)
        controlsBody = findViewById(R.id.controlsBody)
        controlsHandle = findViewById(R.id.controlsHandle)
        tallyBadge = findViewById(R.id.tallyBadge)
    }

    private fun updateTallyState(state: String) {
        val upper = state.uppercase()
        when (upper) {
            "ON AIR" -> {
                tallyBadge.text = "ON AIR"
                tallyBadge.setBackgroundColor(Color.parseColor("#DC2626")) // Bright Red
                tallyBadge.setTextColor(Color.WHITE)
            }
            "BACKSTAGE", "STAGE", "ON STAGE" -> {
                tallyBadge.text = "BACKSTAGE"
                tallyBadge.setBackgroundColor(Color.parseColor("#D97706")) // Amber/Yellow
                tallyBadge.setTextColor(Color.WHITE)
            }
            else -> { // OFF AIR
                tallyBadge.text = "OFF AIR"
                tallyBadge.setBackgroundColor(Color.parseColor("#AA374151")) // Dim Dark Gray
                tallyBadge.setTextColor(Color.parseColor("#D1D5DB"))
            }
        }
    }

    private fun setupSpinners() {
        codecSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            arrayOf("H.264", "H.265"))
        resSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            arrayOf("720p Portrait (720x1280)", "720p Landscape (1280x720)",
                     "1080p Portrait (1080x1920)", "1080p Landscape (1920x1080)"))
        fpsSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            arrayOf("30 FPS", "60 FPS"))
        refreshCameraList()
    }

    private fun loadPrefs() {
        val p = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        ipInput.setText(p.getString("ip", ""))
        portInput.setText(p.getString("port", ""))
        streamInput.setText(p.getString("stream_id", "camera1"))
        bitrateInput.setText(p.getString("bitrate", "3000"))
        codecSpinner.setSelection(p.getInt("codec_idx", 0))
        resSpinner.setSelection(p.getInt("res_idx", 1))
        fpsSpinner.setSelection(p.getInt("fps_idx", 0))
    }

    private fun savePrefs() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putString("ip", ipInput.text.toString())
            putString("port", portInput.text.toString())
            putString("stream_id", streamInput.text.toString())
            putString("bitrate", bitrateInput.text.toString())
            putInt("codec_idx", codecSpinner.selectedItemPosition)
            putInt("res_idx", resSpinner.selectedItemPosition)
            putInt("fps_idx", fpsSpinner.selectedItemPosition)
            apply()
        }
    }

    private fun wireButtons() {
        goLiveBtn.setOnClickListener {
            if (isStreaming) stopStreaming() else startStreaming()
        }
        findViewById<ImageButton>(R.id.settingsBtn).setOnClickListener { toggleSettings() }
        findViewById<Button>(R.id.applyBtn).setOnClickListener {
            savePrefs()
            toggleSettings()
            refreshCameraList()
        }
        torchSwitch.setOnCheckedChangeListener { _, on ->
            setTorch(on)
        }
        flipHSwitch.setOnCheckedChangeListener { _, on ->
            setFlipH(on)
        }
        controlsHandle.setOnClickListener {
            controlsBody.visibility = if (controlsBody.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
    }

    private fun toggleSettings() {
        settingsVisible = !settingsVisible
        settingsPanel.visibility = if (settingsVisible) View.VISIBLE else View.GONE
    }

    // ── Camera list ─────────────────────────────────────────────────────────
    data class Cam(val id: String, val name: String, val facing: String)

    private fun getCameras(): List<Cam> {
        val list = mutableListOf<Cam>()
        try {
            val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in mgr.cameraIdList) {
                val chars = mgr.getCameraCharacteristics(id)
                val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                    CameraMetadata.LENS_FACING_BACK -> "back"
                    CameraMetadata.LENS_FACING_FRONT -> "front"
                    CameraMetadata.LENS_FACING_EXTERNAL -> "external"
                    else -> "unknown"
                }
                list.add(Cam(id, "Camera $id (${facing})", facing))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera list error", e)
        }
        if (list.isEmpty()) list.add(Cam("0", "Camera 0", "back"))
        return list
    }

    private fun refreshCameraList() {
        val cams = getCameras()
        val names = cams.map { it.name }.toTypedArray()
        cameraSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
    }

    private fun getSelectedCameraId(): String {
        val cams = getCameras()
        val i = cameraSpinner.selectedItemPosition
        return if (i in cams.indices) cams[i].id else "0"
    }

    fun getCamerasListForHub(): List<Triple<String, String, String>> {
        return getCameras().map { Triple(it.id, it.name, it.facing) }
    }

    // ── Camera init ─────────────────────────────────────────────────────────
    override fun surfaceCreated(holder: SurfaceHolder) {}
    override fun surfaceChanged(holder: SurfaceHolder, fmt: Int, w: Int, h: Int) {
        if (onCreateDone && hasPerms() && srtCamera == null) {
            try {
                initCamera()
            } catch (t: Throwable) {
                Log.e(TAG, "surfaceChanged initCamera failed", t)
            }
        }
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        try { srtCamera?.stopPreview() } catch (_: Throwable) {}
    }

    private fun hasPerms() = REQUIRED.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(rc, perms, grants)
        if (rc == REQ_PERMS && grants.all { it == PackageManager.PERMISSION_GRANTED }) {
            if (glView.holder.surface.isValid) {
                try { initCamera() } catch (t: Throwable) {
                    Log.e(TAG, "onRequestPermissionsResult initCamera failed", t)
                }
            }
        }
    }

    private fun initCamera() {
        if (srtCamera != null) return
        try {
            srtCamera = SrtCamera2(glView, this)
            glView.setAspectRatioMode(com.pedro.encoder.utils.gl.AspectRatioMode.Adjust)
            val (w, h) = getResolution()
            val camId = getSelectedCameraId()
            srtCamera?.startPreview(camId, maxOf(w, h), minOf(w, h))
            setupZoom()
        } catch (t: Throwable) {
            Log.e(TAG, "Camera init failed", t)
            srtCamera = null
        }
    }

    private fun getRotation(cameraId: String): Int {
        try {
            val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = mgr.getCameraCharacteristics(cameraId)
            val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
                ?: CameraMetadata.LENS_FACING_BACK

            val displayRotation = (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.rotation
            val displayDegrees = when (displayRotation) {
                Surface.ROTATION_0 -> 0
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }

            // Standard Android Camera2 encoder rotation formula
            return if (facing == CameraMetadata.LENS_FACING_FRONT) {
                (360 - (sensor + displayDegrees) % 360) % 360  // mirror for front
            } else {
                (sensor - displayDegrees + 360) % 360          // back camera
            }
        } catch (e: Exception) {
            Log.e(TAG, "getRotation failed", e)
            return 0
        }
    }

    private fun getResolution(): Pair<Int, Int> = when (resSpinner.selectedItemPosition) {
        0 -> 720 to 1280    // 720p Portrait
        1 -> 1280 to 720    // 720p Landscape
        2 -> 1080 to 1920   // 1080p Portrait
        3 -> 1920 to 1080   // 1080p Landscape
        else -> 1280 to 720
    }

    private var isStarting = false // guard against rapid clicks
    private val keyframeRunner = Runnable { requestKeyFrame() }
    private var keyframeFlushActive = false

    // ── Start / Stop streaming ──────────────────────────────────────────────
    private fun startStreaming() {
        if (isStreaming || isStarting) return
        isStarting = true
        try {
            startStreamingInternal()
        } finally {
            if (!isStreaming) isStarting = false
        }
    }

    private fun startStreamingInternal() {
        val rawIp = ipInput.text.toString().trim()
        val port = portInput.text.toString().toIntOrNull()

        if (rawIp.isEmpty()) {
            toast("Enter the hub IP address")
            return
        }
        if (port == null) {
            toast("Enter a port number (1024–65535)")
            return
        }

        // Parse & validate endpoint
        val endpoint: SrtEndpoint
        try {
            endpoint = SrtEndpoint.fromRaw(rawIp, port)
        } catch (e: IllegalArgumentException) {
            toast(e.message ?: "Invalid endpoint")
            return
        }

        val streamId = streamInput.text.toString().trim()
            .replace(Regex("[^a-zA-Z0-9_-]"), "")
            .ifBlank { "camera1" }

        val useH265 = codecSpinner.selectedItemPosition == 1
        val (w, h) = getResolution()
        val fps = if (fpsSpinner.selectedItemPosition == 1) 60 else 30
        val bitrateKbps = bitrateInput.text.toString().toIntOrNull() ?: 5000

        activeEndpoint = endpoint
        activeStreamId = streamId
        savePrefs()

        // Init camera if needed
        if (srtCamera == null) {
            if (!hasPerms()) {
                requestPermissions(REQUIRED, REQ_PERMS)
                return
            }
            try { initCamera() } catch (t: Throwable) {
                toast("Camera init failed: ${t.message}")
                return
            }
        }
        val cam = srtCamera ?: run {
            toast("Camera not ready")
            return
        }

        cam.setVideoCodec(if (useH265) VideoCodec.H265 else VideoCodec.H264)
        cam.getStreamClient().setOnlyVideo(true)

        val prepared = try {
            cam.stopPreview()
            val camId = getSelectedCameraId()
            cam.startPreview(camId, maxOf(w, h), minOf(w, h))
            cam.prepareVideo(w, h, fps, bitrateKbps * 1000, 1, getRotation(camId))
        } catch (t: Throwable) {
            Log.e(TAG, "Prepare failed", t)
            false
        }

        if (!prepared) {
            toast("Camera prepare failed")
            return
        }

        // Start foreground service
        ContextCompat.startForegroundService(this, Intent(this, WakeLockService::class.java))

        // Start SRT stream — RootEncoder requires srt://host:port/streamid format
        val srtUrl = "${endpoint.url}/$streamId"
        try {
            cam.startStream(srtUrl)
            Log.i(TAG, "Streaming to $srtUrl")
        } catch (t: Throwable) {
            Log.e(TAG, "startStream failed", t)
            toast("Failed to start stream: ${t.message}")
            stopStreaming()
            return
        }

        // Connect to hub (WebSocket signaling — separate from SRT data port)
        hubClient?.disconnect()
        val hubWsPort = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("hub_ws_port", "3001")?.toIntOrNull() ?: 3001
        hubClient = HubClient(
            hubHost = endpoint.host,
            hubPort = hubWsPort,
            cameraId = streamId,
            srtPort = endpoint.port,
            onCommand = { type, value -> handleHubCommand(type, value) },
            onTally = { state -> updateTallyState(state) },
            getCameraList = { getCamerasListForHub() },
            getActiveCameraId = { getSelectedCameraId() },
            fallbackHosts = listOf(endpoint.host, "127.0.0.1"),
            onBitrateAdjustmentRequested = { targetKbps ->
                try {
                    srtCamera?.setVideoBitrateOnFly(targetKbps * 1000)
                    Log.i(TAG, "ABR adjusted bitrate to ${targetKbps} kbps")
                } catch (e: Exception) {
                    Log.e(TAG, "ABR bitrate change failed", e)
                }
            }
        ).apply { connect() }

        isStreaming = true
        isStarting = false
        // Periodic keyframe flush: prevents encoder buffer bloat (latency drift)
        if (!keyframeFlushActive) {
            keyframeFlushActive = true
            handler.postDelayed(keyframeRunner, 30_000L)
        }
        goLiveBtn.text = "STOP"
        goLiveBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
        setStatus("Connecting...", "#F59E0B")
    }

    private fun stopStreaming() {
        isStarting = false
        keyframeFlushActive = false
        handler.removeCallbacks(keyframeRunner)
        hubClient?.disconnect()
        hubClient = null
        try { srtCamera?.stopStream() } catch (_: Throwable) {}
        try { srtCamera?.startPreview() } catch (_: Throwable) {}
        stopService(Intent(this, WakeLockService::class.java))
        isStreaming = false
        goLiveBtn.text = "GO LIVE"
        goLiveBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))
        setStatus("Stopped", "#6B7280")
    }

    // ── Hub command handler ─────────────────────────────────────────────────
    private fun handleHubCommand(type: String, value: Any) {
        handler.post {
            try {
                when (type) {
                    "zoom" -> {
                        val z = (value as? Number)?.toFloat() ?: 1f
                        srtCamera?.setZoom(z)
                        updateZoomUI(z)
                    }
                    "torch" -> {
                        val on = value as? Boolean ?: false
                        torchSwitch.isChecked = on
                        setTorch(on)
                    }
                    "exposure" -> {
                        val e = (value as? Number)?.toFloat() ?: 1f
                        srtCamera?.glInterface?.setFilter(
                            com.pedro.encoder.input.gl.render.filters.ExposureFilterRender().also {
                                it.exposure = e
                            })
                    }
                    "select_camera" -> {
                        val camId = value.toString()
                        switchCamera(camId)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "handleHubCommand failed", t)
            }
        }
    }

    private fun switchCamera(cameraId: String) {
        if (!isStreaming) return
        try {
            srtCamera?.stopStream()
            srtCamera?.stopPreview()
            val (w, h) = getResolution()
            val fps = if (fpsSpinner.selectedItemPosition == 1) 60 else 30
            val bitrateKbps = bitrateInput.text.toString().toIntOrNull() ?: 5000
            srtCamera?.startPreview(cameraId, maxOf(w, h), minOf(w, h))
            srtCamera?.prepareVideo(w, h, fps, bitrateKbps * 1000, 1, getRotation(cameraId))
            activeEndpoint?.url?.let { srtCamera?.startStream("$it/$activeStreamId") }
        } catch (t: Throwable) {
            Log.e(TAG, "Camera switch failed", t)
        }
        hubClient?.sendCameraUpdate(cameraId)
    }

    // ── Quick controls ──────────────────────────────────────────────────────
    private fun setupZoom() {
        val cam = srtCamera ?: return
        try {
            val range = cam.zoomRange
            val minZ = range?.lower ?: 1f
            val maxZ = range?.upper ?: 10f
            zoomSeek.max = 100
            zoomSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val z = minZ + (p / 100f) * (maxZ - minZ)
                        zoomLabel.text = "Zoom: %.1fx".format(z)
                        try { cam.setZoom(z) } catch (_: Throwable) {}
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        } catch (_: Throwable) {}
        expSeek.max = 200
        expSeek.progress = 100
        expSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    val e = p / 100f
                    expLabel.text = "Exp: %.2f".format(e)
                    try {
                        cam.glInterface?.setFilter(
                            com.pedro.encoder.input.gl.render.filters.ExposureFilterRender().also {
                                it.exposure = e
                            })
                    } catch (_: Throwable) {}
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun updateZoomUI(z: Float) {
        zoomLabel.text = "Zoom: %.1fx".format(z)
    }

    private fun setTorch(on: Boolean) {
        try {
            if (on) srtCamera?.enableLantern() else srtCamera?.disableLantern()
        } catch (_: Throwable) {}
    }

    private fun setFlipH(on: Boolean) {
        try {
            srtCamera?.glInterface?.setIsStreamHorizontalFlip(on)
        } catch (_: Throwable) {}
    }

    private fun setStatus(text: String, colorHex: String) {
        statusText.text = text
        try { statusText.setTextColor(Color.parseColor(colorHex)) } catch (_: Exception) {}
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ── ConnectChecker callbacks ────────────────────────────────────────────
    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() {
        handler.post {
            setStatus("● LIVE", "#10B981")
            requestKeyFrame() // start with a clean keyframe
        }
    }
    override fun onConnectionFailed(reason: String) {
        handler.post {
            val msg = when {
                reason.contains("timeout", ignoreCase = true) -> "Connection timeout"
                reason.contains("reject", ignoreCase = true) || reason.contains("configure", ignoreCase = true) -> "Stream ID in use?"
                else -> reason
            }
            toast(msg)
            if (isStreaming) stopStreaming()
        }
    }
    override fun onNewBitrate(bitrate: Long) {
        handler.post { bitrateLabel.text = "${bitrate / 1000} kbps" }
    }
    override fun onDisconnect() {
        handler.post {
            setStatus("Disconnected", "#EF4444")
            requestKeyFrame() // heal stream on reconnect
        }
    }
    override fun onAuthError() {}
    override fun onAuthSuccess() {}

    // ── Keyframe injection (closed-loop stream healing) ─────────────────────
    // Walks RootEncoder's full class hierarchy via reflection to reach the
    // internal MediaCodec and fire PARAMETER_KEY_REQUEST_SYNC_FRAME.
    private fun requestKeyFrame() {
        try {
            val cam = srtCamera ?: return

            // Walk entire class tree to find the VideoEncoder field
            // RootEncoder 2.4.6: SrtCamera2 → Camera2Base → CameraBase (has videoEncoder)
            val encoder = findFieldInHierarchy(cam, "videoEncoder", "encoder", "mVideoEncoder")
                ?: return

            // Walk encoder's hierarchy to find the MediaCodec
            // VideoEncoder has videoCodec / mediaCodec
            val codec = findFieldInHierarchy(encoder, "videoCodec", "mediaCodec", "mVideoCodec", "codec")
                as? android.media.MediaCodec ?: return

            val params = android.os.Bundle()
            params.putInt(android.media.MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            codec.setParameters(params)
            Log.i(TAG, "Keyframe requested — stream healing")
            // Reschedule for continuous buffer flush
            if (keyframeFlushActive) {
                handler.postDelayed(keyframeRunner, 30_000L)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "requestKeyFrame failed: ${t.message}")
        }
    }

    /** Walk class hierarchy upward until field is found, trying each name. */
    private fun findFieldInHierarchy(obj: Any, vararg names: String): Any? {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            for (name in names) {
                try {
                    val f = clazz.getDeclaredField(name)
                    f.isAccessible = true
                    return f.get(obj)
                } catch (_: NoSuchFieldException) {}
                catch (_: IllegalAccessException) {}
            }
            clazz = clazz.superclass
        }
        return null
    }

    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    private fun registerNetworkMonitor() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val builder = android.net.NetworkRequest.Builder()
            networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    Log.i(TAG, "Network interface available: $network")
                    if (isStreaming) {
                        handler.post { hubClient?.connect() }
                    }
                }
                override fun onLost(network: android.net.Network) {
                    Log.w(TAG, "Network interface lost: $network")
                    if (isStreaming) {
                        handler.post { hubClient?.connect() }
                    }
                }
            }
            cm.registerNetworkCallback(builder.build(), networkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "Network monitor register failed", e)
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────
    override fun onDestroy() {
        super.onDestroy()
        networkCallback?.let {
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager)
                    .unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        try { hubClient?.disconnect() } catch (_: Throwable) {}
        try { srtCamera?.stopStream() } catch (_: Throwable) {}
        try { srtCamera?.stopPreview() } catch (_: Throwable) {}
    }
}
