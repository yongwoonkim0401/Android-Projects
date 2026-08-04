package com.scicalc.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class NumberFormatterTest {

    @Test
    fun `정수는 소수점 없이`() {
        assertEquals("0", NumberFormatter.format(0.0))
        assertEquals("42", NumberFormatter.format(42.0))
        assertEquals("-7", NumberFormatter.format(-7.0))
    }

    @Test
    fun `부동소수점 잡음을 제거한다`() {
        assertEquals("0.3", NumberFormatter.format(0.1 + 0.2))
        assertEquals("0.1", NumberFormatter.format(0.1))
        assertEquals("1.5", NumberFormatter.format(1.5))
    }

    @Test
    fun `큰 수와 작은 수는 지수 표기`() {
        assertEquals("1×10¹⁵", NumberFormatter.format(1e15))
        assertEquals("1.5×10⁻¹⁰", NumberFormatter.format(1.5e-10))
        assertEquals("-2×10¹³", NumberFormatter.format(-2e13))
    }

    @Test
    fun `입력용 문자열은 다시 파싱할 수 있어야 한다`() {
        val values = doubleArrayOf(0.1 + 0.2, 1e15, 1.5e-10, -12345.678, 42.0)
        val engine = ExpressionEngine()
        val context = EvalContext()
        for (v in values) {
            val text = NumberFormatter.toInputText(v)
            val buffer = GapBuffer().apply { insert(text) }
            val ok = engine.evaluate(buffer, context, autoCloseParens = false)
            assertEquals("파싱 실패: $text", true, ok)
            assertEquals(v, engine.value, kotlin.math.abs(v) * 1e-11)
        }
    }
}
