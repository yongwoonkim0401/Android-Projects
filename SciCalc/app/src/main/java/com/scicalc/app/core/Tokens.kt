package com.scicalc.app.core

/**
 * 토큰 종류. enum 대신 Int 상수를 쓴다.
 * 토큰 배열이 `IntArray` 로 유지되어 객체 헤더도, 박싱도, GC 추적 대상도 생기지 않는다.
 * (enum 배열이면 토큰 1개당 참조 8B + 객체가 따라붙는다.)
 */
object Tok {
    const val END = 0

    // 피연산자
    const val NUM = 1
    const val PI = 2
    const val EULER = 3
    const val ANS = 4
    const val MEM = 5

    // 구분자
    const val LPAREN = 6
    const val RPAREN = 7
    const val COMMA = 8

    // 이항 연산자
    const val ADD = 10
    const val SUB = 11
    const val MUL = 12
    const val DIV = 13
    const val POW = 14

    // 전위 단항
    const val NEG = 15
    const val POS = 16

    // 후위 단항
    const val FACT = 20
    const val PERCENT = 21
    const val SQR = 22
    const val CUBE = 23
    const val RECIP = 24

    // 1인자 함수
    const val FN_SIN = 30
    const val FN_COS = 31
    const val FN_TAN = 32
    const val FN_ASIN = 33
    const val FN_ACOS = 34
    const val FN_ATAN = 35
    const val FN_SINH = 36
    const val FN_COSH = 37
    const val FN_TANH = 38
    const val FN_LN = 39
    const val FN_LOG10 = 40
    const val FN_LOG2 = 41
    const val FN_SQRT = 42
    const val FN_CBRT = 43
    const val FN_EXP = 44
    const val FN_ABS = 45

    // 2인자 함수
    const val FN_MOD = 50
    const val FN_NCR = 51
    const val FN_NPR = 52
    const val FN_LOGB = 53

    // ------------------------------------------------------------ 분류 헬퍼

    /** 값 그 자체인 토큰. */
    fun isOperand(t: Int): Boolean = t in NUM..MEM

    fun isFunction(t: Int): Boolean = t in FN_SIN..FN_LOGB

    fun isPostfix(t: Int): Boolean = t in FACT..RECIP

    fun isBinary(t: Int): Boolean = t in ADD..POW

    fun isUnary(t: Int): Boolean = t == NEG || t == POS

    fun arityOf(t: Int): Int = if (t >= FN_MOD) 2 else 1

    /**
     * 우선순위. 숫자가 클수록 강하게 결합한다.
     * 후위 연산자는 스택에 쌓지 않고 즉시 출력하므로 값이 필요 없다.
     */
    fun precedenceOf(t: Int): Int = when (t) {
        ADD, SUB -> 1
        MUL, DIV -> 2
        NEG, POS -> 3
        POW -> 4
        else -> 0
    }

    /** `^` 와 단항 연산자만 오른쪽 결합: 2^3^2 = 2^(3^2). */
    fun isRightAssociative(t: Int): Boolean = t == POW || t == NEG || t == POS

    /** 이 토큰 뒤에 값이 바로 오면 곱셈이 생략된 것으로 본다. 예) 2π, (1+2)(3), 3sin(x) */
    fun endsValue(t: Int): Boolean = isOperand(t) || t == RPAREN || isPostfix(t)

    /** 값의 시작이 될 수 있는 토큰. */
    fun startsValue(t: Int): Boolean = isOperand(t) || t == LPAREN || isFunction(t)
}

/**
 * 함수·상수를 **문자 1개**로 버퍼에 담기 위한 사설 사용 영역(U+E000~) 코드.
 *
 * "sin(" 을 그대로 넣으면 4 자를 먹고 토크나이저가 매번 글자를 비교해야 한다.
 * 대신 U+E001 한 글자만 넣으면 저장 공간은 1/4, 토큰 판별은 배열 조회 한 번으로 끝난다.
 * 화면에 그릴 때만 [labelOf] 로 사람이 읽는 이름을 붙인다.
 */
