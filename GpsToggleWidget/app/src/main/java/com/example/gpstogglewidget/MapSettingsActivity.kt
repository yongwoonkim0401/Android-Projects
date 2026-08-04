package com.example.gpstogglewidget

import android.os.Bundle
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 경로 표시에 사용할 지도를 선택하는 설정 화면 (위젯 트리플클릭으로 열림).
 *
 * 네이버/카카오 지도는 공식 SDK와 발급받은 API 키가 있어야 동작한다.
 * 키가 없으면 선택은 저장되지만 지도 표시 시 OpenStreetMap으로 자동 폴백된다.
 */
class MapSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_settings)

        val group = findViewById<RadioGroup>(R.id.rg_map)
        val note = findViewById<TextView>(R.id.tv_key_note)

        when (GpsToggleWidget.getMapProvider(this)) {
            "naver" -> group.check(R.id.rb_naver)
            "kakao" -> group.check(R.id.rb_kakao)
            else -> group.check(R.id.rb_osm)
        }
        updateNote(note, GpsToggleWidget.getMapProvider(this))

        group.setOnCheckedChangeListener { _, checkedId ->
            val provider = when (checkedId) {
                R.id.rb_naver -> "naver"
                R.id.rb_kakao -> "kakao"
                else -> "osm"
            }
            GpsToggleWidget.setMapProvider(this, provider)
            updateNote(note, provider)
        }

    }

    private fun updateNote(note: TextView, provider: String) {
        note.text = when (provider) {
            "naver" -> if (MapKeys.NAVER_CLIENT_ID.isBlank()) {
                "네이버 지도는 API 키(Client ID)가 필요합니다.\n" +
                    "키가 설정되지 않아 지금은 OpenStreetMap으로 표시됩니다.\n\n" +
                    "발급: ncloud.com → Maps → Web Dynamic Map\n" +
                    "발급 후 MapKeys.kt의 NAVER_CLIENT_ID에 입력하세요."
            } else {
                "네이버 지도로 경로를 표시합니다."
            }
            "kakao" -> if (MapKeys.KAKAO_JS_KEY.isBlank()) {
                "카카오맵은 API 키(JavaScript 키)가 필요합니다.\n" +
                    "키가 설정되지 않아 지금은 OpenStreetMap으로 표시됩니다.\n\n" +
                    "발급: developers.kakao.com → 내 애플리케이션 → JavaScript 키\n" +
                    "발급 후 MapKeys.kt의 KAKAO_JS_KEY에 입력하세요."
            } else {
                "카카오맵으로 경로를 표시합니다."
            }
            else -> "OpenStreetMap으로 경로를 표시합니다. API 키가 필요하지 않습니다."
        }
    }
}
