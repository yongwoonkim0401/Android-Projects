package com.scicalc.app.ui

import com.scicalc.app.core.GapBuffer
import com.scicalc.app.core.Sym

/**
 * 화면에 보일 **일부 구간만** 문자열로 만드는 렌더러.
 *
 * ### 왜 전체를 그리지 않는가
 * TextView 에 5,000 자를 넣으면 매 프레임 그만큼 텍스트 레이아웃(줄바꿈 계산, 글리프 측정)을
 * 다시 한다. 안드로이드의 텍스트 레이아웃은 길이에 비례하고 상수도 크다.
 * 수천 자에서 이미 16ms 예산을 넘겨 입력이 눈에 띄게 밀린다.
 *
 * 실제로 사용자가 볼 수 있는 건 커서 주변 수십 자뿐이다.
 * 그래서 커서 주변 [WINDOW_SIZE] 자만 잘라 넘긴다.
 * 수식이 10 자든 10 만 자든 **렌더링 비용이 일정**하고, 만들어지는 문자열 길이도 상수로 묶인다.
 *
 * 출력 버퍼는 재사용되며, 결과 문자열은 프레임당 하나만 만들어진다.
 */
class ExpressionRenderer {

    private val sb = StringBuilder(WINDOW_SIZE * 4)

    /** 마지막 렌더에서 왼쪽이 잘렸는지. */
    var clippedLeft: Boolean = false
        private set

    /** 마지막 렌더에서 오른쪽이 잘렸는지. */
    var clippedRight: Boolean = false
        private set

    /** 만들어진 문자열에서 커서 기호의 인덱스. */
    var caretIndex: Int = 0
        private set

    /**
     * 커서 주변 구간을 사람이 읽는 형태로 만든다.
     * 사설 코드(U+E0xx)는 "sin", "π" 같은 이름으로, ASCII 연산자는 ×, ÷, − 로 바꾼다.
     */
    fun render(buffer: GapBuffer, showCaret: Boolean): CharSequence {
        sb.setLength(0)
        val length = buffer.length
        val cursor = buffer.cursor

        // 커서를 가운데쯤 두되, 끝에 붙어 있으면 그쪽으로 붙인다.
        var start = cursor - LEAD_BEFORE_CURSOR
        if (start < 0) start = 0
        var end = start + WINDOW_SIZE
        if (end > length) {
            end = length
            start = if (end - WINDOW_SIZE < 0) 0 else end - WINDOW_SIZE
        }

        clippedLeft = start > 0
        clippedRight = end < length

        if (clippedLeft) sb.append(ELLIPSIS)

        var i = start
        while (i < end) {
            if (showCaret && i == cursor) {
                caretIndex = sb.length
                sb.append(CARET)
            }
            appendDisplay(sb, buffer[i])
            i++
        }
        if (showCaret && cursor >= end) {
            caretIndex = sb.length
            sb.append(CARET)
        }

        if (clippedRight) sb.append(ELLIPSIS)
        return sb.toString()
    }

    /**
     * 기록용으로 앞에서 [maxChars] 자까지만 만든다.
     * 10 만 자 수식을 계산해도 기록이 쓰는 메모리는 상수로 묶인다.
     */
    fun renderPrefix(buffer: GapBuffer, maxChars: Int): String {
        val limit = if (buffer.length < maxChars) buffer.length else maxChars
        val out = StringBuilder(limit + 16)
        for (i in 0 until limit) appendDisplay(out, buffer[i])
        if (limit < buffer.length) out.append(ELLIPSIS)
        return out.toString()
    }

    private fun appendDisplay(out: StringBuilder, c: Char) {
        val label = Sym.labelOf(c)
        if (label != null) {
            out.append(label)
            return
        }
        when (c) {
            '*' -> out.append('×')
            '/' -> out.append('÷')
            '-' -> out.append('−')
            else -> out.append(c)
        }
    }

    companion object {
        /** 한 번에 그리는 최대 원본 문자 수. */
        const val WINDOW_SIZE = 96

        /** 커서 왼쪽으로 확보하는 문맥 길이. 나머지는 오른쪽 문맥이 된다. */
        const val LEAD_BEFORE_CURSOR = 72

        const val CARET = '│'   // │
        const val ELLIPSIS = '…' // …
    }
}
