package com.example.fakegps

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
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
import android.view.animation.LinearInterpolator
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
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var map: MapView
    private lateinit var locationManager: LocationManager
    private lateinit var fusedClient: FusedLocationProviderClient

    private var userHasInteracted = false
    private var fakeActive = false // master start/stop state

    // Desired fake state (mirrored to the service, also used for the map/UI)
    private var currentPoint: GeoPoint? = null
    private var bearing: Float = 0f
    private var speedMps: Float = 0f
    private var moving = false
    private var userTouching = false
    private var movedWhileTouching = false
    private var lastCenter: GeoPoint? = null
    private var lastMoveTimeMs = 0L

    // ---- Route (auto-move start -> end at a set speed) ---------------------

    private enum class PickMode { NONE, START, END }
    private var pickMode = PickMode.NONE
    private var startPoint: GeoPoint? = null
    private var endPoint: GeoPoint? = null
    private var startMarker: Marker? = null
    private var endMarker: Marker? = null
    private var routePolyline: List<GeoPoint>? = null
    private var routeLineOverlay: Polyline? = null
    private var routeActive = false
    private var routeSpeedKmh = 12
    private var routeFetchPending = false
    private var activeRoutePath: List<GeoPoint>? = null
    private var mapPanAnimator: ValueAnimator? = null

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

            // Binding is async: a route started before it completed still needs handing over.
            val pending = activeRoutePath
            if (routeActive && pending != null) {
                svc.updateRoute(pending, speedMps)
                updateStatus()
                return
            }

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
        if (mv) smoothPanTo(p, MockLocationService.TICK_INTERVAL_MS)

        // The service clears `moving` once it runs off the end of the route.
        if (routeActive && !mv) {
            arriveAtRouteEnd(endPoint ?: p)
            return
        }
        updateStatus()
    }

    /**
     * Pans the map to [target] at a constant rate over [durationMs]. osmdroid's own
     * `animateTo` eases in/out, which makes the map visibly stop and restart on every
     * push; a linear interpolation over exactly one tick keeps the motion continuous.
     */
    private fun smoothPanTo(target: GeoPoint, durationMs: Long) {
        mapPanAnimator?.cancel()
        val center = map.mapCenter
        val fromLat = center.latitude
        val fromLon = center.longitude
        val dLat = target.latitude - fromLat
        val dLon = target.longitude - fromLon
        if (dLat == 0.0 && dLon == 0.0) return

        mapPanAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val f = (anim.animatedValue as Float).toDouble()
                map.controller.setCenter(GeoPoint(fromLat + dLat * f, fromLon + dLon * f))
            }
            start()
        }
    }

    private fun cancelMapPan() {
        mapPanAnimator?.cancel()
        mapPanAnimator = null
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

        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean = handleMapTap(p)
            override fun longPressHelper(p: GeoPoint): Boolean = false
        }))
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
        binding.startStopButton.setOnClickListener {
            if (fakeActive) stopFake() else startFake()
        }

        binding.routeToggleButton.setOnClickListener {
            if (routeActive) stopRoute() else startRoute()
            updateToggleButtonsUI()
        }

        binding.setRoutePointsButton.setOnClickListener {
            beginRoutePicking()
        }

        binding.speedMinusButton.setOnClickListener { changeSpeed(-1) }

        binding.speedPlusButton.setOnClickListener { changeSpeed(+1) }

        binding.myLocationButton.setOnClickListener {
            goToCurrentRealLocation(force = true)
        }

        updateToggleButtonsUI()
        updateSpeedText()
    }

    private fun updateToggleButtonsUI() {
        binding.startStopButton.text = if (fakeActive) "ON" else "OFF"
        binding.startStopButton.backgroundTintList =
            ColorStateList.valueOf(if (fakeActive) COLOR_ON else COLOR_OFF)

        binding.routeToggleButton.text = if (routeActive) "ON" else "OFF"
        binding.routeToggleButton.backgroundTintList =
            ColorStateList.valueOf(if (routeActive) COLOR_ROUTE_ON else COLOR_OFF)
    }

    /** Adjusts the travel speed, applying it live if auto-move is already running. */
    private fun changeSpeed(deltaKmh: Int) {
        routeSpeedKmh = (routeSpeedKmh + deltaKmh).coerceIn(1, 200)
        updateSpeedText()
        if (routeActive) {
            speedMps = routeSpeedKmh / 3.6f
            mockService?.setSpeed(speedMps)
            updateStatus()
        }
    }

    private fun updateSpeedText() {
        binding.speedValueText.text = "$routeSpeedKmh km/h"
    }

    // ---- Route picking (tap the map to set start/end points) ---------------

    private fun handleMapTap(p: GeoPoint): Boolean {
        return when (pickMode) {
            PickMode.START -> {
                setStartPoint(p)
                pickMode = PickMode.END
                Toast.makeText(this, "도착점을 지도에서 탭하세요", Toast.LENGTH_SHORT).show()
                true
            }
            PickMode.END -> {
                setEndPoint(p)
                pickMode = PickMode.NONE
                Toast.makeText(this, "출발점/도착점이 설정되었습니다", Toast.LENGTH_SHORT).show()
                true
            }
            PickMode.NONE -> false
        }
    }

    private fun beginRoutePicking() {
        if (routeActive) {
            stopRoute()
            updateToggleButtonsUI()
        }
        clearRouteMarkers()
        clearRoutePolyline()
        startPoint = null
        endPoint = null
        routeFetchPending = false // any in-flight fetch is now stale and won't touch this
        pickMode = PickMode.START
        Toast.makeText(this, "출발점을 지도에서 탭하세요", Toast.LENGTH_SHORT).show()
    }

    private fun setStartPoint(p: GeoPoint) {
        startPoint = p
        startMarker?.let { map.overlays.remove(it) }
        startMarker = createRouteMarker(p, COLOR_ON)
        map.overlays.add(startMarker)
        map.invalidate()
    }

    private fun setEndPoint(p: GeoPoint) {
        endPoint = p
        endMarker?.let { map.overlays.remove(it) }
        endMarker = createRouteMarker(p, COLOR_MARKER_END)
        map.overlays.add(endMarker)
        map.invalidate()

        val start = startPoint ?: return
        clearRoutePolyline()
        routeFetchPending = true
        Toast.makeText(this, "도로 경로를 계산하는 중...", Toast.LENGTH_SHORT).show()
        fetchRoadRoute(start, p) { route, error ->
            if (startPoint !== start || endPoint !== p) return@fetchRoadRoute // stale (points changed meanwhile)
            routeFetchPending = false
            if (route != null) {
                routePolyline = route
                showRoutePolyline(route)
                Toast.makeText(this, "도로 경로 설정됨 (${route.size}개 지점)", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "도로 경로 실패: $error — 직선으로 이동합니다", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun createRouteMarker(p: GeoPoint, tint: Int): Marker {
        val marker = Marker(map)
        marker.setPosition(p)
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        val icon = ContextCompat.getDrawable(this, R.drawable.ic_marker)?.mutate()
        icon?.setTint(tint)
        marker.icon = icon
        return marker
    }

    private fun clearRouteMarkers() {
        startMarker?.let { map.overlays.remove(it) }
        endMarker?.let { map.overlays.remove(it) }
        startMarker = null
        endMarker = null
        map.invalidate()
    }

    // ---- Road routing (OSRM demo server — no API key, low-volume use only) ------

    private fun fetchRoadRoute(
        start: GeoPoint,
        end: GeoPoint,
        onResult: (List<GeoPoint>?, String?) -> Unit
    ) {
        Thread {
            var route: List<GeoPoint>? = null
            var error: String? = null
            for (host in ROUTING_HOSTS) {
                // OSRM wants lon,lat and a '.' decimal separator regardless of device locale.
                val url = String.format(
                    java.util.Locale.US,
                    "%s/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson",
                    host, start.longitude, start.latitude, end.longitude, end.latitude
                )
                try {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 10000
                        readTimeout = 10000
                        setRequestProperty("User-Agent", "FakeGPS-ywkim/1.0")
                        setRequestProperty("Accept", "application/json")
                    }
                    try {
                        val code = conn.responseCode
                        if (code != HttpURLConnection.HTTP_OK) {
                            error = "HTTP $code"
                            continue
                        }
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val parsed = parseOsrmRoute(body)
                        if (parsed != null) {
                            route = parsed
                            error = null
                            break
                        }
                        error = "경로 없음(${JSONObject(body).optString("code", "?")})"
                    } finally {
                        conn.disconnect()
                    }
                } catch (e: Exception) {
                    error = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
                }
            }
            runOnUiThread { onResult(route, error) }
        }.start()
    }

    private fun parseOsrmRoute(json: String): List<GeoPoint>? {
        return try {
            val root = JSONObject(json)
            if (root.optString("code") != "Ok") return null
            val routes = root.optJSONArray("routes") ?: return null
            if (routes.length() == 0) return null
            val coords = routes.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
            val points = ArrayList<GeoPoint>(coords.length())
            for (i in 0 until coords.length()) {
                val c = coords.getJSONArray(i)
                points.add(GeoPoint(c.getDouble(1), c.getDouble(0))) // GeoJSON is [lon, lat]
            }
            points.takeIf { it.size >= 2 }
        } catch (_: Exception) {
            null
        }
    }

    private fun showRoutePolyline(points: List<GeoPoint>) {
        routeLineOverlay?.let { map.overlays.remove(it) }
        val line = Polyline(map)
        line.setPoints(points)
        line.setColor(COLOR_ROUTE_ON)
        line.setWidth(6f)
        routeLineOverlay = line
        map.overlays.add(line)
        map.invalidate()
    }

    private fun clearRoutePolyline() {
        routeLineOverlay?.let { map.overlays.remove(it) }
        routeLineOverlay = null
        routePolyline = null
    }

    // ---- Route auto-move (start -> end at the set speed) --------------------

    private fun startRoute(): Boolean {
        val start = startPoint
        val tappedEnd = endPoint
        if (start == null || tappedEnd == null) {
            Toast.makeText(this, "먼저 출발점/도착점을 지정하세요", Toast.LENGTH_SHORT).show()
            return false
        }
        if (routeFetchPending) {
            Toast.makeText(this, "도로 경로를 계산 중입니다. 잠시 후 다시 시도하세요", Toast.LENGTH_SHORT).show()
            return false
        }
        if (!fakeActive && !startFake(showToast = false)) return false

        // Follow the fetched road route if we have one; otherwise fall back to a straight line.
        val path = routePolyline?.takeIf { it.size >= 2 } ?: listOf(start, tappedEnd)
        endPoint = path.last() // keep arrival detection aligned with where the path actually ends
        activeRoutePath = path

        currentPoint = start
        lastCenter = start
        bearing = start.bearingTo(path[1]).toFloat()
        speedMps = routeSpeedKmh / 3.6f
        moving = true
        routeActive = true

        map.controller.animateTo(start)
        mockService?.updateRoute(path, speedMps)
        updateStatus()
        Toast.makeText(this, "자동 이동 시작 — 도착점까지 이동합니다", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun stopRoute() {
        if (!routeActive) return
        routeActive = false
        moving = false
        speedMps = 0f
        activeRoutePath = null
        cancelMapPan()
        mockService?.stopMoving()
        currentPoint?.let { if (fakeActive) mockService?.update(it, 0f, bearing, false) }
        updateStatus()
    }

    private fun arriveAtRouteEnd(target: GeoPoint) {
        currentPoint = target
        stopRoute()
        map.controller.animateTo(target)
        updateToggleButtonsUI()
        Toast.makeText(this, "도착점에 도달했습니다", Toast.LENGTH_SHORT).show()
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
                    cancelMapPan() // hand the map back to the finger immediately
                    if (routeActive) {
                        stopRoute()
                        updateToggleButtonsUI()
                    }
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
    private fun startFake(showToast: Boolean = true): Boolean {
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

        updateToggleButtonsUI()
        updateStatus()
        if (showToast) {
            Toast.makeText(this, "Fake GPS 시작 — 지도를 드래그해 위치를 이동하세요", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun stopFake() {
        fakeActive = false
        moving = false
        speedMps = 0f
        routeActive = false
        activeRoutePath = null
        cancelMapPan()

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
        updateToggleButtonsUI()
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
            routeActive -> {
                val kmh = (speedMps * 3.6f).toInt()
                "자동 이동 중(경로) · 방위 ${bearing.toInt()}° · $kmh km/h"
            }
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
        cancelMapPan()
        startupListener?.let { try { locationManager.removeUpdates(it) } catch (_: Exception) {} }
    }

    companion object {
        // OSRM-compatible public routing servers, tried in order (no API key required).
        private val ROUTING_HOSTS = listOf(
            "https://routing.openstreetmap.de/routed-car",
            "https://router.project-osrm.org"
        )

        private val COLOR_ON = Color.parseColor("#43A047")
        private val COLOR_OFF = Color.parseColor("#757575")
        private val COLOR_ROUTE_ON = Color.parseColor("#1E88E5")
        private val COLOR_MARKER_END = Color.parseColor("#E53935")
    }
}
