package com.example.gpstogglewidget

import android.app.Activity
import android.os.Bundle

/**
 * 수집 ON/OFF 토글만 수행하는 완전 투명 Activity (화면에 전혀 보이지 않음).
 *
 * 위젯 클릭 판정(450ms) 후에는 앱이 백그라운드 상태라 포그라운드 서비스
 * 시작이 차단되므로, 이 Activity를 아주 잠깐 거쳐 앱을 포그라운드로 만든 뒤
 * 서비스를 시작한다. 순정 Translucent 테마 + 애니메이션 제거로 화면 전환이
 * 보이지 않으며, onCreate에서 즉시 finish 한다.
 */
class ToggleActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GpsToggleWidget.toggleTracking(this)
        finish()
        // 화면 전환 애니메이션은 실행 Intent의 FLAG_ACTIVITY_NO_ANIMATION으로 이미 억제됨
    }
}
