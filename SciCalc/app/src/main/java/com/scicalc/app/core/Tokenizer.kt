package com.scicalc.app.core

/**
 * [CharSource] 를 한 번만 훑어 [TokenStream] 을 채우는 스트리밍 토크나이저.
 *
 * - 입력 전체를 String 으로 만들지 않는다. 인덱스로만 읽는다.
 * - 부분 문자열(substring)을 만들지 않는다. 숫자도 문자에서 바로 double 로 누적한다.
 * - 결과 토큰 배열은 재사용된다.
 *
 * 즉 수식 길이 n 에 대해 **추가 할당 없이 O(n)** 이다.
 */
class Tokenizer {

    var errorCode: Int = Err.NONE
        private set

    var errorPos: Int = -1
        private set

    /** 숫자 고속 경로에서 벗어난 드문 입력을 처리할 때만 쓰는 버퍼(재사용). */
    private val fallback = StringBuilder(32)

    /**
     * @return 성공 여부. 실패하면 [errorCode], [errorPos] 에 사유가 담긴다.
     */
    fun tokenize(src: CharSource, out: TokenStream): Boolean {
        errorCode = Err.NONE
        errorPos = -1
        out.clear()

        val n = src.length
        var i = 0
        while (i < n) {
            val c = src[i]
            val code = c.code

            // 공백은 건너뛴다(키패드는 넣지 않지만 붙여넣기 대비).
            if (code == 0x20) {
                i++
                continue
            }

            if (isDigit(c) || code == 0x2E /* . */) {
                i = scanNumber(src, i, out)
                if (errorCode != Err.NONE) return false
                continue
            }

            val t = tokenForSymbol(c)
            if (t < 0) {
                fail(Err.BAD_CHAR, i)
                return false
            }
            out.add(t, i)
            i++
        }
        return true
    }

    // ------------------------------------------------------------------ 기호

    private fun tokenForSymbol(c: Char): Int = when (c.code) {
        0x2B -> Tok.ADD          // +
        0x2D -> Tok.SUB          // -
        0x2212 -> Tok.SUB        // − (붙여넣기 대비)
        0x2A -> Tok.MUL          // *
        0xD7 -> Tok.MUL          // ×
        0x2F -> Tok.DIV          // /
        0xF7 -> Tok.DIV          // ÷
        0x5E -> Tok.POW          // ^
        0x28 -> Tok.LPAREN       // (
        0x29 -> Tok.RPAREN       // )
        0x2C -> Tok.COMMA        // ,
        0x21 -> Tok.FACT         // !
        0x25 -> Tok.PERCENT      // %
        0xB2 -> Tok.SQR          // ²
        0xB3 -> Tok.CUBE         // ³
        0x3C0 -> Tok.PI          // π
        else -> Sym.tokenOf(c)   // 사설 영역(함수/상수), 없으면 -1
    }

    // ------------------------------------------------------------------ 숫자

    /**
     * [start] 부터 숫자 리터럴을 읽어 토큰 하나를 추가하고, 다음 인덱스를 돌려준다.
     *
     * 정수부/소수부를 Long 가수(mantissa)로 모으고 10 의 거듭제곱을 한 번만 곱한다.
     * 가수가 2^53 이하이고 지수가 ±22 이내이면 부동소수점 반올림이 한 번뿐이라
     * `Double.parseDouble` 과 **비트 단위로 같은 결과**가 나온다(고속 경로).
     * 그 범위를 벗어나는 아주 긴 숫자만 문자열로 만들어 표준 파서에 넘긴다.
     */
    private fun scanNumber(src: CharSource, start: Int, out: TokenStream): Int {
        val n = src.length
        var i = start
        var mantissa = 0L
        var digits = 0
        var fracDigits = 0
        var seenDot = false
        var exact = true
        var anyDigit = false

        while (i < n) {
            val c = src[i]
            if (isDigit(c)) {
                anyDigit = true
                if (digits < MAX_EXACT_DIGITS) {
                    mantissa = mantissa * 10 + (c.code - 0x30)
                    digits++
                    if (seenDot) fracDigits++
                } else {
                    // 유효자리를 넘어선 자릿수: 고속 경로 포기
                    exact = false
                    if (!seenDot) fracDigits--
                }
                i++
            } else if (c.code == 0x2E && !seenDot) {
                seenDot = true
                i++
            } else {
                break
            }
        }

        if (!anyDigit) {
            // "." 하나만 있는 경우
            fail(Err.SYNTAX, start)
            return i
        }

        // 지수부: 1.5E3, 2E-7 …
        var expPart = 0
        var expEnd = i
        if (i < n && (src[i].code == 0x45 || src[i].code == 0x65)) { // E, e
            var j = i + 1
            var negExp = false
            if (j < n && (src[j].code == 0x2B || src[j].code == 0x2D || src[j].code == 0x2212)) {
                negExp = src[j].code != 0x2B
                j++
            }
            var expDigits = 0
            var e = 0
            while (j < n && isDigit(src[j]) && expDigits < 6) {
                e = e * 10 + (src[j].code - 0x30)
                expDigits++
                j++
            }
            if (expDigits > 0) {
                expPart = if (negExp) -e else e
                expEnd = j
                i = j
            }
            // 지수 자릿수가 없으면 'E' 는 숫자에 속하지 않는다 -> 그대로 두고 BAD_CHAR 로 잡힌다.
        }

        val scale = expPart - fracDigits
        val value: Double
        if (exact && mantissa <= MAX_EXACT_MANTISSA && scale >= -MAX_POW10 && scale <= MAX_POW10) {
            val m = mantissa.toDouble()
            value = if (scale >= 0) m * POW10[scale] else m / POW10[-scale]
        } else {
            value = parseSlow(src, start, expEnd)
            if (value.isNaN()) {
                fail(Err.SYNTAX, start)
                return expEnd
            }
        }

        out.add(Tok.NUM, start, value)
        return i
    }

    /** 고속 경로를 벗어난 긴 리터럴만 문자열로 만들어 표준 파서에 넘긴다(호출 빈도 극히 낮음). */
    private fun parseSlow(src: CharSource, from: Int, to: Int): Double {
        fallback.setLength(0)
        for (k in from until to) {
            val c = src[k]
            if (c.code == 0x2212) fallback.append('-') else fallback.append(c)
        }
        return try {
            fallback.toString().toDouble()
        } catch (e: NumberFormatException) {
            Double.NaN
        }
    }

    private fun isDigit(c: Char): Boolean = c.code in 0x30..0x39

    private fun fail(code: Int, pos: Int) {
        errorCode = code
        errorPos = pos
    }

    companion object {
        /** Long 가수에 안전하게 담을 수 있는 십진 자릿수. */
        private const val MAX_EXACT_DIGITS = 17

        /** 2^53. 이 이하의 정수는 double 로 정확히 표현된다. */
        private const val MAX_EXACT_MANTISSA = 9_007_199_254_740_992L

        /** 10^22 까지는 double 로 정확히 표현된다. */
        private const val MAX_POW10 = 22

        private val POW10 = DoubleArray(MAX_POW10 + 1).also {
            var v = 1.0
            for (k in 0..MAX_POW10) {
                it[k] = v
                v *= 10.0
            }
        }
    }
}
