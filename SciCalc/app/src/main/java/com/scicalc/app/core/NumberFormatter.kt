package com.scicalc.app.core

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs

/**
 * 계산 결과를 사람이 읽는 문자열로 만든다.
 *
 * double 은 2 진 소수라 `0.1 + 0.2` 가 `0.30000000000000004` 로 나온다.
 * 유효자리 [SIGNIFICANT_DIGITS] 로 반올림하면 이 잡음이 정확히 제거되면서도
 * 공학 계산에 필요한 정밀도는 유지된다(double 의 실제 정밀도는 약 15.95 자리).
 */
object NumberFormatter {

    /** 표시 유효자리. */
    const val SIGNIFICANT_DIGITS = 12

    /** 이 이상이면 지수 표기. */
    private const val SCI_UPPER = 1e12

    /** 0 이 아니면서 이 미만이면 지수 표기. */
    private const val SCI_LOWER = 1e-9

    private val ROUNDING = MathContext(SIGNIFICANT_DIGITS)
    private const val SUPERSCRIPTS = "⁰¹²³⁴⁵⁶⁷⁸⁹"

    fun format(value: Double): String {
        if (value == 0.0) return "0"
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "∞" else "-∞"

        val magnitude = abs(value)
        return if (magnitude >= SCI_UPPER || magnitude < SCI_LOWER) {
            scientific(value)
        } else {
            BigDecimal(value).round(ROUNDING).stripTrailingZeros().toPlainString()
        }
    }

    /** 1.234×10⁻⁷ 형태. */
    private fun scientific(value: Double): String {
        val rounded = BigDecimal(value).round(ROUNDING).stripTrailingZeros()
        // 가수를 1 <= |m| < 10 으로 정규화
        val exponent = rounded.precision() - rounded.scale() - 1
        val mantissa = rounded.movePointLeft(exponent).stripTrailingZeros()
        val sb = StringBuilder(24)
        sb.append(mantissa.toPlainString())
        sb.append("×10")
        appendSuperscript(sb, exponent)
        return sb.toString()
    }

    private fun appendSuperscript(sb: StringBuilder, exponent: Int) {
        var e = exponent
        if (e < 0) {
            sb.append('⁻') // ⁻
            e = -e
        }
        if (e == 0) {
            sb.append(SUPERSCRIPTS[0])
            return
        }
        val start = sb.length
        while (e > 0) {
            sb.insert(start, SUPERSCRIPTS[e % 10])
            e /= 10
        }
    }

    /**
     * 계산 결과를 다시 수식 입력에 넣을 때 쓰는 형태(지수 표기를 `E` 로).
     * 화면 표기(×10ⁿ)는 토크나이저가 읽지 못하므로 별도로 만든다.
     */
    fun toInputText(value: Double): String {
        if (value == 0.0) return "0"
        val magnitude = abs(value)
        val rounded = BigDecimal(value).round(ROUNDING).stripTrailingZeros()
        return if (magnitude >= SCI_UPPER || magnitude < SCI_LOWER) {
            val exponent = rounded.precision() - rounded.scale() - 1
            rounded.movePointLeft(exponent).stripTrailingZeros().toPlainString() + "E" + exponent
        } else {
            rounded.toPlainString()
        }
    }
}
