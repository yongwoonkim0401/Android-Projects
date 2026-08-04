package com.scicalc.app.core

/**
 * 계산 실패 사유. 예외를 던지지 않고 코드로 돌려준다.
 *
 * 타이핑 중 미리보기는 대부분 "아직 완성되지 않은 수식"이라 실패가 정상 흐름이다.
 * 그 경로에서 예외를 만들면 매 키 입력마다 스택 트레이스를 수집하는 비용이 붙는다.
 */
object Err {
    const val NONE = 0

    /** 연산자/피연산자 배치가 잘못됨. */
    const val SYNTAX = 1

    /** 괄호 짝이 맞지 않음. */
    const val UNBALANCED = 2

    /** 해석할 수 없는 문자. */
    const val BAD_CHAR = 3

    /** 0 으로 나눔. */
    const val DIV_ZERO = 4

    /** 정의역을 벗어남(√-1, ln 0 등). */
    const val DOMAIN = 5

    /** 표현 가능한 범위를 넘음(무한대/NaN). */
    const val OVERFLOW = 6

    /** 함수 인자 개수가 맞지 않음. */
    const val ARITY = 7

    /** 빈 수식. */
    const val EMPTY = 8

    /** 수식이 너무 복잡함(내부 한계 초과). */
    const val TOO_COMPLEX = 9
}
