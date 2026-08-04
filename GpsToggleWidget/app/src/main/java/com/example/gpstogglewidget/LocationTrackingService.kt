package com.example.gpstogglewidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * GPS로부터 위도/경도/고도를 5초 간격으로 수집하는 포그라운드 서비스.
 * 수집된 좌표는 GPX 파일로 저장된다 (파일명 = 수집 시작 시각).
 * 위젯 탭(수집 ON)으로 시작되고, 다시 탭(수집 OFF)하면 중지된다.
 */
class LocationTrackingService : Service(), LocationListener {

    companion object {
        private const val CHANNEL_ID = "gps_tracking"
        private const val NOTIFICATION_ID = 1

        // 위치 갱신 주기: 5초
        private const val UPDATE_INTERVAL_MS = 5000L
        private const val UPDATE_MIN_DISTANCE_M = 0f

        // 서비스의 실제 생존 여부 — SharedPreferences 플래그는 프로세스 강제종료 시
        // 지워지지 않아 신뢰할 수 없으므로, 토글 판단은 반드시 이 값으로 한다
        @Volatile
        var isRunning = false
            private set
    }

    private lateinit var locationManager: LocationManager
    private var gpxFile: File? = null

    // GPS 위성 fix를 받았는지 여부 — 받기 전까지는 네트워크 위치로 기록
    private var hasGpsFix = false

    // GPX <time> 요소는 UTC ISO 8601 형식
    private val gpxTimeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        try {
            hasGpsFix = false
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                UPDATE_INTERVAL_MS,
                UPDATE_MIN_DISTANCE_M,
                this
            )
            // GPS fix 전에도 즉시 기록을 시작할 수 있도록 네트워크 위치도 함께 수신
            if (locationManager.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    UPDATE_INTERVAL_MS,
                    UPDATE_MIN_DISTANCE_M,
                    this
                )
            }
            startGpxFile()
            GpsToggleWidget.setTracking(this, true)

            // 마지막으로 알려진 위치를 즉시 첫 포인트로 기록 (대기 시간 없음)
            val lastKnown = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER
            ).filter { locationManager.allProviders.contains(it) }
                .mapNotNull { locationManager.getLastKnownLocation(it) }
                .maxByOrNull { it.time }

            if (lastKnown != null) {
                GpsToggleWidget.saveLocationText(this, GpsToggleWidget.formatLocation(lastKnown))
                appendTrackPoint(lastKnown)
            } else {
                GpsToggleWidget.saveLocationText(this, "위치 확인 중…")
            }
        } catch (e: SecurityException) {
            // 위치 권한 없음 → 수집 불가
            GpsToggleWidget.setTracking(this, false)
            GpsToggleWidget.saveLocationText(this, "위치 권한 필요")
            stopSelf()
        }
        GpsToggleWidget.refreshAllWidgets(this)
        return START_STICKY
    }

    override fun onLocationChanged(location: Location) {
        if (location.provider == LocationManager.GPS_PROVIDER) {
            if (!hasGpsFix) {
                hasGpsFix = true
                // GPS fix 확보 → 네트워크 위치는 더 이상 필요 없음
                locationManager.removeUpdates(this)
                try {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        UPDATE_INTERVAL_MS,
                        UPDATE_MIN_DISTANCE_M,
                        this
                    )
                } catch (e: SecurityException) {
                    stopSelf()
                    return
                }
            }
        } else if (hasGpsFix) {
            // 이미 GPS로 기록 중이면 네트워크 위치는 무시 (중복 방지)
            return
        }

        GpsToggleWidget.saveLocationText(this, GpsToggleWidget.formatLocation(location))
        GpsToggleWidget.refreshAllWidgets(this)
        appendTrackPoint(location)
    }

    @Deprecated("Deprecated in API 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {
    }

    override fun onProviderDisabled(provider: String) {
        // 시스템 GPS가 꺼지면 수집 중지
        if (provider == LocationManager.GPS_PROVIDER) {
            GpsToggleWidget.saveLocationText(this, "GPS가 꺼져 있습니다")
            stopSelf()
        }
    }

    override fun onProviderEnabled(provider: String) {
    }

    override fun onDestroy() {
        isRunning = false
        locationManager.removeUpdates(this)
        closeGpxFile()
        GpsToggleWidget.setTracking(this, false)
        GpsToggleWidget.refreshAllWidgets(this)
        super.onDestroy()
    }

    // ─────────────────────────── GPX 파일 기록 ───────────────────────────

    /** 수집 시작 시각을 파일명으로 GPX 파일을 만들고 헤더를 기록한다. */
    private fun startGpxFile() {
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir
        // "Track 년-월-일 출발시각.gpx" (출발시각은 24시간 기준, 구분자 없이 HHmmss)
        val fileName = "Track " +
            SimpleDateFormat("yyyy-MM-dd HHmmss", Locale.US).format(Date()) + ".gpx"
        val file = File(dir, fileName)

        file.writeText(
            """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<gpx version="1.1" creator="GpsToggleWidget"
            |     xmlns="http://www.topografix.com/GPX/1/1">
            |  <trk>
            |    <name>${fileName.removeSuffix(".gpx")}</name>
            |    <trkseg>
            |
            """.trimMargin()
        )
        gpxFile = file
    }

    /** 트랙포인트 한 개를 GPX 파일에 추가한다. */
    private fun appendTrackPoint(location: Location) {
        val file = gpxFile ?: return
        val lat = String.format(Locale.US, "%.6f", location.latitude)
        val lon = String.format(Locale.US, "%.6f", location.longitude)
        val time = gpxTimeFormat.format(Date(location.time))

        val sb = StringBuilder()
        sb.append("      <trkpt lat=\"$lat\" lon=\"$lon\">\n")
        // GPX <ele>은 해수면 기준 고도 (GPX 표준). MSL 값 우선, 없으면 타원체 고도.
        val alt = GpsToggleWidget.seaLevelAltitude(location)
        if (alt != null) {
            sb.append("        <ele>${String.format(Locale.US, "%.1f", alt)}</ele>\n")
        }
        sb.append("        <time>$time</time>\n")
        sb.append("      </trkpt>\n")

        file.appendText(sb.toString())
    }

    /** GPX 닫는 태그를 기록하고 파일을 마무리한다. */
    private fun closeGpxFile() {
        val file = gpxFile ?: return
        file.appendText(
            """
            |    </trkseg>
            |  </trk>
            |</gpx>
            |
            """.trimMargin()
        )
        gpxFile = null
    }

    // ─────────────────────────── 알림 ───────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GPS 위치 수집",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS 위치 수집 중")
            .setContentText("5초 간격으로 GPX 파일에 기록됩니다")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }
}
