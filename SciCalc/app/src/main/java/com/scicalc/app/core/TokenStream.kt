package com.scicalc.app.core

/**
 * 토큰열을 담는 구조체 배열(SoA, structure of arrays).
 *
 * `List<Token>` 이면 토큰 하나당 객체 하나 + 리스트 슬롯이 생긴다.
 * 토큰 3만 개면 객체 3만 개가 힙에 뿌려지고, 계산이 끝나면 전부 쓰레기가 된다.
 * 여기서는 병렬 배열 3개뿐이고, [clear] 가 배열을 유지하므로
 * **두 번째 계산부터는 할당이 0** 이다.
 */
class TokenStream(initialCapacity: Int = 128) {

    @JvmField
    var type = IntArray(initialCapacity)

    /** NUM 토큰의 값. 다른 토큰에서는 사용하지 않는다. */
    @JvmField
    var num = DoubleArray(initialCapacity)

    /** 원본 수식에서의 시작 위치. 오류를 어디서 났는지 표시할 때 쓴다. */
    @JvmField
    var pos = IntArray(initialCapacity)

    @JvmField
    var size: Int = 0

    val capacity: Int get() = type.size

    fun clear() {
        size = 0
    }

    fun add(tokenType: Int, sourcePos: Int, value: Double = 0.0) {
        if (size == type.size) grow()
        type[size] = tokenType
        num[size] = value
        pos[size] = sourcePos
        size++
    }

    fun typeAt(index: Int): Int = type[index]

    fun lastType(): Int = if (size == 0) Tok.END else type[size - 1]

    private fun grow() {
        val n = type.size + (type.size shr 1) + 16
        type = type.copyOf(n)
        num = num.copyOf(n)
        pos = pos.copyOf(n)
    }
}

/**
 * 후위 표기(RPN) 프로그램. 토큰열과 같은 이유로 병렬 배열을 쓴다.
 * 평가기는 이 배열을 앞에서 뒤로 한 번만 훑는다 -> O(n), 분기 예측에 유리한 선형 접근.
 */
class RpnProgram(initialCapacity: Int = 128) {

    @JvmField
    var op = IntArray(initialCapacity)

    @JvmField
    var num = DoubleArray(initialCapacity)

    @JvmField
    var pos = IntArray(initialCapacity)

    @JvmField
    var size: Int = 0

    fun clear() {
        size = 0
    }

    fun emit(opCode: Int, sourcePos: Int, value: Double = 0.0) {
        if (size == op.size) grow()
        op[size] = opCode
        num[size] = value
        pos[size] = sourcePos
        size++
    }

    private fun grow() {
        val n = op.size + (op.size shr 1) + 16
        op = op.copyOf(n)
        num = num.copyOf(n)
        pos = pos.copyOf(n)
    }
}