object Sym {
    @JvmField val SIN = 0xE001.toChar()
    @JvmField val COS = 0xE002.toChar()
    @JvmField val TAN = 0xE003.toChar()
    @JvmField val ASIN = 0xE004.toChar()
    @JvmField val ACOS = 0xE005.toChar()
    @JvmField val ATAN = 0xE006.toChar()
    @JvmField val SINH = 0xE007.toChar()
    @JvmField val COSH = 0xE008.toChar()
    @JvmField val TANH = 0xE009.toChar()
    @JvmField val LN = 0xE00A.toChar()
    @JvmField val LOG10 = 0xE00B.toChar()
    @JvmField val LOG2 = 0xE00C.toChar()
    @JvmField val SQRT = 0xE00D.toChar()
    @JvmField val CBRT = 0xE00E.toChar()
    @JvmField val EXP = 0xE00F.toChar()
    @JvmField val ABS = 0xE010.toChar()
    @JvmField val MOD = 0xE011.toChar()
    @JvmField val NCR = 0xE012.toChar()
    @JvmField val NPR = 0xE013.toChar()
    @JvmField val LOGB = 0xE014.toChar()

    @JvmField val PI = 0xE020.toChar()
    @JvmField val EULER = 0xE021.toChar()
    @JvmField val ANS = 0xE022.toChar()
    @JvmField val MEM = 0xE023.toChar()

    @JvmField val RECIP = 0xE030.toChar()

    const val BASE = 0xE000
    private const val TABLE_SIZE = 64

    private val tokenTable = IntArray(TABLE_SIZE) { -1 }
    private val labelTable = arrayOfNulls<String>(TABLE_SIZE)

    private fun bind(c: Char, token: Int, label: String) {
        val i = c.code - BASE
        tokenTable[i] = token
        labelTable[i] = label
    }

    init {
        bind(SIN, Tok.FN_SIN, "sin")
        bind(COS, Tok.FN_COS, "cos")
        bind(TAN, Tok.FN_TAN, "tan")
        bind(ASIN, Tok.FN_ASIN, "sin⁻¹")
        bind(ACOS, Tok.FN_ACOS, "cos⁻¹")
        bind(ATAN, Tok.FN_ATAN, "tan⁻¹")
        bind(SINH, Tok.FN_SINH, "sinh")
        bind(COSH, Tok.FN_COSH, "cosh")
        bind(TANH, Tok.FN_TANH, "tanh")
        bind(LN, Tok.FN_LN, "ln")
        bind(LOG10, Tok.FN_LOG10, "log")
        bind(LOG2, Tok.FN_LOG2, "log₂")
        bind(SQRT, Tok.FN_SQRT, "√")
        bind(CBRT, Tok.FN_CBRT, "∛")
        bind(EXP, Tok.FN_EXP, "exp")
        bind(ABS, Tok.FN_ABS, "abs")
        bind(MOD, Tok.FN_MOD, "mod")
        bind(NCR, Tok.FN_NCR, "nCr")
        bind(NPR, Tok.FN_NPR, "nPr")
        bind(LOGB, Tok.FN_LOGB, "log")
        bind(PI, Tok.PI, "π")
        bind(EULER, Tok.EULER, "e")
        bind(ANS, Tok.ANS, "Ans")
        bind(MEM, Tok.MEM, "M")
        bind(RECIP, Tok.RECIP, "⁻¹")
    }

    fun isSentinel(c: Char): Boolean {
        val i = c.code - BASE
        return i in 0 until TABLE_SIZE && tokenTable[i] >= 0
    }

    /** 사설 코드에 대응하는 토큰. 알 수 없으면 -1. */
    fun tokenOf(c: Char): Int {
        val i = c.code - BASE
        return if (i in 0 until TABLE_SIZE) tokenTable[i] else -1
    }

    /** 화면에 표시할 이름. */
    fun labelOf(c: Char): String? {
        val i = c.code - BASE
        return if (i in 0 until TABLE_SIZE) labelTable[i] else null
    }
}
