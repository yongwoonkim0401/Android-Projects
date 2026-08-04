package com.example.androidcctv

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    private lateinit var tvStatus: TextView
    private lateinit var tvUrl: TextView
    private lateinit var tvToken: TextView
    private lateinit var ivPreview: ImageView
    private lateinit var btnToggle: Button
    private lateinit var etPort: EditText
    private lateinit var cbAutoStart: CheckBox

    private val ui = Handler(Looper.getMainLooper())
    private var lastSeq = -1L
    private var previewing = false

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        tvStatus = findViewById(R.id.tvStatus)
        tvUrl = findViewById(R.id.tvUrl)
        tvToken = findViewById(R.id.tvToken)
        ivPreview = findViewById(R.id.ivPreview)
        btnToggle = findViewById(R.id.btnToggle)
        etPort = findViewById(R.id.etPort)
        cbAutoStart = findViewById(R.id.cbAutoStart)

        etPort.setText(prefs.port.toString())
        tvToken.text = prefs.token
        cbAutoStart.isChecked = prefs.autoStart

        btnToggle.setOnClickListener { toggleService() }

        findViewById<Button>(R.id.btnOpen).setOnClickListener {
            val url = "${panelUrl()}"
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (t: Throwable) {
                toast("브라우저를 열 수 없습니다")
            }
        }

        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("cctv", panelUrl()))
            toast("주소를 복사했습니다")
        }

        findViewById<Button>(R.id.btnNewToken).setOnClickListener {
            val t = prefs.regenerateToken()
            tvToken.text = t
            toast("새 토큰이 발급되었습니다. 기존 접속은 끊깁니다.")
        }

        findViewById<Button>(R.id.btnApplyPort).setOnClickListener {
            val p = etPort.text.toString().toIntOrNull()
            if (p == null || p < 1024 || p > 65535) {
                toast("1024~65535 사이의 포트를 입력하세요")
            } else {
                prefs.port = p
                toast(
                    if (CctvService.isRunning) "포트를 저장했습니다. 중지 후 다시 시작하세요."
                    else "포트를 저장했습니다."
                )
            }
        }

        cbAutoStart.setOnCheckedChangeListener { _, checked -> prefs.autoStart = checked }

        findViewById<Button>(R.id.btnBattery).setOnClickListener { requestIgnoreBatteryOptimizations() }

        requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        // 화면을 보고 있는 동안만 JPEG 를 만들게 한다(평소에는 H.264 만 돌아 CPU 를 아낀다).
        if (!previewing) {
            previewing = true
            StreamHub.viewers.incrementAndGet()
        }
        ui.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        if (previewing) {
            previewing = false
            StreamHub.viewers.decrementAndGet()
        }
        ui.removeCallbacks(refresher)
    }

    private fun toggleService() {
        if (CctvService.isRunning) {
            CctvService.stop(this)
            toast("중지 요청을 보냈습니다")
        } else {
            if (!hasCamera()) {
                requestPermissions()
                toast("카메라 권한이 필요합니다")
                return
            }
            prefs.port = etPort.text.toString().toIntOrNull() ?: prefs.port
            CctvService.start(this)
            toast("시작하는 중…")
        }
        ui.postDelayed({ refresh() }, 600)
    }

    private fun refresh() {
        val running = CctvService.isRunning
        btnToggle.text = if (running) "CCTV 중지" else "CCTV 시작"
        tvUrl.text = if (running) panelUrl() else "중지 상태 (IP: ${NetUtil.primaryIp()})"

        if (running) {
            val age = StreamHub.lastFrameAgeMs()
            val h264 = when {
                !prefs.h264Enabled -> "H.264 꺼짐"
                VideoHub.ready -> "H.264 ${VideoHub.kbps} kbps"
                else -> "H.264 준비 중"
            }
            tvStatus.text = if (age in 0..3000) {
                "실행 중 · $h264 · 시청자 ${VideoHub.viewers.get()}명"
            } else {
                "실행 중 · $h264 · 카메라 준비 중…"
            }
        } else {
            tvStatus.text = "중지됨"
            ivPreview.setImageDrawable(null)
            lastSeq = -1L
        }

        val f = StreamHub.latest() ?: return
        if (f.seq == lastSeq) return
        lastSeq = f.seq
        try {
            val bmp = BitmapFactory.decodeByteArray(f.data, 0, f.data.size)
            if (bmp != null) ivPreview.setImageBitmap(bmp)
        } catch (ignored: Throwable) {
        }
    }

    private fun panelUrl(): String {
        val port = prefs.port
        return "http://${NetUtil.primaryIp()}:$port/?token=${prefs.token}"
    }

    private fun hasCamera() = ContextCompat.checkSelfPermission(
        this, android.Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val need = ArrayList<String>()
        if (!hasCamera()) need.add(android.Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, need.toTypedArray(), 100)
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            toast("이 안드로이드 버전에는 해당 설정이 없습니다")
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (t: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (t2: Throwable) {
                toast("설정 화면을 열 수 없습니다")
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
