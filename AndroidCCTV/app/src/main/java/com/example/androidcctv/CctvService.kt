package com.example.androidcctv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import org.json.JSONObject

/**
 * 카메라·HTTP 서버를 안고 도는 포그라운드 서비스.
 * 화면이 꺼져도 살아 있도록 WakeLock 과 WifiLock 을 잡는다.
 */
class CctvService : LifecycleService(), HttpServer.Bridge {

    companion object {
        private const val TAG = "CctvService"
        private const val CHANNEL_ID = "cctv_running"
        private const val NOTIF_ID = 1001

        const val ACTION_START = "com.example.androidcctv.START"
        const val ACTION_STOP = "com.example.androidcctv.STOP"

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var publicUrl: String = ""
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, CctvService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, CctvService::class.java).setAction(ACTION_STOP)
            try {
                ctx.startService(i)
            } catch (t: Throwable) {
                ctx.stopService(Intent(ctx, CctvService::class.java))
            }
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var store: Storage
    private lateinit var server: HttpServer
    private lateinit var camera: CameraController

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val ui = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private var lastMotionFile: String? = null

    private var lastNotificationText = ""

    // 알림 갱신도 공짜가 아니다(주소 조회 + 시스템 UI 작업). 주기를 늘리고
    // 내용이 실제로 달라졌을 때만 다시 그린다.
    private val ticker = object : Runnable {
        override fun run() {
            if (!isRunning) return
            updateNotification()
            ui.postDelayed(this, 20_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        store = Storage(this)
        server = HttpServer(this, this)
        camera = CameraController(this, this, prefs, store)
        camera.onMotion = { at, file ->
            lastMotionFile = file?.name
            Log.d(TAG, "motion at $at file=${file?.name}")
        }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }

        if (!hasCameraPermission()) {
            Log.e(TAG, "카메라 권한이 없어 서비스를 시작할 수 없습니다")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isRunning) {
            startedAt = System.currentTimeMillis()
            try {
                ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), foregroundType())
            } catch (t: Throwable) {
                Log.e(TAG, "startForeground 실패", t)
                stopSelf()
                return START_NOT_STICKY
            }
            acquireLocks()
            if (!server.start(prefs.port)) {
                Log.e(TAG, server.lastError ?: "서버 시작 실패")
            }
            camera.start()
            isRunning = true
            prefs.wasRunning = true
            ui.post(ticker)
        }
        publicUrl = urlNow()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        shutdownInternal()
        super.onDestroy()
    }

    private fun shutdown() {
        prefs.wasRunning = false
        shutdownInternal()
        stopSelf()
    }

    private fun shutdownInternal() {
        if (!isRunning) return
        isRunning = false
        ui.removeCallbacks(ticker)
        camera.release()
        server.stop()
        releaseLocks()
        StreamHub.reset()
        publicUrl = ""
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    // ------------------------------------------------------------- 권한 · 잠금

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /** Android 14+ 는 권한이 있는 타입만 선언해야 예외가 나지 않는다. */
    private fun foregroundType(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        return ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cctv:service").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "wakelock", t)
        }
        acquireWifiLock()
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            // HIGH_PERF 는 Wi‑Fi 절전을 통째로 끄기 때문에 전력 소모가 크다. 기본은 일반 잠금.
            val mode = if (prefs.highPerfWifi) {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            } else {
                WifiManager.WIFI_MODE_FULL
            }
            wifiLock = wm.createWifiLock(mode, "cctv:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "wifilock", t)
        }
    }

    /** Wi‑Fi 잠금 모드를 바꾼 뒤 다시 잡는다. */
    private fun refreshWifiLock() {
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (ignored: Throwable) {
        }
        wifiLock = null
        if (isRunning) acquireWifiLock()
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (ignored: Throwable) {
        }
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (ignored: Throwable) {
        }
        wakeLock = null
        wifiLock = null
    }

    // ------------------------------------------------------------- 알림

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        ch.setShowBadge(false)
        ch.enableVibration(false)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingFlags()
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, CctvService::class.java).setAction(ACTION_STOP),
            pendingFlags()
        )
        val detail = notificationText()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cctv)
            .setContentTitle("CCTV 실행 중")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "중지", stop)
            .build()
    }

    private fun pendingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    private fun notificationText(): String {
        if (!isRunning) return "준비 중…"
        val viewers = server.viewers
        val rate = if (viewers > 0 && VideoHub.kbps > 0) "${VideoHub.kbps} kbps" else "대기 중"
        return "${urlNow()}  ·  시청자 ${viewers}명  ·  $rate"
    }

    private fun updateNotification() {
        val text = notificationText()
        if (text == lastNotificationText) return
        lastNotificationText = text
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification())
        } catch (ignored: Throwable) {
        }
    }

    private fun urlNow(): String = "http://${NetUtil.primaryIp()}:${server.port}"

    // ------------------------------------------------------------- HttpServer.Bridge

    override fun token(): String = prefs.token

    override fun storage(): Storage = store

    override fun requestFrame() = camera.requestSnapshot()

    override fun status(): JSONObject {
        val battery = batteryInfo()
        val motion = JSONObject()
            .put("enabled", prefs.motionEnabled)
            .put("sensitivity", prefs.motionSensitivity)
            .put("cooldown", prefs.motionCooldown)
            .put("saveShot", prefs.motionSaveShot)
            .put("score", String.format("%.4f", camera.motionScore).toDouble())
            .put("count", camera.motionCount)
            .put("lastAt", camera.lastMotionAt)
            .put("lastFile", lastMotionFile ?: JSONObject.NULL)

        val recording = JSONObject()
            .put("available", camera.h264Active)
            .put("active", camera.isRecording)
            .put("file", camera.recordingFile?.name ?: JSONObject.NULL)
            .put(
                "elapsed",
                if (camera.isRecording) (System.currentTimeMillis() - camera.recordingStartedAt) / 1000 else 0
            )
            .put("sizeMb", camera.recordedBytes / (1024 * 1024))
            .put("audio", false)

        val stream = JSONObject()
            .put("h264", JSONObject()
                .put("enabled", prefs.h264Enabled)
                .put("ready", VideoHub.ready)
                .put("codec", VideoHub.codecString ?: JSONObject.NULL)
                .put("width", VideoHub.width)
                .put("height", VideoHub.height)
                .put("kbps", VideoHub.kbps)
                .put("bitrateKbps", prefs.bitrateKbps)
                .put("keyInterval", prefs.keyInterval)
                .put("rotation", camera.streamRotation)
                .put("viewers", VideoHub.viewers.get()))
            .put("mjpeg", JSONObject()
                .put("viewers", StreamHub.viewers.get())
                .put("fps", String.format("%.1f", StreamHub.fps).toDouble())
                .put("quality", prefs.quality)
                .put("targetFps", prefs.fps))
            .put("power", JSONObject()
                .put("cameraFps", prefs.fps)
                .put("fpsRange", camera.appliedFpsRange ?: JSONObject.NULL)
                .put("highPerfWifi", prefs.highPerfWifi)
                .put("encoding", VideoHub.viewers.get() > 0 || camera.isRecording))

        return JSONObject()
            .put("ok", true)
            .put("running", isRunning)
            .put("bound", camera.bound)
            .put("url", urlNow())
            .put("ips", NetUtil.localIpv4().joinToString(","))
            .put("port", server.port)
            .put("viewers", server.viewers)
            .put("fps", String.format("%.1f", StreamHub.fps).toDouble())
            .put("targetFps", prefs.fps)
            .put("frameAgeMs", StreamHub.lastFrameAgeMs())
            .put("lens", camera.activeLens)
            .put("requestedLens", prefs.lens)
            .put("width", camera.frameWidth)
            .put("height", camera.frameHeight)
            .put("configWidth", prefs.width)
            .put("configHeight", prefs.height)
            .put("quality", prefs.quality)
            .put("rotation", prefs.rotation)
            .put("mirror", prefs.mirror)
            .put("stream", stream)
            .put("autoStart", prefs.autoStart)
            .put("motion", motion)
            .put("recording", recording)
            .put("torch", JSONObject().put("available", camera.torchAvailable).put("on", camera.torchOn))
            .put("battery", battery)
            .put("storage", JSONObject()
                .put("freeMb", store.freeBytes() / (1024 * 1024))
                .put("events", store.count("events"))
                .put("snapshots", store.count("snapshots"))
                .put("videos", store.count("videos")))
            .put("uptime", if (startedAt > 0) (System.currentTimeMillis() - startedAt) / 1000 else 0)
            .put("error", camera.lastError ?: server.lastError ?: JSONObject.NULL)
    }

    override fun applyConfig(body: JSONObject): JSONObject {
        val applied = ArrayList<String>()
        var needRebind = false
        var needServerRestart = false

        fun has(k: String) = body.has(k) && !body.isNull(k)

        if (has("lens")) {
            val v = body.getString("lens")
            if (v != prefs.lens) { prefs.lens = v; needRebind = true; applied.add("lens") }
        }
        if (has("resolution")) {
            val parts = body.getString("resolution").lowercase().split("x")
            val w = parts.getOrNull(0)?.trim()?.toIntOrNull()
            val h = parts.getOrNull(1)?.trim()?.toIntOrNull()
            if (w != null && h != null) {
                if (w != prefs.width || h != prefs.height) {
                    prefs.width = w; prefs.height = h; needRebind = true; applied.add("resolution")
                }
            }
        }
        if (has("width")) {
            val v = body.getInt("width")
            if (v != prefs.width) { prefs.width = v; needRebind = true; applied.add("width") }
        }
        if (has("height")) {
            val v = body.getInt("height")
            if (v != prefs.height) { prefs.height = v; needRebind = true; applied.add("height") }
        }
        if (has("quality")) { prefs.quality = body.getInt("quality"); applied.add("quality") }
        if (has("fps")) {
            val v = body.getInt("fps").coerceIn(1, 30)
            if (v != prefs.fps) {
                prefs.fps = v
                // 카메라 촬영 속도 자체를 바꾸므로 세션을 다시 열어야 한다.
                needRebind = true
                applied.add("fps")
            }
        }
        if (has("highPerfWifi")) {
            val v = body.getBoolean("highPerfWifi")
            if (v != prefs.highPerfWifi) {
                prefs.highPerfWifi = v
                refreshWifiLock()
                applied.add("highPerfWifi")
            }
        }
        if (has("rotation")) {
            val v = ((body.getInt("rotation") % 360) + 360) % 360 / 90 * 90
            if (v != prefs.rotation) {
                prefs.rotation = v
                // H.264 는 회전을 MP4 헤더(tkhd 행렬)에 담으므로 스트림을 다시 열어야 한다.
                if (prefs.h264Enabled) needRebind = true
                applied.add("rotation")
            }
        }
        if (has("mirror")) { prefs.mirror = body.getBoolean("mirror"); applied.add("mirror") }
        if (has("motionEnabled")) { prefs.motionEnabled = body.getBoolean("motionEnabled"); applied.add("motionEnabled") }
        if (has("motionSensitivity")) { prefs.motionSensitivity = body.getInt("motionSensitivity"); applied.add("motionSensitivity") }
        if (has("motionCooldown")) { prefs.motionCooldown = body.getInt("motionCooldown"); applied.add("motionCooldown") }
        if (has("motionSaveShot")) { prefs.motionSaveShot = body.getBoolean("motionSaveShot"); applied.add("motionSaveShot") }
        if (has("autoStart")) { prefs.autoStart = body.getBoolean("autoStart"); applied.add("autoStart") }
        if (has("maxEvents")) { prefs.maxEvents = body.getInt("maxEvents"); applied.add("maxEvents") }
        if (has("bitrateKbps")) {
            prefs.bitrateKbps = body.getInt("bitrateKbps")
            camera.applyBitrate()          // 재바인딩 없이 즉시 적용
            applied.add("bitrateKbps")
        }
        if (has("keyInterval")) {
            val v = body.getInt("keyInterval")
            if (v != prefs.keyInterval) { prefs.keyInterval = v; needRebind = true; applied.add("keyInterval") }
        }
        if (has("h264Enabled")) {
            val v = body.getBoolean("h264Enabled")
            if (v != prefs.h264Enabled) { prefs.h264Enabled = v; needRebind = true; applied.add("h264Enabled") }
        }
        if (has("port")) {
            val v = body.getInt("port")
            if (v != prefs.port) { prefs.port = v; needServerRestart = true; applied.add("port") }
        }

        if (needRebind) camera.rebind()
        if (needServerRestart) {
            // 포트를 바꾸면 현재 연결은 끊기고 새 주소로 다시 접속해야 한다.
            ui.postDelayed({
                server.stop()
                server.start(prefs.port)
                publicUrl = urlNow()
            }, 300)
        }

        return JSONObject()
            .put("ok", true)
            .put("applied", applied.joinToString(","))
            .put("rebound", needRebind)
            .put("serverRestarted", needServerRestart)
            .put("status", status())
    }

    override fun action(name: String, params: Map<String, String>): JSONObject {
        fun bool(key: String, def: Boolean = true): Boolean =
            params[key]?.let { it == "true" || it == "1" || it == "on" } ?: def

        val res = JSONObject().put("action", name)
        when (name) {
            "torch" -> {
                val err = camera.setTorch(bool("on"))
                res.put("ok", err == null); if (err != null) res.put("error", err)
            }
            "lens" -> {
                val v = params["lens"] ?: if (prefs.lens == "front") "back" else "front"
                prefs.lens = v
                camera.rebind()
                res.put("ok", true).put("lens", prefs.lens)
            }
            "zoom" -> {
                val v = params["value"]?.toFloatOrNull() ?: 0f
                val err = camera.setZoom(v)
                res.put("ok", err == null); if (err != null) res.put("error", err)
            }
            "snapshot" -> {
                val f = StreamHub.latest()
                if (f == null) {
                    res.put("ok", false).put("error", "아직 프레임이 없습니다")
                } else {
                    try {
                        val file = store.saveSnapshot(f.data)
                        store.prune("snapshots", prefs.maxEvents)
                        res.put("ok", true).put("name", file.name).put("url", "/media/snapshots/${file.name}")
                    } catch (t: Throwable) {
                        res.put("ok", false).put("error", "저장 실패: ${t.message}")
                    }
                }
            }
            "record" -> {
                val on = bool("on")
                val err = if (on) camera.startRecording() else camera.stopRecording()
                res.put("ok", err == null); if (err != null) res.put("error", err)
            }
            "restart" -> {
                camera.clearError()
                camera.rebind()
                res.put("ok", true)
            }
            "clearError" -> {
                camera.clearError()
                res.put("ok", true)
            }
            "stop" -> {
                res.put("ok", true).put("message", "서비스를 종료합니다")
                ui.postDelayed({ shutdown() }, 400)
            }
            else -> res.put("ok", false).put("error", "알 수 없는 동작: $name")
        }
        res.put("status", status())
        return res
    }

    private fun batteryInfo(): JSONObject {
        return try {
            val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val plugged = (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val temp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
            JSONObject()
                .put("percent", if (level >= 0 && scale > 0) level * 100 / scale else -1)
                .put("charging", plugged)
                .put("tempC", temp)
        } catch (t: Throwable) {
            JSONObject().put("percent", -1).put("charging", false).put("tempC", 0)
        }
    }
}
