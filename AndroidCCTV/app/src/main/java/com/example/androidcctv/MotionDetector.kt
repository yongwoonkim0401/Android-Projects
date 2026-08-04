package com.example.androidcctv

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max

/**
 * NV21 의 Y(밝기) 평면만 사용하는 가벼운 움직임 감지기.
 * 화면을 32x24 격자로 줄여 평균 밝기를 비교하므로 구형 CPU 에서도 부담이 적다.
 */
class MotionDetector {

    private val gw = 32
    private val gh = 24
    private var prev: IntArray? = null

    /** 마지막으로 계산한 변화 셀 비율(0.0 ~ 1.0) */
    @Volatile
    var lastScore: Double = 0.0
        private set

    fun reset() {
        prev = null
        lastScore = 0.0
    }

    /**
     * 카메라의 Y 평면을 그대로 읽는다(NV21 변환 전이라 JPEG 인코딩 없이도 감지가 가능하다).
     *
     * @param sensitivity 1(둔감) ~ 100(민감)
     * @return 움직임으로 판정되면 true
     */
    fun analyze(
        y: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        w: Int,
        h: Int,
        sensitivity: Int
    ): Boolean {
        val cur = downsample(y, rowStride, pixelStride, w, h)
        val old = prev
        prev = cur
        if (old == null) {
            lastScore = 0.0
            return false
        }

        val pixelThreshold = max(4.0, 45.0 - sensitivity * 0.40)
        val ratioThreshold = max(0.005, 0.10 - sensitivity * 0.0009)

        var changed = 0
        for (i in cur.indices) {
            if (abs(cur[i] - old[i]) > pixelThreshold) changed++
        }
        val ratio = changed.toDouble() / cur.size
        lastScore = ratio
        return ratio >= ratioThreshold
    }

    private fun downsample(
        y: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        w: Int,
        h: Int
    ): IntArray {
        val out = IntArray(gw * gh)
        // 셀마다 최대 4x4 지점만 표본으로 뽑아 평균을 낸다.
        val stepX = max(1, (w / gw) / 4)
        val stepY = max(1, (h / gh) / 4)

        for (gy in 0 until gh) {
            val y0 = gy * h / gh
            val y1 = ((gy + 1) * h / gh).coerceAtMost(h)
            for (gx in 0 until gw) {
                val x0 = gx * w / gw
                val x1 = ((gx + 1) * w / gw).coerceAtMost(w)
                var sum = 0
                var n = 0
                var py = y0
                while (py < y1) {
                    val base = py * rowStride
                    var px = x0
                    while (px < x1) {
                        sum += y.get(base + px * pixelStride).toInt() and 0xFF
                        n++
                        px += stepX
                    }
                    py += stepY
                }
                out[gy * gw + gx] = if (n > 0) sum / n else 0
            }
        }
        return out
    }
}
