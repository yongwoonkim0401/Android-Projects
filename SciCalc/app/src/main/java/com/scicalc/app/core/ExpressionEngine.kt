package com.scicalc.app.core

/**
 * 토크나이저 -> 조차장 파서 -> RPN 평가기를 묶은 계산 파이프라인.
 *
 * 내부의 토큰 배열·스택·RPN 버퍼를 **인스턴스가 살아있는 동안 계속 재사용한다.**
 * 그래서 처음 몇 번의 계산에서 배열이 필요한 크기까지 자라고 나면
 * 이후 계산은 길이에 상관없이 **힙 할당 0 바이트**로 끝난다.
 *
 * 스레드 안전하지 않다. 한 인스턴스는 한 스레드에서만 쓴다.
 * (앱은 UI 스레드용 1개, 백그라운드 계산용 1개를 따로 갖는다.)
 */
class ExpressionEngine {

    private val tokenizer = Tokenizer()
    private val tokens = TokenStream()
    private val parser = ShuntingYard()
    private val program = RpnProgram()
    private val evaluator = RpnEvaluator()

    var errorCode: Int = Err.NONE
        private set

    /** 오류가 난 원본 문자 위치. 없으면 -1. */
    var errorPos: Int = -1
        private set

    var value: Double = 0.0
        private set

    /** 자동으로 닫아준 괄호 수. */
    var autoClosedParens: Int = 0
        private set

    /** 진단용: 마지막 파싱의 토큰 수. */
    val tokenCount: Int get() = tokens.size

    /** 진단용: 마지막 파싱의 RPN 길이. */
    val rpnLength: Int get() = program.size

    /**
     * @param autoCloseParens 미완성 괄호를 끝에서 자동으로 닫을지. 미리보기에서는 true.
     */
    fun evaluate(src: CharSource, ctx: EvalContext, autoCloseParens: Boolean = true): Boolean {
        errorCode = Err.NONE
        errorPos = -1
        autoClosedParens = 0

        if (src.length == 0) {
            errorCode = Err.EMPTY
            return false
        }

        if (!tokenizer.tokenize(src, tokens)) {
            errorCode = tokenizer.errorCode
            errorPos = tokenizer.errorPos
            return false
        }

        if (!parser.convert(tokens, program, autoCloseParens)) {
            errorCode = parser.errorCode
            errorPos = parser.errorPos
            return false
        }
        autoClosedParens = parser.autoClosedCount

        if (!evaluator.evaluate(program, ctx)) {
            errorCode = evaluator.errorCode
            errorPos = evaluator.errorPos
            return false
        }

        value = evaluator.result
        return true
    }
}
