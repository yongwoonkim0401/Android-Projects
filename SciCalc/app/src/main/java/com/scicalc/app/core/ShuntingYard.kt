package com.scicalc.app.core

/**
 * 중위 표기 토큰열을 후위 표기(RPN)로 바꾸는 다익스트라의 조차장(shunting-yard) 알고리즘.
 *
 * ### 왜 재귀 하강 파서가 아닌가
 * 재귀 하강은 괄호가 깊어질수록 JVM 콜스택이 깊어진다. 안드로이드의 기본 스레드 스택은
 * 보통 1MB 안팎이라 `((((...1...))))` 를 수천 겹 넣으면 `StackOverflowError` 로 앱이 죽는다.
 * 사용자가 붙여넣기 한 번으로 만들 수 있는 입력이다.
 *
 * 조차장 알고리즘은 중첩 깊이를 **힙에 있는 [IntStack]** 으로 표현한다.
 * 콜스택은 이 함수 한 프레임뿐이라 깊이와 무관하게 넘치지 않고,
 * 스택 배열은 필요한 만큼만 자라며 [MAX_DEPTH] 를 넘으면 크래시 대신 오류를 돌려준다.
 *
 * 처리 규칙
 * - 피연산자 -> 즉시 출력
 * - 함수 -> 연산자 스택에 push (뒤에 반드시 '(' 가 와야 함)
 * - 이항 연산자 -> 우선순위가 더 높거나 같은(왼쪽 결합일 때) 연산자를 먼저 출력한 뒤 push
 * - 후위 연산자(!, %, ², ³, ⁻¹) -> 바로 앞 피연산자에 붙으므로 즉시 출력
 * - '(' -> push, 인자 카운터 프레임 시작
 * - ',' -> '(' 를 만날 때까지 출력, 인자 수 +1
 * - ')' -> '(' 까지 출력, 함수였다면 인자 수 검사 후 함수 출력
 * - 곱셈 생략(2π, 3(4+5), (1)(2)) -> 값이 끝난 자리에 값이 시작되면 MUL 을 끼워 넣는다
 */
class ShuntingYard {

    var errorCode: Int = Err.NONE
        private set

    var errorPos: Int = -1
        private set

    /** 자동으로 닫아준 괄호 수. UI 가 "괄호를 닫았습니다"를 알릴 때 쓴다. */
    var autoClosedCount: Int = 0
        private set

    /** 연산자/함수/괄호 스택. 재사용되므로 계산마다 새로 할당되지 않는다. */
    private val ops = IntStack(64)

    /** 연산자의 원본 위치(오류 표시용). ops 와 같은 깊이로 움직인다. */
    private val opsPos = IntStack(64)

    /** 괄호 프레임마다 현재까지의 인자 개수. */
    private val argCounts = IntStack(16)

    /** 그 괄호가 함수 호출의 괄호인지 여부(1/0). */
    private val funcFrame = IntStack(16)

    /** convert() 실행 중의 출력 대상. 헬퍼들이 공유한다. */
    private var out: RpnProgram = EMPTY_OUT

    /**
     * @param autoCloseParens 참이면 닫히지 않은 '(' 를 끝에서 자동으로 닫는다.
     *   타이핑 중 미리보기에서 "sin(30" 같은 미완성 수식도 결과를 보여주기 위한 것.
     * @return 성공 여부. 실패 시 [errorCode], [errorPos] 참조.
     */
    fun convert(tokens: TokenStream, program: RpnProgram, autoCloseParens: Boolean): Boolean {
        errorCode = Err.NONE
        errorPos = -1
        autoClosedCount = 0
        ops.clear()
        opsPos.clear()
        argCounts.clear()
        funcFrame.clear()
        program.clear()
        out = program

        if (tokens.size == 0) {
            fail(Err.EMPTY, 0)
            return false
        }

        var prev = Tok.END
        var i = 0
        while (i < tokens.size) {
            var t = tokens.type[i]
            val p = tokens.pos[i]

            // ── 곱셈 생략 보정 ──────────────────────────────────────────────
            if (Tok.endsValue(prev) && Tok.startsValue(t)) {
                if (!pushOperator(Tok.MUL, p)) return false
            }

            // ── 단항 +/- 판별: 값이 와야 할 자리의 +/- 는 부호다 ───────────
            if ((t == Tok.SUB || t == Tok.ADD) && !Tok.endsValue(prev)) {
                t = if (t == Tok.SUB) Tok.NEG else Tok.POS
            }

            when {
                Tok.isOperand(t) -> {
                    out.emit(t, p, tokens.num[i])
                    markArgUsed()
                }

                Tok.isFunction(t) -> {
                    // 함수 뒤에는 반드시 '(' 가 와야 한다.
                    if (i + 1 >= tokens.size || tokens.type[i + 1] != Tok.LPAREN) {
                        fail(Err.SYNTAX, p)
                        return false
                    }
                    if (!pushRaw(t, p)) return false
                }

                t == Tok.LPAREN -> {
                    if (!pushRaw(Tok.LPAREN, p)) return false
                    val isFuncCall = ops.size >= 2 && Tok.isFunction(ops.peekAt(1))
                    argCounts.push(0)
                    funcFrame.push(if (isFuncCall) 1 else 0)
                }

                t == Tok.RPAREN -> {
                    if (!Tok.endsValue(prev) && prev != Tok.LPAREN) {
                        fail(Err.SYNTAX, p)
                        return false
                    }
                    if (!closeParen(p)) return false
                }

                t == Tok.COMMA -> {
                    if (!Tok.endsValue(prev)) {
                        fail(Err.SYNTAX, p)
                        return false
                    }
                    if (!popUntilLparen(p)) return false
                    if (funcFrame.isEmpty || funcFrame.peek() == 0 || argCounts.peek() == 0) {
                        fail(Err.SYNTAX, p)
                        return false
                    }
                    argCounts.incrementTop()
                }

                Tok.isPostfix(t) -> {
                    if (!Tok.endsValue(prev)) {
                        fail(Err.SYNTAX, p)
                        return false
                    }
                    out.emit(t, p)
                }

                Tok.isBinary(t) -> {
                    if (!Tok.endsValue(prev)) {
                        fail(Err.SYNTAX, p)
                        return false
                    }
                    if (!pushOperator(t, p)) return false
                }

                Tok.isUnary(t) -> {
                    if (!pushOperator(t, p)) return false
                }

                else -> {
                    fail(Err.SYNTAX, p)
                    return false
                }
            }

            prev = t
            i++
        }

        // ── 마무리 ────────────────────────────────────────────────────────
        if (!Tok.endsValue(prev)) {
            // "1+" 처럼 연산자로 끝난 미완성 수식
            fail(Err.SYNTAX, tokens.pos[tokens.size - 1])
            return false
        }

        while (ops.isNotEmpty) {
            if (ops.peek() == Tok.LPAREN) {
                if (!autoCloseParens) {
                    fail(Err.UNBALANCED, opsPos.peek())
                    return false
                }
                autoClosedCount++
                if (!closeParen(opsPos.peek())) return false
            } else {
                out.emit(ops.pop(), opsPos.pop())
            }
        }
        if (out.size == 0) {
            fail(Err.EMPTY, 0)
            return false
        }
        return true
    }

