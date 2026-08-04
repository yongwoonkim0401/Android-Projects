package com.example.androidcctv

import android.content.Context
import java.security.SecureRandom

/**
 * 앱 전체 설정. HTTP API(/api/config)와 화면에서 동일한 값을 읽고 쓴다.
 */
class Prefs(ctx: Context) {

    private val sp = ctx.applicationContext.getSharedPreferences("cctv", Context.MODE_PRIVATE)

    var port: Int
        get() = sp.getInt("port", 8080)
        set(v) = sp.edit().putInt("port", v.coerceIn(1024, 65535)).apply()

    /** 스트림/제어 API 접근 토큰. 최초 실행 시 무작위 생성한다. */
    var token: String
        get() {
            var t = sp.getString("token", null)
            if (t.isNullOrEmpty()) {
                t = newToken()
                sp.edit().putString("token", t).apply()
            }
            return t
        }
        set(v) = sp.edit().putString("token", v).apply()

    /** "front" 또는 "back" */
    var lens: String
        get() = sp.getString("lens", "front") ?: "front"
        set(v) = sp.edit().putString("lens", if (v == "back") "back" else "front").apply()

    var width: Int
        get() = sp.getInt("width", 640)
        set(v) = sp.edit().putInt("width", v.coerceIn(160, 1920)).apply()

    var height: Int
        get() = sp.getInt("height", 480)
        set(v) = sp.edit().putInt("height", v.coerceIn(120, 1080)).apply()

    /** JPEG 품질 1~100 */
    var quality: Int
        get() = sp.getInt("quality", 60)
        set(v) = sp.edit().putInt("quality", v.coerceIn(10, 100)).apply()

    /**
     * 목표 초당 프레임 수 1~30.
     * 카메라 자체의 촬영 속도(AE 목표 FPS 범위)와 MJPEG 전송 속도를 함께 결정한다.
     * 낮출수록 센서·ISP·인코더가 모두 덜 돌아 배터리에 가장 큰 영향을 준다.
     */
    var fps: Int
        get() = sp.getInt("fps", 10)
        set(v) = sp.edit().putInt("fps", v.coerceIn(1, 30)).apply()

    /** 추가 회전각 0/90/180/270 */
    var rotation: Int
        get() = sp.getInt("rotation", 0)
        set(v) = sp.edit().putInt("rotation", ((v % 360) + 360) % 360 / 90 * 90).apply()

    var mirror: Boolean
        get() = sp.getBoolean("mirror", false)
        set(v) = sp.edit().putBoolean("mirror", v).apply()

    var motionEnabled: Boolean
        get() = sp.getBoolean("motionEnabled", true)
        set(v) = sp.edit().putBoolean("motionEnabled", v).apply()

    /** 1(둔감) ~ 100(민감) */
    var motionSensitivity: Int
        get() = sp.getInt("motionSensitivity", 40)
        set(v) = sp.edit().putInt("motionSensitivity", v.coerceIn(1, 100)).apply()

    /** 움직임 이벤트 저장 최소 간격(초) */
    var motionCooldown: Int
        get() = sp.getInt("motionCooldown", 10)
        set(v) = sp.edit().putInt("motionCooldown", v.coerceIn(1, 3600)).apply()

    /** 움직임 감지 시 JPEG 스냅샷 저장 여부 */
    var motionSaveShot: Boolean
        get() = sp.getBoolean("motionSaveShot", true)
        set(v) = sp.edit().putBoolean("motionSaveShot", v).apply()

    /** H.264 하드웨어 인코딩 스트림 사용 여부. 끄면 MJPEG 만 제공한다. */
    var h264Enabled: Boolean
        get() = sp.getBoolean("h264Enabled", true)
        set(v) = sp.edit().putBoolean("h264Enabled", v).apply()

    /** H.264 목표 비트레이트(kbps). 대역폭은 사실상 이 값으로 결정된다. */
    var bitrateKbps: Int
        get() = sp.getInt("bitrateKbps", 600)
        set(v) = sp.edit().putInt("bitrateKbps", v.coerceIn(100, 8000)).apply()

    /** 키프레임 간격(초). 짧으면 접속이 빠르고 길면 대역폭·전력이 준다. */
    var keyInterval: Int
        get() = sp.getInt("keyInterval", 2)
        set(v) = sp.edit().putInt("keyInterval", v.coerceIn(1, 10)).apply()

    /**
     * 고성능 Wi‑Fi 잠금. 켜면 Wi‑Fi 절전이 꺼져 응답이 빨라지지만 전력 소모가 크게 늘어난다.
     * 기본은 꺼짐(일반 잠금만 사용).
     */
    var highPerfWifi: Boolean
        get() = sp.getBoolean("highPerfWifi", false)
        set(v) = sp.edit().putBoolean("highPerfWifi", v).apply()

    var autoStart: Boolean
        get() = sp.getBoolean("autoStart", true)
        set(v) = sp.edit().putBoolean("autoStart", v).apply()

    /** 마지막으로 사용자가 의도한 실행 상태(부팅 후 자동 복구에 사용) */
    var wasRunning: Boolean
        get() = sp.getBoolean("wasRunning", false)
        set(v) = sp.edit().putBoolean("wasRunning", v).apply()

    /** 보관할 최대 이벤트 스냅샷 개수 */
    var maxEvents: Int
        get() = sp.getInt("maxEvents", 300)
        set(v) = sp.edit().putInt("maxEvents", v.coerceIn(10, 5000)).apply()

    fun regenerateToken(): String {
        val t = newToken()
        token = t
        return t
    }

    private fun newToken(): String {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        val sb = StringBuilder()
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
