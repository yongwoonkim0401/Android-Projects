package com.example.gpstogglewidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager

/**
 * GPS 상태 변경(켜짐/꺼짐)을 감지하여 위젯 UI를 즉시 갱신한다.
 * android.location.PROVIDERS_CHANGED 브로드캐스트를 수신한다.
 */
class GpsStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == LocationManager.PROVIDERS_CHANGED_ACTION ||
            intent.action == "android.location.MODE_CHANGED"
        ) {
            GpsToggleWidget.refreshAllWidgets(context)
        }
    }
}
