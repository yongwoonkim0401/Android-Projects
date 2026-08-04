package com.example.sangilwidget

object Constants {

    // ─────────────────────────────────────────────────────────────
    // 산길샘 앱 패키지명
    // 확인 방법: 폰을 PC에 연결 후 adb shell pm list packages | grep sangil
    // ─────────────────────────────────────────────────────────────
    const val SANGIL_PACKAGE = "com.sangil.sangilsam"  // ← 실제 패키지명으로 변경!

    // ─────────────────────────────────────────────────────────────
    // 산길샘 버튼 텍스트 (앱을 열어 버튼에 표시된 글자 그대로 입력)
    // MainActivity 설정 화면에서도 변경 가능
    // ─────────────────────────────────────────────────────────────
    const val DEFAULT_BTN_START = "저장 시작"
    const val DEFAULT_BTN_STOP  = "등산 끝"

    // 내부 액션 / 키
    const val ACTION_WIDGET_CLICK        = "com.example.sangilwidget.ACTION_CLICK"
    const val ACTION_ACCESSIBILITY_CMD   = "com.example.sangilwidget.ACC_CMD"
    const val EXTRA_COMMAND              = "command"

    const val CMD_START = "start"
    const val CMD_STOP  = "stop"

    // SharedPreferences
    const val PREF_NAME       = "widget_prefs"
    const val PREF_STATE      = "hiking_state"
    const val PREF_BTN_START  = "btn_start_text"
    const val PREF_BTN_STOP   = "btn_stop_text"
    const val PREF_PACKAGE    = "target_package"

    // 상태값
    const val STATE_IDLE      = 0
    const val STATE_RECORDING = 1
}