    // ------------------------------------------------------------------ 내부

    /** 우선순위를 따져 스택을 비운 뒤 연산자를 넣는다. */
    private fun pushOperator(t: Int, p: Int): Boolean {
        // 전위 단항(+x, -x)은 피연산자 앞에 오므로 비울 것이 없다.
        // 여기서 스택을 비우면 "2^-3" 에서 아직 오른쪽 피연산자가 없는 ^ 가 먼저 출력돼 버린다.
        if (Tok.isUnary(t)) return pushRaw(t, p)

        val prec = Tok.precedenceOf(t)
        val rightAssoc = Tok.isRightAssociative(t)
        while (ops.isNotEmpty) {
            val top = ops.peek()
            if (top == Tok.LPAREN) break
            val topPrec = if (Tok.isFunction(top)) FUNC_PRECEDENCE else Tok.precedenceOf(top)
            val shouldPop = if (rightAssoc) topPrec > prec else topPrec >= prec
            if (!shouldPop) break
            out.emit(ops.pop(), opsPos.pop())
        }
        return pushRaw(t, p)
    }

    private fun pushRaw(t: Int, p: Int): Boolean {
        if (ops.size >= MAX_DEPTH) {
            fail(Err.TOO_COMPLEX, p)
            return false
        }
        ops.push(t)
        opsPos.push(p)
        return true
    }

    /** 열린 괄호를 만날 때까지 연산자를 출력한다. */
    private fun popUntilLparen(p: Int): Boolean {
        while (ops.isNotEmpty && ops.peek() != Tok.LPAREN) {
            out.emit(ops.pop(), opsPos.pop())
        }
        if (ops.isEmpty) {
            fail(Err.UNBALANCED, p)
            return false
        }
        return true
    }

    private fun closeParen(p: Int): Boolean {
        if (!popUntilLparen(p)) return false
        val lparenPos = opsPos.peek()
        ops.pop()
        opsPos.pop()

        val argc = if (argCounts.isEmpty) 0 else argCounts.pop()
        val wasFunc = if (funcFrame.isEmpty) 0 else funcFrame.pop()

        if (wasFunc == 1) {
            val fn = ops.pop()
            val fnPos = opsPos.pop()
            if (argc != Tok.arityOf(fn)) {
                fail(if (argc == 0) Err.SYNTAX else Err.ARITY, fnPos)
                return false
            }
            out.emit(fn, fnPos)
        } else if (argc == 0) {
            fail(Err.SYNTAX, lparenPos) // 빈 괄호 "()"
            return false
        }
        markArgUsed()
        return true
    }

    /** 현재 괄호 프레임에 값이 하나라도 들어왔음을 표시한다. */
    private fun markArgUsed() {
        if (argCounts.isNotEmpty && argCounts.peek() == 0) argCounts.replaceTop(1)
    }

    private fun fail(code: Int, pos: Int) {
        errorCode = code
        errorPos = pos
    }

    companion object {
        /** 함수는 어떤 연산자보다 강하게 결합한다. */
        private const val FUNC_PRECEDENCE = 10

        /**
         * 연산자 스택 최대 깊이. 넘으면 크래시 대신 "수식이 너무 복잡합니다"로 끝낸다.
         * 원소 하나가 4 바이트라 이 깊이에서도 스택 메모리는 100KB 미만이다.
         */
        const val MAX_DEPTH = 20_000

        private val EMPTY_OUT = RpnProgram(1)
    }
}
