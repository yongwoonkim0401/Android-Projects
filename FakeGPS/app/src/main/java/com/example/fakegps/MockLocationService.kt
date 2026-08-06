package com.example.fakegps

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import org.osmdroid.util.GeoPoint

/**
 * Foreground service that continuously injects the fake location so it survives
 * when the user switches to another app (map, navigation, etc.). It mocks the
 * legacy GPS/NETWORK providers AND the fused provider used by most modern apps.
 */
class MockLocationService : Service() {

    inner class LocalBinder : Binder() {
        val service: MockLocationService get() = this@MockLocationService
    }

    private val binder = LocalBinder()
    private lateinit var lm: LocationManager
    private var fused: FusedLocationProviderClient? = null

    private val handler = Handler(Looper.getMainLooper())
    private val intervalMs = TICK_INTERVAL_MS

    private val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    private var providersReady = false
    private var fusedMockReady = false

    // Fake state (single source of truth while active)
    private var point: GeoPoint? = null
    private var bearing = 0f
    private var speed = 0f          // m/s
    private var moving = false

    // Road route to follow (set via updateRoute); null means "just move along bearing/speed"
    private var route: List<GeoPoint>? = null
    private var routeSegmentIndex = 0

    /** Called on the main thread after each push so the UI can follow along. */
    var onPush: ((GeoPoint, Float, Boolean) -> Unit)? = null

    /** Called when mock injection fails (app not selected as mock location app). */
    var onError: (() -> Unit)? = null

    private val tick = object : Runnable {
        override fun run() {
            val p = point
            if (p != null) {
                val next = if (moving && speed > 0f) {
                    if (route != null) {
                        advanceAlongRoute(p, speed * (intervalMs / 1000.0))
                    } else {
                        p.destinationPoint(speed * (intervalMs / 1000.0), bearing.toDouble())
                    }
                } else p
                point = next
                push(next)
                onPush?.invoke(next, bearing, moving)
            }
            handler.postDelayed(this, intervalMs)
        }
    }

    /** Walks [distanceMeters] forward along [route] from [from], crossing waypoints as needed. */
    private fun advanceAlongRoute(from: GeoPoint, distanceMeters: Double): GeoPoint {
        val pts = route ?: return from
        var current = from
        var remaining = distanceMeters
        var idx = routeSegmentIndex
        while (remaining > 0.0 && idx < pts.size) {
            val target = pts[idx]
            val segDist = current.distanceToAsDouble(target)
            if (segDist <= remaining) {
                remaining -= segDist
                current = target
                idx++
            } else {
                val brng = current.bearingTo(target)
                bearing = brng.toFloat()
                current = current.destinationPoint(remaining, brng)
                remaining = 0.0
            }
        }
        routeSegmentIndex = idx
        if (idx >= pts.size) moving = false // reached the end of the route
        return current
    }

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        try { fused = LocationServices.getFusedLocationProviderClient(this) } catch (_: Throwable) {}
        startForegroundNotification()
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMocking()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /** Clears the mock (real GPS resumes), drops the notification and stops the service. */
    fun stopMocking() {
        clearMock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun clearMock() {
        handler.removeCallbacks(tick)
        point = null
        moving = false
        route = null
        for (pr in providers) {
            try { lm.removeTestProvider(pr) } catch (_: Exception) {}
        }
        providersReady = false
        try { if (fusedMockReady) fused?.setMockMode(false) } catch (_: Throwable) {}
        fusedMockReady = false
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** Update the fake target. moving=true keeps advancing along [brng] at [spd]. */
    fun update(p: GeoPoint, spd: Float, brng: Float, mv: Boolean) {
        route = null
        point = p
        speed = spd
        bearing = brng
        moving = mv
        push(p)
    }

    /** Follow [routePoints] (e.g. a road-snapped path) at [spd], starting from its first point. */
    fun updateRoute(routePoints: List<GeoPoint>, spd: Float) {
        val start = routePoints.firstOrNull() ?: return
        route = routePoints
        routeSegmentIndex = 1
        point = start
        speed = spd
        bearing = if (routePoints.size > 1) start.bearingTo(routePoints[1]).toFloat() else bearing
        moving = routePoints.size > 1
        push(start)
    }

    /** Change the travel speed without disturbing the route or the progress along it. */
    fun setSpeed(spd: Float) {
        speed = spd
    }

    fun stopMoving() {
        moving = false
        speed = 0f
    }

    fun currentPoint(): GeoPoint? = point

    fun isActive(): Boolean = providersReady

    @SuppressLint("MissingPermission")
    private fun ensureProviders(): Boolean {
        if (providersReady) return true
        try {
            for (pr in providers) {
                try { lm.removeTestProvider(pr) } catch (_: Exception) {}
                lm.addTestProvider(
                    pr, false, false, false, false, true, true, true,
                    1 /* Criteria.POWER_LOW */, 1 /* Criteria.ACCURACY_FINE */
                )
                lm.setTestProviderEnabled(pr, true)
            }
            providersReady = true
        } catch (e: Exception) {
            onError?.invoke()
            return false
        }
        // Also mock the fused provider so Google Play services based apps follow.
        try {
            fused?.setMockMode(true)
                ?.addOnSuccessListener { fusedMockReady = true }
                ?.addOnFailureListener { fusedMockReady = false }
        } catch (_: Throwable) {}
        return true
    }

    @SuppressLint("MissingPermission")
    private fun push(p: GeoPoint) {
        if (!ensureProviders()) return
        for (pr in providers) {
            try {
                lm.setTestProviderLocation(pr, buildLocation(pr, p))
            } catch (e: Exception) {
                providersReady = false
                onError?.invoke()
            }
        }
        if (fusedMockReady) {
            try { fused?.setMockLocation(buildLocation(LocationManager.GPS_PROVIDER, p)) } catch (_: Throwable) {}
        }
    }

    private fun buildLocation(provider: String, p: GeoPoint): Location =
        Location(provider).apply {
            latitude = p.latitude
            longitude = p.longitude
            altitude = 0.0
            accuracy = 1f
            speed = this@MockLocationService.speed
            bearing = this@MockLocationService.bearing
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                bearingAccuracyDegrees = 0.5f
                speedAccuracyMetersPerSecond = 0.5f
                verticalAccuracyMeters = 0.5f
            }
        }

    private fun startForegroundNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "가짜 GPS", NotificationManager.IMPORTANCE_LOW)
            channel.description = "모의 위치 주입 실행 중"
            nm.createNotificationChannel(channel)
        }

        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, MockLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("가짜 GPS 실행 중")
            .setContentText("다른 앱으로 전환해도 모의 위치가 유지됩니다")
            .setSmallIcon(R.drawable.ic_crosshair)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "중지", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        for (pr in providers) {
            try { lm.removeTestProvider(pr) } catch (_: Exception) {}
        }
        try { if (fusedMockReady) fused?.setMockMode(false) } catch (_: Throwable) {}
        onPush = null
        onError = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.example.fakegps.STOP"
        /** Push rate. Short enough that each step is a small, smooth increment on the map. */
        const val TICK_INTERVAL_MS = 250L
        private const val CHANNEL_ID = "fakegps_mock"
        private const val NOTIF_ID = 1001
    }
}
