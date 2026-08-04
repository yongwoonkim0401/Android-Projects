package com.scicalc.app.core

/**
 * 파서가 읽어들이는 문자 공급원.
 *
 * 토크나이저가 [GapBuffer] 와 스냅샷 [CharArraySource] 를 모두 다룰 수 있게 하는 최소 인터페이스다.
 * String 을 만들지 않고 인덱스로만 읽으므로, 수식이 아무리 길어도 파싱 과정에서
 * 원본 크기만큼의 복사본이 새로 생기지 않는다.
 */
interface CharSource {
    val length: Int
    operator fun get(index: Int): Char
}

/**
 * 미리 확보한 [CharArray] 를 감싸는 공급원. 배열은 재사용되며 [len] 만 갱신한다.
 * (백그라운드 계산 스레드에 넘길 스냅샷 용도)
 */
class CharArraySource : CharSource {
    @JvmField
    var chars: CharArray = CharArray(0)

    @JvmField
    var len: Int = 0

    override val length: Int get() = len

    override fun get(index: Int): Char = chars[index]
}
