package com.scicalc.app.core

import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 후위 표기 프로그램을 **스택 하나로** 계산한다.
 *
 * 루프 한 번, 재귀 0 회. 중첩 깊이는 전부 [DoubleStack] 의 깊이로 표현되므로
 * 괄호가 1만 겹이든 10만 겹이든 JVM 콜스택은 이 함수 한 프레임뿐이다.
 * 스택이 한계([MAX_STACK])를 넘으면 OOM 대신 "너무 복잡함" 오류로 정상 종료한다.
 *
 * 계산 도중 어떤 객체도 만들지 않는다. 예외도 던지지 않는다.
 */
class RpnEvaluator {

    var errorCode: Int = Err.NONE
        private set

    var errorPos: Int = -1
        private set

    /** 마지막 성공 계산 결과. */
    var result: Double = 0.0
        private set

    private val stack = DoubleStack(64)

    fun evaluate(program: RpnProgram, ctx: EvalContext): Boolean {
        errorCode = Err.NONE
        errorPos = -1
        stack.clear()

        var k = 0
        while (k < program.size) {
            val op = program.op[k]
            val pos = program.pos[k]

            when {
                // ── 피연산자 ──────────────────────────────────────────────
                op == Tok.NUM -> if (!push(program.num[k], pos)) return false
                op == Tok.PI -> if (!push(PI, pos)) return false
                op == Tok.EULER -> if (!push(E, pos)) return false
                op == Tok.ANS -> if (!push(ctx.ans, pos)) return false
                op == Tok.MEM -> if (!push(ctx.memory, pos)) return false

                // ── 이항 연산자 ───────────────────────────────────────────
                Tok.isBinary(op) -> {
                    if (stack.size < 2) return fail(Err.SYNTAX, pos)
                    val b = stack.pop()
                    val a = stack.peek()
                    val v = when (op) {
                        Tok.ADD -> a + b
                        Tok.SUB -> a - b
                        Tok.MUL -> a * b
                        Tok.DIV -> {
                            if (b == 0.0) return fail(Err.DIV_ZERO, pos)
                            a / b
                        }
                        else -> a.pow(b) // Tok.POW
                    }
                    if (!finite(v, pos)) return false
                    stack.replaceTop(v)
                }

                // ── 전위 단항 ─────────────────────────────────────────────
                op == Tok.NEG -> {
                    if (stack.isEmpty) return fail(Err.SYNTAX, pos)
                    stack.replaceTop(-stack.peek())
                }
                op == Tok.POS -> {
                    if (stack.isEmpty) return fail(Err.SYNTAX, pos)
                }

                // ── 후위 단항 ─────────────────────────────────────────────
                Tok.isPostfix(op) -> {
                    if (stack.isEmpty) return fail(Err.SYNTAX, pos)
                    val a = stack.peek()
                    val v = when (op) {
                        Tok.FACT -> MathFunctions.factorial(a)
                        Tok.PERCENT -> a / 100.0
                        Tok.SQR -> a * a
                        Tok.CUBE -> a * a * a
                        else -> { // Tok.RECIP
                            if (a == 0.0) return fail(Err.DIV_ZERO, pos)
                            1.0 / a
                        }
                    }
                    if (!finite(v, pos)) return false
                    stack.replaceTop(v)
                }

                // ── 2인자 함수 ────────────────────────────────────────────
                op >= Tok.FN_MOD -> {
                    if (stack.size < 2) return fail(Err.SYNTAX, pos)
                    val b = stack.pop()
                    val a = stack.peek()
                    val v = when (op) {
                        Tok.FN_MOD -> {
                            if (b == 0.0) return fail(Err.DIV_ZERO, pos)
                            a % b
                        }
                        Tok.FN_NCR -> MathFunctions.combinations(a, b)
                        Tok.FN_NPR -> MathFunctions.permutations(a, b)
                        else -> MathFunctions.logBase(a, b) // logb(밑, 진수)
                    }
                    if (!finite(v, pos)) return false
                    stack.replaceTop(v)
                }

                // ── 1인자 함수 ────────────────────────────────────────────
                Tok.isFunction(op) -> {
                    if (stack.isEmpty) return fail(Err.SYNTAX, pos)
                    val a = stack.peek()
                    val v = when (op) {
                        Tok.FN_SIN -> MathFunctions.sinOf(a, ctx.angleMode)
                        Tok.FN_COS -> MathFunctions.cosOf(a, ctx.angleMode)
                        Tok.FN_TAN -> MathFunctions.tanOf(a, ctx.angleMode)
                        Tok.FN_ASIN -> MathFunctions.asinOf(a, ctx.angleMode)
                        Tok.FN_ACOS -> MathFunctions.acosOf(a, ctx.angleMode)
                        Tok.FN_ATAN -> MathFunctions.atanOf(a, ctx.angleMode)
                        Tok.FN_SINH -> kotlin.math.sinh(a)
                        Tok.FN_COSH -> kotlin.math.cosh(a)
                        Tok.FN_TANH -> kotlin.math.tanh(a)
                        Tok.FN_LN -> if (a <= 0.0) Double.NaN else ln(a)
                        Tok.FN_LOG10 -> if (a <= 0.0) Double.NaN else log10(a)
                        Tok.FN_LOG2 -> if (a <= 0.0) Double.NaN else log2(a)
                        Tok.FN_SQRT -> if (a < 0.0) Double.NaN else sqrt(a)
                        Tok.FN_CBRT -> MathFunctions.cbrtOf(a)
                        Tok.FN_EXP -> exp(a)
                        else -> abs(a) // Tok.FN_ABS
                    }
                    if (!finite(v, pos)) return false
                    stack.replaceTop(v)
                }

                else -> return fail(Err.SYNTAX, pos)
            }
            k++
        }

        if (stack.size != 1) return fail(Err.SYNTAX, if (program.size > 0) program.pos[program.size - 1] else 0)
        result = stack.pop()
        return true
    }

    // ------------------------------------------------------------------ 내부

    private fun push(v: Double, pos: Int): Boolean {
        if (stack.size >= MAX_STACK) return fail(Err.TOO_COMPLEX, pos)
        stack.push(v)
        return true
    }

    /** NaN 은 정의역 오류, 무한대는 자릿수 초과로 구분해 알려준다. */
    private fun finite(v: Double, pos: Int): Boolean {
        if (v.isNaN()) return fail(Err.DOMAIN, pos)
        if (v.isInfinite()) return fail(Err.OVERFLOW, pos)
        return true
    }

    private fun fail(code: Int, pos: Int): Boolean {
        errorCode = code
        errorPos = pos
        return false
    }

    companion object {
        /**
         * 피연산자 스택 최대 깊이. 8 바이트 * 20만 = 1.6MB 상한.
         * 정상적인 수식은 수십을 넘지 않는다.
         */
        const val MAX_STACK = 200_000
    }
}
