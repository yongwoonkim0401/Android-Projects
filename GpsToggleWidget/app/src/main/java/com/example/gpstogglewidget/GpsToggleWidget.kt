package com.example.gpstogglewidget

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * GPS 정보(위도/경도/고도) 수집 시작/중지를 토글하는 위젯.
 * - 수집 ON: LocationTrackingService가 GPS로부터 좌표를 연속 수집해 위젯에 표시
 * - 수집 OFF: 서비스 중지
 * 시스템 GPS 자체는 켜고 끄지 않는다.
 */
class GpsToggleWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE_TRACKING -> {
                handleClick(context)
            }
            LocationManager.PROVIDERS_CHANGED_ACTION -> {
                refreshAllWidgets(context)
            }
        }
    }

    /**
     * 클릭 횟수 감지: 마지막 클릭 후 일정 시간 동안 추가 클릭이 없으면 확정한다.
     * - 1회: 수집 ON/OFF 토글
     * - 2회: GPX 경로 뷰어 열기
     * - 3회 이상: GPX 저장 폴더 열기
     *
     * 주의: goAsync()를 쓰면 다음 브로드캐스트 전달이 receiver 종료까지 지연되어
     * 두 번째 클릭이 카운트 창 안에 도착하지 못한다. 반드시 onReceive를 즉시
     * 반환시키고 Handler로만 지연 처리한다.
     */
    private fun handleClick(context: Context) {
        val appContext = context.applicationContext

        clickCount++
        clickHandler.removeCallbacksAndMessages(null)

        clickHandler.postDelayed({
            val count = clickCount
            clickCount = 0
            when {
                count >= 3 -> appContext.startActivity(
                    Intent(appContext, MapSettingsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                count == 2 -> appContext.startActivity(
                    Intent(appContext, GpxViewerActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                else -> appContext.startActivity(
                    Intent(appContext, ToggleActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                )
            }
        }, MULTI_CLICK_WINDOW_MS)
    }

    companion object {
        const val ACTION_TOGGLE_TRACKING = "com.example.gpstogglewidget.ACTION_TOGGLE_TRACKING"

        private const val PREFS = "gps_widget"
        private const val KEY_LOC_TEXT = "loc_text"
        private const val KEY_TRACKING = "tracking"
        private const val KEY_MAP_PROVIDER = "map_provider"

        /** 경로 표시에 사용할 지도: "osm"(기본) | "naver" | "kakao" */
        fun getMapProvider(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_MAP_PROVIDER, "osm") ?: "osm"

        fun setMapProvider(context: Context, provider: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_MAP_PROVIDER, provider).apply()
        }

        // 마지막 클릭 후 이 시간 안에 추가 클릭이 없으면 클릭 횟수 확정
        private const val MULTI_CLICK_WINDOW_MS = 450L

        // 멀티클릭 카운트 상태 (프로세스 생존 동안 유지)
        private var clickCount = 0
        private val clickHandler = Handler(Looper.getMainLooper())

        fun toggleTracking(context: Context) {
            // SharedPreferences 플래그가 아닌 실제 서비스 생존 여부로 판단한다
            // (프로세스 강제종료 시 플래그만 true로 남는 문제 방지)
            if (LocationTrackingService.isRunning) {
                // 수집 중지
                context.stopService(Intent(context, LocationTrackingService::class.java))
                // onDestroy에서 상태/위젯 갱신됨
            } else {
                // 오래된 플래그 정리
                if (isTracking(context)) {
                    setTracking(context, false)
                }
                // 수집 시작 전 사전 조건 확인
                if (!isGpsEnabled(context)) {
                    Toast.makeText(context, "기기의 GPS(위치)를 먼저 켜 주세요", Toast.LENGTH_SHORT).show()
                    return
                }
                if (ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_FINE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Toast.makeText(context, "앱을 열어 위치 권한을 허용해 주세요", Toast.LENGTH_SHORT).show()
                    saveLocationText(context, "위치 권한 필요")
                    refreshAllWidgets(context)
                    return
                }
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, LocationTrackingService::class.java)
                )
            }
        }

        fun isTracking(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_TRACKING, false)

        fun setTracking(context: Context, tracking: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_TRACKING, tracking)
                .apply()
            if (!tracking) {
                saveLocationText(context, "")
            }
        }

        /**
         * 해수면(MSL) 기준 고도(m)를 반환한다. null이면 고도 정보 없음.
         *
         * Location.getAltitude()는 WGS84 타원체 기준이라 한국에서 실제 해발고도보다
         * 약 +23~29m 높게 나온다. Android 14(API 34)+ 는 지오이드 보정을 마친
         * 해수면 기준 고도(getMslAltitudeMeters)를 제공하므로 이를 우선 사용하고,
         * 없으면 타원체 고도로 폴백한다.
         */
        fun seaLevelAltitude(loc: Location): Double? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                loc.hasMslAltitude()
            ) {
                return loc.mslAltitudeMeters
            }
            return if (loc.hasAltitude()) loc.altitude else null
        }

        fun formatLocation(loc: Location): String {
            // 위젯에는 고도만 표시한다 (위도/경도는 GPX 파일에만 기록됨)
            val alt = seaLevelAltitude(loc)?.let {
                String.format(Locale.US, "%.0f m", it)
            } ?: "-"
            return "고도 $alt"
        }

        fun saveLocationText(context: Context, text: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LOC_TEXT, text)
                .apply()
        }

        private fun loadLocationText(context: Context): String {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LOC_TEXT, "") ?: ""
        }

        fun refreshAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, GpsToggleWidget::class.java)
            )
            for (id in ids) {
                updateWidget(context, manager, id)
            }
        }

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: Int
        ) {
            val tracking = isTracking(context)
            val views = RemoteViews(context.packageName, R.layout.widget_gps_toggle)

            // ON/OFF에 따라 위성 아이콘을 교체 (활성 파랑+렌즈발광 / 비활성 회색)
            views.setInt(R.id.iv_gps_icon, "setImageAlpha", 255)
            if (tracking) {
                views.setImageViewResource(R.id.iv_gps_icon, R.drawable.ic_sat_on)
                views.setTextViewText(R.id.tv_location, loadLocationText(context))
            } else {
                views.setImageViewResource(R.id.iv_gps_icon, R.drawable.ic_sat_off)
                views.setTextViewText(R.id.tv_location, "")
            }

            // 모든 클릭은 브로드캐스트로 받아 클릭 횟수로 동작을 구분한다
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                widgetId,
                Intent(context, GpsToggleWidget::class.java).apply {
                    action = ACTION_TOGGLE_TRACKING
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            appWidgetManager.updateAppWidget(widgetId, views)
        }

        private fun isGpsEnabled(context: Context): Boolean {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }
    }
}
