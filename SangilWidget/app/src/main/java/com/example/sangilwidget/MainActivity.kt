package com.example.sangilwidget

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE)

        val etPackage   = findViewById<EditText>(R.id.et_package)
        val etBtnStart  = findViewById<EditText>(R.id.et_btn_start)
        val etBtnStop   = findViewById<EditText>(R.id.et_btn_stop)
        val tvAccStatus = findViewById<TextView>(R.id.tv_acc_status)
        val btnSave     = findViewById<Button>(R.id.btn_save)
        val btnAcc      = findViewById<Button>(R.id.btn_open_accessibility)

        // 저장된 값 불러오기
        etPackage.setText(prefs.getString(Constants.PREF_PACKAGE, Constants.SANGIL_PACKAGE))
        etBtnStart.setText(prefs.getString(Constants.PREF_BTN_START, Constants.DEFAULT_BTN_START))
        etBtnStop.setText(prefs.getString(Constants.PREF_BTN_STOP, Constants.DEFAULT_BTN_STOP))

        // 접근성 서비스 상태 표시
        updateAccessibilityStatus(tvAccStatus)

        btnSave.setOnClickListener {
            prefs.edit()
                .putString(Constants.PREF_PACKAGE,   etPackage.text.toString().trim())
                .putString(Constants.PREF_BTN_START, etBtnStart.text.toString().trim())
                .putString(Constants.PREF_BTN_STOP,  etBtnStop.text.toString().trim())
                .apply()
            Toast.makeText(this, "저장되었습니다.", Toast.LENGTH_SHORT).show()
        }

        btnAcc.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        val tvAccStatus = findViewById<TextView>(R.id.tv_acc_status)
        updateAccessibilityStatus(tvAccStatus)
    }

    private fun updateAccessibilityStatus(tv: TextView) {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).any { it.resolveInfo.serviceInfo.packageName == packageName }

        if (enabled) {
            tv.text = "✅ 접근성 서비스: 활성화됨"
            tv.setTextColor(getColor(android.R.color.holo_green_dark))
        } else {
            tv.text = "❌ 접근성 서비스: 비활성화 (아래 버튼으로 활성화 필요)"
            tv.setTextColor(getColor(android.R.color.holo_red_dark))
        }
    }
}
