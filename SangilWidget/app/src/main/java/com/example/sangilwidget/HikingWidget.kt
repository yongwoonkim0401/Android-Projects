package com.example.sangilwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews

class HikingWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        if (intent.action != Constants.ACTION_WIDGET_CLICK) return

        val prefs = context.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE)
        val currentState = prefs.getInt(Constants.PREF_STATE, Constants.STATE_IDLE)

        val command: String
        val newState: Int

        if (currentState == Constants.STATE_IDLE) {
            command  = Constants.CMD_START
            newState = Constants.STATE_RECORDING
        } else {
            command  = Constants.CMD_STOP
            newState = Constants.STATE_IDLE
        }

        prefs.edit().putInt(Constants.PREF_STATE, newState).apply()

        // Accessibility Service에 명령 전달
        val accIntent = Intent(Constants.ACTION_ACCESSIBILITY_CMD).apply {
            setPackage(context.packageName)
            putExtra(Constants.EXTRA_COMMAND, command)
        }
        context.sendBroadcast(accIntent)

        // 모든 위젯 UI 갱신
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, HikingWidget::class.java))
        for (id in ids) updateWidget(context, manager, id)
    }

    companion object {
        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val prefs = context.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE)
            val state = prefs.getInt(Constants.PREF_STATE, Constants.STATE_IDLE)

            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            if (state == Constants.STATE_IDLE) {
                views.setTextViewText(R.id.tv_status, "⛰ 대기 중")
                views.setTextViewText(R.id.btn_action, "등산 시작")
                views.setInt(R.id.btn_action, "setBackgroundColor", Color.parseColor("#2E7D32"))
            } else {
                views.setTextViewText(R.id.tv_status, "● 기록 중...")
                views.setTextViewText(R.id.btn_action, "등산 종료")
                views.setInt(R.id.btn_action, "setBackgroundColor", Color.parseColor("#C62828"))
            }

            val clickIntent = Intent(context, HikingWidget::class.java).apply {
                action = Constants.ACTION_WIDGET_CLICK
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, appWidgetId, clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_action, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
