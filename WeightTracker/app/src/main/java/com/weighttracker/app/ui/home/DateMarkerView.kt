package com.weighttracker.app.ui.home

import android.content.Context
import android.widget.TextView
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF
import com.weighttracker.app.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 터치 지점의 날짜와 몸무게를 보여주는 마커 (x = epoch day) */
class DateMarkerView(context: Context) : MarkerView(context, R.layout.view_chart_marker) {

    private val tvMarker: TextView = findViewById(R.id.tvMarker)
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    override fun refreshContent(e: Entry?, highlight: Highlight?) {
        if (e != null) {
            val date = LocalDate.ofEpochDay(Math.round(e.x).toLong()).format(fmt)
            tvMarker.text = "$date  ${"%.1f".format(e.y)} kg"
        }
        super.refreshContent(e, highlight)
    }

    override fun getOffset(): MPPointF {
        // 마커가 터치 지점보다 충분히 위쪽 중앙에 오도록 배치 (그래프와 겹침 최소화)
        return MPPointF(-(width / 2f), -height.toFloat() - 48f)
    }

    override fun getOffsetForDrawingAtPoint(posX: Float, posY: Float): MPPointF {
        // 차트 경계를 벗어나면 안쪽으로 보정 (작은 차트에서 마커 잘림 방지)
        val offset = MPPointF(getOffset().x, getOffset().y)
        val chart = chartView ?: return offset

        // 좌우 보정
        if (posX + offset.x < 0) {
            offset.x = -posX
        } else if (posX + offset.x + width > chart.width) {
            offset.x = chart.width - posX - width
        }
        // 위쪽을 벗어나면 터치 지점 아래쪽에 표시
        if (posY + offset.y < 0) {
            offset.y = 24f
        }
        return offset
    }
}
