package com.example.fakegps

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.fakegps.databinding.ActivityMainBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var map: MapView
    private lateinit var locationManager: LocationManager
    private lateinit var fusedClient: FusedLocationProviderClient

    private var userHasInteracted = false
    private var fakeActive = false // master start/stop state
    private var suppressSwitch = false // guard against re-entrant switch callbacks

    // Desired fake state (mirrored to the service, also used for the map/UI)
    private var currentPoint: GeoPoint? = null
    private var bearing: Float = 0f
    private var speedMps: Float = 0f
    private var moving = false
    private var userTouching = false
    private var movedWhileTouching = false
    private var lastCenter: GeoPoint? = null
    private var lastMoveTimeMs = 0L

    private var mockPrompted = false
    private var startupListener: LocationListener? = null

    // ---- Foreground mock-location service ---------------------------------

    private var mockService: MockLocationService? = null
    private var serviceStarted = false
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val svc = (service as MockLocationService.LocalBinder).service
            mockService = svc
            svc.onPush = { p, brng, mv -> runOnUiThread { onServicePush(p, brng, mv) } }
            svc.onError = { runOnUiThread { maybeShowMockDialog() } }

            val svcPoint = svc.currentPoint()
            if (svcPoint != null) {
                // Service is authoritative (it may have advanced while we were backgrounded)
                currentPoint = svcPoint
                lastCenter = svcPoint
                map.controller.animateTo(svcPoint)
                updateStatus()
            } else {
                val p = currentPoint
                if (p != null && userHasInteracted) svc.update(p, speedMps, bearing, moving)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mockService = null
        }
    }

    private fun ensureServiceBound() {
        // A location-type foreground service requires location permission to start.
        if (!hasLocationPermission()) {
            Toast.makeText(this, "위치 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(this, MockLocationService::class.java)
        try {
            ContextCompat.startForegroundService(this, i)
        } catch (e: Exception) {
            return
        }
        if (!bound) bound = bindService(i, connection, Context.BIND_AUTO_CREATE)
        serviceStarted = true
    }

    /** Callback from the service after each injected fix (auto-move follows on the map). */
    private fun onServicePush(p: GeoPoint, brng: Float, mv: Boolean) {
        if (userTouching || !fakeActive) return
        currentPoint = p
        bearing = brng
        if (mv) map.controller.animateTo(p) // keep the auto-moving point under the crosshair
        updateStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        // OSM tile servers block generic/"com.example" User-Agents. Use a unique,
        // identifying value so tile requests are not rejected ("Access blocked").
        Configuration.getInstance().userAgentValue = "FakeGPS-ywkim/1.0"
        // The "Access blocked" notice OSM returns is a real 200-PNG, so osmdroid
        // caches it like a normal tile. Purge that stale cache once so fresh tiles load.
        clearStaleTileCacheOnce()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        fusedClient = LocationServices.getFusedLocationProviderClient(this)

        setupMap()
        setupControls()
        updateStatus()
        requestPermissionsIfNeeded()
    }

    /** Deletes the osmdroid tile cache once, to drop stale "Access blocked" images. */
    private fun clearStaleTileCacheOnce() {
        val prefs = getSharedPreferences("fakegps", Context.MODE_PRIVATE)
        if (prefs.getBoolean("tilecache_cleared_v2", false)) return
        try {
            val cache = Configuration.getInstance().osmdroidTileCache
            cache?.listFiles()?.forEach { deleteRecursively(it) }
        } catch (_: Exception) {}
        prefs.edit().putBoolean("tilecache_cleared_v2", true).apply()
    }

    private fun deleteRecursively(f: File) {
        if (f.isDirectory) f.listFiles()?.forEach { deleteRecursively(it) }
        f.delete()
    }

    private fun setupMap() {
        map = binding.map
        // OSM's public tile server blocks third-party apps ("tile usage policy").
        // Use CARTO's basemap tiles instead (OSM data, no API key, permissive for low volume).
        val carto = XYTileSource(
            "CartoVoyager", 0, 20, 256, ".png",
            arrayOf(
                "https://a.basemaps.cartocdn.com/rastertiles/voyager/",
                "https://b.basemaps.cartocdn.com/rastertiles/voyager/",
                "https://c.basemaps.cartocdn.com/rastertiles/voyager/",
                "https://d.basemaps.cartocdn.com/rastertiles/voyager/"
            ),
            "© OpenStreetMap contributors, © CARTO"
        )
        map.setTileSource(carto)
        map.setMultiTouchControls(true)
        map.isTilesScaledToDpi = true
        map.setMinZoomLevel(3.0)
        map.setMaxZoomLevel(21.0)
        map.setFlingEnabled(false)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.ALWAYS)

        map.controller.setZoom(18.0)
        map.controller.setCenter(GeoPoint(37.5665, 126.9780))

        map.overlays.add(TouchTracker())

        map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                if (userTouching && fakeActive) {
                    val c = map.mapCenter
                    onUserMovedMapTo(GeoPoint(c.latitude, c.longitude))
                }
                return false
            }
            override fun onZoom(event: ZoomEvent?): Boolean {
                updateStatus()
                return false
            }
        })
    }

    private fun setupControls() {
        binding.startStopSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            if (isChecked) {
                if (!startFake()) setSwitchChecked(false) // revert if it couldn't start
            } else {
                stopFake()
            }
        }

        binding.myLocationButton.setOnClickListener {
            goToCurrentRealLocation(force = true)
        }
    }

    private fun setSwitchChecked(checked: Boolean) {
        suppressSwitch = true
        binding.startStopSwitch.isChecked = checked
        suppressSwitch = false
    }

    /** Tracks finger state without consuming touches, so the map still pans/zooms. */
    private inner class TouchTracker : Overlay() {
        override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    userTouching = true
                    userHasInteracted = true
                    movedWhileTouching = false
                    moving = false
                    mockService?.stopMoving()
                    val c = mapView.mapCenter
                    lastCenter = GeoPoint(c.latitude, c.longitude)
                    lastMoveTimeMs = SystemClock.elapsedRealtime()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    userTouching = false
                    // Location stays fixed where the finger lifted (no auto-move).
                    moving = false
                    speedMps = 0f
                    currentPoint?.let { if (fakeActive) mockService?.update(it, 0f, bearing, false) }
                    updateStatus()
                }
            }
            return false
        }
    }

    /** On each user-driven scroll, the map center (crosshair) becomes the fake fix. */
    private fun onUserMovedMapTo(center: GeoPoint) {
        if (!fakeActive) return
        val now = SystemClock.elapsedRealtime()
        val dt = ((now - lastMoveTimeMs).coerceAtLeast(1L)) / 1000.0
        val prev = lastCenter
        if (prev != null) {
            val meters = prev.distanceToAsDouble(center)
            if (meters > 0.01) {
                bearing = prev.bearingTo(center).toFloat()
                movedWhileTouching = true
                speedMps = (meters / dt).toFloat().coerceAtMost(83f)
            }
        }
        currentPoint = center
        moving = false
        mockService?.update(center, speedMps, bearing, false)
        lastCenter = center
        lastMoveTimeMs = now
        updateStatus()
    }

    // ---- Start / stop --------------------------------------------------------

    /** Returns true if fake GPS was started; false if it couldn't (e.g. no permission). */
    private fun startFake(): Boolean {
        if (!hasLocationPermission()) {
            requestPermissionsIfNeeded()
            return false
        }
        fakeActive = true
        userHasInteracted = true
        moving = false
        speedMps = 0f

        // Begin at the current crosshair (map center)
        val c = map.mapCenter
        val center = GeoPoint(c.latitude, c.longitude)
        currentPoint = center
        lastCenter = center

        ensureServiceBound()
        mockService?.update(center, 0f, bearing, false)

        updateStatus()
        Toast.makeText(this, "Fake GPS 시작 — 지도를 드래그해 위치를 이동하세요", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun stopFake() {
        fakeActive = false
        moving = false
        speedMps = 0f

        // Clear the mock so the device's real GPS takes over again. Delivering
        // ACTION_STOP works whether or not binding has completed yet.
        if (bound) {
            try { unbindService(connection) } catch (_: Exception) {}
            bound = false
        }
        mockService = null
        try {
            startService(Intent(this, MockLocationService::class.java).setAction(MockLocationService.ACTION_STOP))
        } catch (_: Exception) {}
        serviceStarted = false

        // Return the map (and reported position) to the real current location
        goToCurrentRealLocation(force = true)
        updateStatus()
        Toast.makeText(this, "Fake GPS 멈춤 — 실제 위치로 복귀합니다", Toast.LENGTH_SHORT).show()
    }

    // ---- Startup / current location ---------------------------------------

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun isLocationServiceEnabled(): Boolean = try {
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (_: Exception) { false }

    @SuppressLint("MissingPermission")
    private fun goToCurrentRealLocation(force: Boolean = true) {
        if (!hasLocationPermission()) return
        if (!isLocationServiceEnabled()) {
            Toast.makeText(this, "위치 서비스가 꺼져 있습니다. 설정에서 켜주세요.", Toast.LENGTH_LONG).show()
            try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (_: Exception) {}
            return
        }
        try {
            fusedClient.lastLocation.addOnSuccessListener { loc -> if (loc != null) centerOnReal(loc, force) }
        } catch (_: SecurityException) {}
        try {
            val cts = CancellationTokenSource()
            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { loc ->
                    if (loc != null) centerOnReal(loc, force) else fallbackToLocationManager(force)
                }
                .addOnFailureListener { fallbackToLocationManager(force) }
        } catch (e: SecurityException) {
        } catch (e: Throwable) {
            fallbackToLocationManager(force)
        }
    }

    @SuppressLint("MissingPermission")
    private fun fallbackToLocationManager(force: Boolean) {
        if (!hasLocationPermission()) return
        var best: Location? = null
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            try {
                val l = locationManager.getLastKnownLocation(p)
                if (l != null && (best == null || l.time > best.time)) best = l
            } catch (_: Exception) {}
        }
        best?.let { centerOnReal(it, force) }

        startupListener?.let { try { locationManager.removeUpdates(it) } catch (_: Exception) {} }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                centerOnReal(location, force)
                try { locationManager.removeUpdates(this) } catch (_: Exception) {}
                startupListener = null
            }
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        startupListener = listener
        var requested = false
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (locationManager.isProviderEnabled(p)) {
                    locationManager.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper())
                    requested = true
                }
            } catch (_: Exception) {}
        }
        if (!requested && best == null) {
            Toast.makeText(this, "현재 위치를 가져올 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun centerOnReal(loc: Location, force: Boolean) {
        if (!force && userHasInteracted) return
        val gp = GeoPoint(loc.latitude, loc.longitude)
        map.post {
            map.controller.setZoom(18.0)
            map.controller.setCenter(gp)
        }
        currentPoint = gp
        lastCenter = gp
        // "현재 위치" button while active: snap the fake fix to the real spot too
        if (force && fakeActive) {
            moving = false
            speedMps = 0f
            mockService?.update(gp, 0f, bearing, false)
        }
        updateStatus()
    }

    private fun maybeShowMockDialog() {
        if (mockPrompted) return
        mockPrompted = true
        showMockAppDialog()
    }

    private fun updateStatus() {
        val p = currentPoint
        if (!fakeActive) {
            binding.statusText.text = "■ 멈춤 · 실제 GPS 사용 중 (스위치 ON으로 시작)"
            if (p != null) {
                binding.coordText.text = String.format("%.6f, %.6f  (zoom %.1f)", p.latitude, p.longitude, map.zoomLevelDouble)
            }
            return
        }
        val state = when {
            userTouching -> "드래그 중 (위치 실시간 이동)"
            moving -> {
                val kmh = (speedMps * 3.6f).toInt()
                "자동 이동 중 · 방위 ${bearing.toInt()}° · $kmh km/h"
            }
            else -> "위치 고정됨 (백그라운드 유지)"
        }
        binding.statusText.text = "● Fake GPS 실행 중: $state"
        if (p != null) {
            binding.coordText.text = String.format("%.6f, %.6f  (zoom %.1f)", p.latitude, p.longitude, map.zoomLevelDouble)
        }
    }

    private fun showMockAppDialog() {
        AlertDialog.Builder(this)
            .setTitle("모의 위치 앱 설정 필요")
            .setMessage(
                "이 앱을 모의 위치 앱으로 지정해야 가짜 GPS를 주입할 수 있습니다.\n\n" +
                    "1. 설정 > 휴대전화 정보 > 빌드 번호 7회 탭 (개발자 옵션 활성화)\n" +
                    "2. 설정 > 개발자 옵션 > '모의 위치 앱 선택'\n" +
                    "3. 'Fake GPS' 선택 후 다시 시도"
            )
            .setPositiveButton("개발자 옵션 열기") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    // ---- Permissions & lifecycle ------------------------------------------

    private fun requestPermissionsIfNeeded() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
        } else {
            goToCurrentRealLocation(force = false)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            goToCurrentRealLocation(force = false)
        }
    }

    override fun onStart() {
        super.onStart()
        if (serviceStarted) ensureServiceBound()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
    }

    override fun onStop() {
        super.onStop()
        // Unbind but keep the service running so injection continues in the background.
        if (bound) {
            mockService?.onPush = null
            mockService?.onError = null
            try { unbindService(connection) } catch (_: Exception) {}
            bound = false
            mockService = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        startupListener?.let { try { locationManager.removeUpdates(it) } catch (_: Exception) {} }
    }
}
