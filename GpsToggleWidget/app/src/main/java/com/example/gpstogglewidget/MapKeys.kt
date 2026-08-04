package com.example.gpstogglewidget

/**
 * 지도 서비스 API 키.
 *
 * 네이버/카카오 지도는 각 사의 공식 SDK와 발급받은 키로만 사용할 수 있다.
 * (키 없이 타일 서버에 직접 접근하는 것은 각 사 이용약관 위반)
 * 아래 값이 비어 있으면 지도 표시 시 OpenStreetMap으로 자동 폴백된다.
 *
 * - 네이버: https://www.ncloud.com → Maps → Web Dynamic Map → Client ID
 *   (콘솔에서 Web 서비스 URL에 https://localhost 를 등록해야 동작)
 * - 카카오: https://developers.kakao.com → 내 애플리케이션 → 앱 키 → JavaScript 키
 *   (플랫폼 → Web 사이트 도메인에 https://localhost 를 등록해야 동작)
 */
object MapKeys {
    const val NAVER_CLIENT_ID = "mtc8y6x8n8"
    const val KAKAO_JS_KEY = "5fdf03fcef72bca6b01648ef2d1dddca"
}
