package com.scicalc.app.core

/**
 * 고정 크기 원형 버퍼로 만든 계산 기록.
 *
 * 리스트에 계속 쌓으면 앱을 오래 켜둘수록 메모리가 단조 증가한다.
 * 여기서는 슬롯 [capacity] 개를 미리 잡아두고 가장 오래된 것을 덮어쓴다.
 * 저장하는 수식도 [MAX_STORED_CHARS] 로 잘라, 10 만 자 수식을 넣어도
 * 기록이 차지하는 메모리는 상수로 묶인다.
 */
class HistoryRing(val capacity: Int = 30) {

    private val expressions = arrayOfNulls<String>(capacity)
    private val results = arrayOfNulls<String>(capacity)

    /** 표시 문자열과 별개로 원본 값을 보관한다. 기록을 다시 입력에 넣을 때 정밀도를 잃지 않는다. */
    private val values = DoubleArray(capacity)
    private var head = 0

    var size: Int = 0
        private set

    fun add(expression: String, result: String, value: Double) {
        expressions[head] = truncate(expression)
        results[head] = result
        values[head] = value
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    /** [index] 0 이 가장 최근. */
    fun expressionAt(index: Int): String = expressions[slot(index)] ?: ""

    fun resultAt(index: Int): String = results[slot(index)] ?: ""

    fun valueAt(index: Int): Double = values[slot(index)]

    fun clear() {
        for (i in 0 until capacity) {
            expressions[i] = null
            results[i] = null
            values[i] = 0.0
        }
        head = 0
        size = 0
    }

    private fun slot(index: Int): Int = ((head - 1 - index) % capacity + capacity) % capacity

    private fun truncate(s: String): String =
        if (s.length <= MAX_STORED_CHARS) s else s.substring(0, MAX_STORED_CHARS) + "…"

    companion object {
        const val MAX_STORED_CHARS = 120
    }
}
