package com.scicalc.app.core

/**
 * `IntArray` 를 직접 쓰는 스택.
 *
 * `ArrayDeque<Int>` 나 `Stack<Integer>` 를 쓰면 push 마다 Integer 박싱 객체가 생긴다.
 * 토큰 1만 개짜리 수식이면 push/pop 이 수만 번 일어나므로 그만큼 쓰레기가 쌓이고,
 * 미리보기 계산이 타이핑마다 돌면 곧바로 GC 압력이 된다. 여기서는 객체가 0개다.
 *
 * [clear] 는 배열을 버리지 않고 `size` 만 0 으로 되돌린다.
 * 그래서 한 번 커진 스택은 다음 계산에서 **재할당 없이** 재사용된다.
 */
class IntStack(initialCapacity: Int = 32) {
    private var data = IntArray(initialCapacity)

    @JvmField
    var size: Int = 0

    val isEmpty: Boolean get() = size == 0
    val isNotEmpty: Boolean get() = size > 0
    val capacity: Int get() = data.size

    fun push(v: Int) {
        if (size == data.size) grow()
        data[size++] = v
    }

    fun pop(): Int = data[--size]

    fun peek(): Int = data[size - 1]

    /** 위에서 [depth] 번째 원소(0 = 맨 위). */
    fun peekAt(depth: Int): Int = data[size - 1 - depth]

    fun replaceTop(v: Int) {
        data[size - 1] = v
    }

    fun incrementTop() {
        data[size - 1]++
    }

    fun clear() {
        size = 0
    }

    private fun grow() {
        data = data.copyOf(data.size + (data.size shr 1) + 8)
    }
}

/**
 * `DoubleArray` 스택. RPN 평가의 피연산자 스택으로 쓴다.
 * 역시 박싱이 없고, 재귀 호출 대신 이 스택 깊이로 중첩을 표현하므로
 * 괄호를 아무리 겹쳐도 JVM 콜스택은 한 칸도 더 쌓이지 않는다(StackOverflowError 불가).
 */
class DoubleStack(initialCapacity: Int = 32) {
    private var data = DoubleArray(initialCapacity)

    @JvmField
    var size: Int = 0

    val isEmpty: Boolean get() = size == 0
    val capacity: Int get() = data.size

    fun push(v: Double) {
        if (size == data.size) grow()
        data[size++] = v
    }

    fun pop(): Double = data[--size]

    fun peek(): Double = data[size - 1]

    fun replaceTop(v: Double) {
        data[size - 1] = v
    }

    fun clear() {
        size = 0
    }

    private fun grow() {
        data = data.copyOf(data.size + (data.size shr 1) + 8)
    }
}
