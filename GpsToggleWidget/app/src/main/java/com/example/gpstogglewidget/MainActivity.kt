package com.example.gpstogglewidget

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 일반 앱과 동일한 방식으로 위치 권한을 요청하는 메인 화면.
 * 1) "위치 권한 허용" 버튼 → 표준 Android 권한 다이얼로그
 * 2) Android 11+ 백그라운드(항상 허용) 권한은 정확한 위치 허용 후
 *    별도 버튼으로 요청 (시스템이 설정 화면으로 안내)
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_FINE = 100
        private const val REQ_BACKGROUND = 101
    }

    private lateinit var tvStatus: TextView
    private lateinit var btnFine: Button
    private lateinit var btnBackground: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tv_permission_status)
        btnFine = findViewById(R.id.btn_request_fine)
        btnBackground = findViewById(R.id.btn_request_background)

        btnFine.setOnClickListener {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQ_FINE
            )
        }

        btnBackground.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    REQ_BACKGROUND
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateStatus()
        // 권한 상태가 바뀌었으니 위젯도 갱신
        GpsToggleWidget.refreshAllWidgets(this)
    }

    private fun updateStatus() {
        val fineGranted = isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        val backgroundGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            true
        }

        val status = buildString {
            append("정확한 위치: ")
            append(if (fineGranted) "허용됨 ✓" else "허용 안 됨")
            append("\n항상 허용(백그라운드): ")
            append(if (backgroundGranted) "허용됨 ✓" else "허용 안 됨")
            append("\n\n")
            append(
                when {
                    fineGranted && backgroundGranted ->
                        "모든 권한이 허용되었습니다.\n위젯에서 위도/경도/고도가 표시됩니다."
                    fineGranted ->
                        "위젯은 백그라운드에서 동작하므로\n\"항상 허용\"까지 설정해야 좌표가 표시됩니다."
                    else ->
                        "위젯에 좌표를 표시하려면\n위치 권한을 허용해 주세요."
                }
            )
        }
        tvStatus.text = status

        btnFine.isEnabled = !fineGranted
        btnBackground.isEnabled = fineGranted && !backgroundGranted
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
