package com.scicalc.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class ExpressionEngineTest {

    private val engine = ExpressionEngine()
    private val context = EvalContext()

    private fun eval(expression: String, autoClose: Boolean = false): Double {
        val buffer = GapBuffer().apply { insert(expression) }
        val ok = engine.evaluate(buffer, context, autoClose)
        assertTrue("계산 실패: $expression (오류 ${engine.errorCode} @${engine.errorPos})", ok)
        return engine.value
    }

    private fun errorOf(expression: String, autoClose: Boolean = false): Int {
        val buffer = GapBuffer().apply { insert(expression) }
        val ok = engine.evaluate(buffer, context, autoClose)
        assertFalse("실패해야 하는데 성공함: $expression", ok)
        return engine.errorCode
    }

    // ---------------------------------------------------------------- 사칙연산

    @Test
    fun `기본 사칙연산`() {
        assertEquals(3.0, eval("1+2"), 0.0)
        assertEquals(-1.0, eval("1-2"), 0.0)
        assertEquals(12.0, eval("3*4"), 0.0)
        assertEquals(2.5, eval("5/2"), 0.0)
    }

    @Test
    fun `연산자 우선순위`() {
        assertEquals(14.0, eval("2+3*4"), 0.0)
        assertEquals(20.0, eval("(2+3)*4"), 0.0)
        assertEquals(7.0, eval("1+2*3"), 0.0)
        assertEquals(-8.0, eval("-2^3"), 0.0)      // 거듭제곱이 단항 마이너스보다 강하다
        assertEquals(512.0, eval("2^3^2"), 0.0)    // 오른쪽 결합
        assertEquals(64.0, eval("(2^3)^2"), 0.0)
    }

    @Test
    fun `단항 부호`() {
        assertEquals(-5.0, eval("-5"), 0.0)
        assertEquals(5.0, eval("--5"), 0.0)
        assertEquals(-1.0, eval("2^-0+-2"), 0.0)   // 2^0 = 1, 1 + (-2) = -1
        assertEquals(0.125, eval("2^-3"), 0.0)
    }

    @Test
    fun `곱셈 생략`() {
        assertEquals(2 * PI, eval("2" + Sym.PI), 1e-12)
        assertEquals(20.0, eval("4(2+3)"), 0.0)
        assertEquals(6.0, eval("(2)(3)"), 0.0)
        assertEquals(6.0, eval("2" + Sym.SQRT + "(9)"), 1e-12)
    }

    @Test
    fun `소수와 지수 표기`() {
        assertEquals(0.5, eval("0.5"), 0.0)
        assertEquals(0.5, eval(".5"), 0.0)
        assertEquals(1500.0, eval("1.5E3"), 0.0)
        assertEquals(2e-7, eval("2E-7"), 0.0)
        // 고속 경로가 표준 파서와 동일한 결과를 내는지
        assertEquals("0.1".toDouble(), eval("0.1"), 0.0)
        assertEquals("123456789.123456".toDouble(), eval("123456789.123456"), 0.0)
        assertEquals("1234567890123456789012".toDouble(), eval("1234567890123456789012"), 0.0)
    }

    // ---------------------------------------------------------------- 함수

    @Test
    fun `삼각함수는 각도 단위를 따른다`() {
        context.angleMode = AngleMode.DEG
        assertEquals(0.5, eval(Sym.SIN + "(30)"), 1e-12)
        assertEquals(0.0, eval(Sym.SIN + "(180)"), 0.0)   // 정확히 0
        assertEquals(-1.0, eval(Sym.COS + "(180)"), 0.0)

        context.angleMode = AngleMode.RAD
        assertEquals(1.0, eval(Sym.SIN + "(" + Sym.PI + "/2)"), 1e-12)

        context.angleMode = AngleMode.GRAD
        assertEquals(1.0, eval(Sym.SIN + "(100)"), 0.0)

        context.angleMode = AngleMode.DEG
    }

    @Test
    fun `역삼각함수와 로그`() {
        context.angleMode = AngleMode.DEG
        assertEquals(30.0, eval(Sym.ASIN + "(0.5)"), 1e-12)
        assertEquals(2.0, eval(Sym.LOG10 + "(100)"), 1e-12)
        assertEquals(1.0, eval(Sym.LN + "(" + Sym.EULER + ")"), 1e-12)
        assertEquals(10.0, eval(Sym.LOG2 + "(1024)"), 1e-12)
        assertEquals(3.0, eval(Sym.CBRT + "(27)"), 1e-12)
        assertEquals(3.0, eval(Sym.LOGB + "(2,8)"), 1e-12)
    }

    @Test
    fun `후위 연산자`() {
        assertEquals(120.0, eval("5!"), 0.0)
        assertEquals(0.5, eval("50%"), 0.0)
        assertEquals(25.0, eval("5²"), 0.0)
        assertEquals(27.0, eval("3³"), 0.0)
        assertEquals(0.25, eval("4" + Sym.RECIP), 0.0)
        assertEquals(-120.0, eval("-5!"), 0.0)  // -(5!)
    }

    @Test
    fun `조합과 순열과 나머지`() {
        assertEquals(10.0, eval(Sym.NCR + "(5,2)"), 0.0)
        assertEquals(20.0, eval(Sym.NPR + "(5,2)"), 0.0)
        assertEquals(1.0, eval(Sym.MOD + "(10,3)"), 0.0)
    }

    @Test
    fun `Ans 와 메모리를 참조한다`() {
        context.ans = 42.0
        context.memory = 8.0
        assertEquals(50.0, eval(Sym.ANS.toString() + "+" + Sym.MEM), 0.0)
    }

    // ---------------------------------------------------------------- 오류

    @Test
    fun `오류를 예외 없이 코드로 돌려준다`() {
        assertEquals(Err.DIV_ZERO, errorOf("1/0"))
        assertEquals(Err.DOMAIN, errorOf(Sym.SQRT + "(-1)"))
        assertEquals(Err.DOMAIN, errorOf(Sym.LN + "(0)"))
        assertEquals(Err.DOMAIN, errorOf(Sym.ASIN + "(2)"))
        assertEquals(Err.UNBALANCED, errorOf("(1+2"))
        assertEquals(Err.SYNTAX, errorOf("1+"))
        assertEquals(Err.SYNTAX, errorOf("1++*2"))
        assertEquals(Err.SYNTAX, errorOf("()"))
        assertEquals(Err.EMPTY, errorOf(""))
        assertEquals(Err.ARITY, errorOf(Sym.NCR + "(5,2,1)"))
        assertEquals(Err.BAD_CHAR, errorOf("1+@"))
        assertEquals(Err.OVERFLOW, errorOf("9^9^9"))
    }

    @Test
    fun `DEG 에서 tan 90 은 정의역 오류`() {
        context.angleMode = AngleMode.DEG
        assertEquals(Err.DOMAIN, errorOf(Sym.TAN + "(90)"))
    }

    @Test
    fun `미완성 괄호를 자동으로 닫는다`() {
        assertEquals(0.5, eval(Sym.SIN + "(30", autoClose = true), 1e-12)
        assertEquals(6.0, eval("((1+2)*2", autoClose = true), 0.0)
    }

    // ---------------------------------------------------------------- 한계

    @Test
    fun `깊은 중첩에서도 StackOverflow 가 나지 않는다`() {
        val depth = 5_000
        val expression = buildString {
            repeat(depth) { append('(') }
            append('7')
            repeat(depth) { append(')') }
        }
        assertEquals(7.0, eval(expression), 0.0)
    }

    @Test
    fun `한계를 넘는 중첩은 크래시 대신 오류로 끝난다`() {
        val depth = ShuntingYard.MAX_DEPTH + 100
        val expression = buildString {
            repeat(depth) { append('(') }
            append('7')
            repeat(depth) { append(')') }
        }
        assertEquals(Err.TOO_COMPLEX, errorOf(expression))
    }

    @Test
    fun `아주 긴 수식도 정확히 계산한다`() {
        // 입력 상한(10만 자) 안에서 가능한 가장 긴 축에 속하는 수식
        val terms = 40_000
        val expression = buildString {
            append('0')
            repeat(terms) { append("+1") }
        }
        assertEquals(terms.toDouble(), eval(expression), 0.0)
    }

    @Test
    fun `같은 엔진을 반복 사용해도 결과가 흔들리지 않는다`() {
        // 내부 배열을 재사용하므로 이전 계산의 잔재가 남지 않는지 확인한다.
        repeat(100) {
            assertEquals(3.0, eval("1+2"), 0.0)
            assertEquals(Err.DIV_ZERO, errorOf("1/0"))
            assertEquals(10.0, eval("(2+3)*2"), 0.0)
        }
    }

    @Test
    fun `긴 수식을 반복 계산해도 토큰열이 누적되지 않는다`() {
        val expression = buildString {
            append('1')
            repeat(2_000) { append("+1") }
        }
        eval(expression)
        val tokensAfterFirst = engine.tokenCount
        val rpnAfterFirst = engine.rpnLength
        repeat(20) { assertEquals(2_001.0, eval(expression), 0.0) }
        // clear() 가 size 만 되돌리므로 재사용해도 토큰 수가 그대로여야 한다.
        assertEquals(tokensAfterFirst, engine.tokenCount)
        assertEquals(rpnAfterFirst, engine.rpnLength)
    }
}
