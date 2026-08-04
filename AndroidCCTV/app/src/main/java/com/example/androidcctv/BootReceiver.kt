package com.example.androidcctv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat

/** 재부팅 후 이전에 켜져 있었다면 CCTV 를 자동으로 다시 시작한다. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return

        val prefs = Prefs(context)
        if (!prefs.autoStart || !prefs.wasRunning) return

        val granted = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w("CctvBoot", "카메라 권한이 없어 자동 시작을 건너뜁니다")
            return
        }

        try {
            CctvService.start(context)
        } catch (t: Throwable) {
            Log.e("CctvBoot", "자동 시작 실패", t)
        }
    }
}
