package com.scicalc.app.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 공학용 함수 모음. 모두 순수 함수이며 객체를 만들지 않는다.
 * 정의역을 벗어나면 [Double.NaN] 을 돌려주고, 판정은 평가기가 한다.
 */
object MathFunctions {

    // ------------------------------------------------------------ 각도 변환

    fun toRadians(value: Double, mode: AngleMode): Double = when (mode) {
        AngleMode.DEG -> value * (PI / 180.0)
        AngleMode.RAD -> value
        AngleMode.GRAD -> value * (PI / 200.0)
    }

    fun fromRadians(radians: Double, mode: AngleMode): Double = when (mode) {
        AngleMode.DEG -> radians * (180.0 / PI)
        AngleMode.RAD -> radians
        AngleMode.GRAD -> radians * (200.0 / PI)
    }

    /**
     * DEG/GRAD 에서 사분면 경계값을 정확히 맞춘다.
     * `sin(180°)` 이 1.2e-16 이 아니라 0 이 나오게 하는 처리로, 공학용 계산기의 기본 동작이다.
     * @return 특수값이면 그 값, 아니면 [Double.NaN] (일반 경로로 계산하라는 뜻)
     */
    private fun quadrantExact(value: Double, mode: AngleMode, kind: Int): Double {
        if (mode == AngleMode.RAD) return Double.NaN
        val perQuadrant = if (mode == AngleMode.DEG) 90.0 else 100.0
        val q = value / perQuadrant
        if (q != floor(q) || abs(q) > 1e15) return Double.NaN
        val n = ((q.toLong() % 4) + 4) % 4  // 0,1,2,3
        return when (kind) {
            KIND_SIN -> when (n) {
                0L -> 0.0
                1L -> 1.0
                2L -> 0.0
                else -> -1.0
            }
            else -> when (n) { // KIND_COS
                0L -> 1.0
                1L -> 0.0
                2L -> -1.0
                else -> 0.0
            }
        }
    }

    fun sinOf(value: Double, mode: AngleMode): Double {
        val exact = quadrantExact(value, mode, KIND_SIN)
        if (!exact.isNaN()) return exact
        return sin(toRadians(value, mode))
    }

    fun cosOf(value: Double, mode: AngleMode): Double {
        val exact = quadrantExact(value, mode, KIND_COS)
        if (!exact.isNaN()) return exact
        return kotlin.math.cos(toRadians(value, mode))
    }

    fun tanOf(value: Double, mode: AngleMode): Double {
        if (mode != AngleMode.RAD) {
            val perQuadrant = if (mode == AngleMode.DEG) 90.0 else 100.0
            val q = value / perQuadrant
            if (q == floor(q) && abs(q) <= 1e15) {
                val n = ((q.toLong() % 4) + 4) % 4
                // 90°, 270° 은 발산 -> NaN 으로 알려 정의역 오류 처리
                return if (n == 1L || n == 3L) Double.NaN else 0.0
            }
        }
        return kotlin.math.tan(toRadians(value, mode))
    }

    fun asinOf(value: Double, mode: AngleMode): Double =
        if (value < -1.0 || value > 1.0) Double.NaN
        else fromRadians(kotlin.math.asin(value), mode)

    fun acosOf(value: Double, mode: AngleMode): Double =
        if (value < -1.0 || value > 1.0) Double.NaN
        else fromRadians(kotlin.math.acos(value), mode)

    fun atanOf(value: Double, mode: AngleMode): Double =
        fromRadians(kotlin.math.atan(value), mode)

    // ------------------------------------------------------------ 계승/조합

    /**
     * 계승. 0 이상 정수는 곱셈 루프로, 그 밖의 실수는 감마 함수로 확장해 계산한다.
     * 음의 정수는 정의되지 않는다.
     */
    fun factorial(x: Double): Double {
        if (x == floor(x) && !x.isInfinite()) {
            if (x < 0) return Double.NaN
            if (x > 170.0) return Double.POSITIVE_INFINITY // double 표현 한계
            var result = 1.0
            var i = 2
            val n = x.toInt()
            while (i <= n) {
                result *= i
                i++
            }
            return result
        }
        return gamma(x + 1.0)
    }

    /** 란초스(Lanczos) 근사 감마 함수. g=7, n=9. 상대 오차 ~1e-15. */
    fun gamma(x: Double): Double {
        if (x < 0.5) {
            // 반사 공식: Γ(x)Γ(1-x) = π / sin(πx)
            val denom = sin(PI * x) * gamma(1.0 - x)
            return if (denom == 0.0) Double.NaN else PI / denom
        }
        val z = x - 1.0
        var a = LANCZOS[0]
        val t = z + 7.5
        for (i in 1 until LANCZOS.size) {
            a += LANCZOS[i] / (z + i)
        }
        return sqrt(2 * PI) * t.pow(z + 0.5) * exp(-t) * a
    }

    /** 조합 nCr. n, r 은 0 이상의 정수여야 한다. */
    fun combinations(n: Double, r: Double): Double {
        if (n != floor(n) || r != floor(r)) return Double.NaN
        if (n < 0 || r < 0 || r > n) return Double.NaN
        val rr = kotlin.math.min(r, n - r)
        var result = 1.0
        var i = 0.0
        while (i < rr) {
            result = result * (n - i) / (i + 1.0)
            if (result.isInfinite()) return Double.POSITIVE_INFINITY
            i += 1.0
        }
        return kotlin.math.round(result)
    }

    /** 순열 nPr. */
    fun permutations(n: Double, r: Double): Double {
        if (n != floor(n) || r != floor(r)) return Double.NaN
        if (n < 0 || r < 0 || r > n) return Double.NaN
        var result = 1.0
        var i = 0.0
        while (i < r) {
            result *= (n - i)
            if (result.isInfinite()) return Double.POSITIVE_INFINITY
            i += 1.0
        }
        return result
    }

    // ------------------------------------------------------------ 기타

    /** 밑이 [base] 인 로그. */
    fun logBase(base: Double, x: Double): Double {
        if (base <= 0.0 || base == 1.0 || x <= 0.0) return Double.NaN
        return ln(x) / ln(base)
    }

    /** 세제곱근. 음수도 처리한다(`pow(1/3)` 은 음수에서 NaN). */
    fun cbrtOf(x: Double): Double = Math.cbrt(x)

    private const val KIND_SIN = 0
    private const val KIND_COS = 1

    private val LANCZOS = doubleArrayOf(
        0.99999999999980993,
        676.5203681218851,
        -1259.1392167224028,
        771.32342877765313,
        -176.61502916214059,
        12.507343278686905,
        -0.13857109526572012,
        9.9843695780195716e-6,
        1.5056327351493116e-7
    )
}
