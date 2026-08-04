package com.scicalc.app.core

/** 삼각함수의 각도 단위. */
enum class AngleMode(val label: String) {
    DEG("DEG"),
    RAD("RAD"),
    GRAD("GRAD");

    fun next(): AngleMode = when (this) {
        DEG -> RAD
        RAD -> GRAD
        GRAD -> DEG
    }
}

/**
 * 계산에 필요한 외부 상태. 값 4개짜리 가변 객체 하나를 계속 재사용한다.
 */
class EvalContext {
    @JvmField
    var angleMode: AngleMode = AngleMode.DEG

    /** 직전 계산 결과(Ans). */
    @JvmField
    var ans: Double = 0.0

    /** 메모리 값(M). */
    @JvmField
    var memory: Double = 0.0
}
