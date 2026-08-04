package com.scicalc.app.ui

import com.scicalc.app.core.Sym

/** 키를 눌렀을 때 할 일. 정적 테이블이라 실행 중에 새로 만들어지지 않는다. */
sealed class KeyAction {
    /** 버퍼에 문자열을 그대로 삽입한다. */
    class Insert(val text: String) : KeyAction()

    /** 함수 기호와 여는 괄호를 함께 삽입한다. */
    class Func(val sentinel: Char) : KeyAction()

    object Delete : KeyAction()
    object Clear : KeyAction()
    object Equals : KeyAction()
    object CursorLeft : KeyAction()
    object CursorRight : KeyAction()
    object ToggleSign : KeyAction()
    object ToggleSecond : KeyAction()
    object ToggleAngle : KeyAction()
    object MemoryClear : KeyAction()
    object MemoryRecall : KeyAction()
    object MemoryAdd : KeyAction()
    object MemorySubtract : KeyAction()
}

enum class KeyStyle { DIGIT, OPERATOR, FUNCTION, ACCENT, WARN }

class KeyFace(val label: String, val action: KeyAction, val style: KeyStyle)

/** 기본 상태와 2nd 상태의 두 얼굴을 가진 키. */
class KeyDef(val primary: KeyFace, val secondary: KeyFace? = null) {
    fun face(second: Boolean): KeyFace = if (second && secondary != null) secondary else primary
}

/**
 * 5열 × 8행 키패드 정의.
 *
 * 뷰를 XML 로 40 개 늘어놓는 대신 표 하나로 두면 2nd 전환 시 라벨/동작만 갈아끼우면 되고,
 * 뷰 계층이 바뀌지 않으므로 재레이아웃 비용도 없다.
 */
object Keys {
    const val COLUMNS = 5
    const val ROWS = 8

    private fun ins(label: String, text: String, style: KeyStyle) =
        KeyFace(label, KeyAction.Insert(text), style)

    private fun fn(label: String, sentinel: Char) =
        KeyFace(label, KeyAction.Func(sentinel), KeyStyle.FUNCTION)

    private fun cmd(label: String, action: KeyAction, style: KeyStyle) =
        KeyFace(label, action, style)

    /** 행 우선(row-major) 순서. */
    val table: Array<KeyDef> = arrayOf(
        // ── 1행 ────────────────────────────────────────────────────────────
        KeyDef(cmd("2nd", KeyAction.ToggleSecond, KeyStyle.FUNCTION)),
        KeyDef(ins("(", "(", KeyStyle.FUNCTION)),
        KeyDef(ins(")", ")", KeyStyle.FUNCTION)),
        KeyDef(fn("mod", Sym.MOD), fn("nCr", Sym.NCR)),
        KeyDef(cmd("DEL", KeyAction.Delete, KeyStyle.WARN)),

        // ── 2행 ────────────────────────────────────────────────────────────
        KeyDef(fn("sin", Sym.SIN), fn("sin⁻¹", Sym.ASIN)),
        KeyDef(fn("cos", Sym.COS), fn("cos⁻¹", Sym.ACOS)),
        KeyDef(fn("tan", Sym.TAN), fn("tan⁻¹", Sym.ATAN)),
        KeyDef(ins("xʸ", "^", KeyStyle.OPERATOR)),
        KeyDef(ins("÷", "/", KeyStyle.OPERATOR)),

        // ── 3행 ────────────────────────────────────────────────────────────
        KeyDef(fn("ln", Sym.LN), fn("eˣ", Sym.EXP)),
        KeyDef(fn("log", Sym.LOG10), fn("log₂", Sym.LOG2)),
        KeyDef(fn("√", Sym.SQRT), fn("∛", Sym.CBRT)),
        KeyDef(ins("x²", "²", KeyStyle.FUNCTION), ins("x³", "³", KeyStyle.FUNCTION)),
        KeyDef(ins("×", "*", KeyStyle.OPERATOR)),

        // ── 4행 ────────────────────────────────────────────────────────────
        KeyDef(ins("π", Sym.PI.toString(), KeyStyle.FUNCTION), fn("sinh", Sym.SINH)),
        KeyDef(ins("e", Sym.EULER.toString(), KeyStyle.FUNCTION), fn("cosh", Sym.COSH)),
        KeyDef(ins("x!", "!", KeyStyle.FUNCTION), fn("tanh", Sym.TANH)),
        KeyDef(ins("EE", "E", KeyStyle.FUNCTION), ins("x⁻¹", Sym.RECIP.toString(), KeyStyle.FUNCTION)),
        KeyDef(ins("−", "-", KeyStyle.OPERATOR)),

        // ── 5행 ────────────────────────────────────────────────────────────
        KeyDef(ins("7", "7", KeyStyle.DIGIT)),
        KeyDef(ins("8", "8", KeyStyle.DIGIT)),
        KeyDef(ins("9", "9", KeyStyle.DIGIT)),
        KeyDef(cmd("◀", KeyAction.CursorLeft, KeyStyle.FUNCTION)),
        KeyDef(ins("+", "+", KeyStyle.OPERATOR)),

        // ── 6행 ────────────────────────────────────────────────────────────
        KeyDef(ins("4", "4", KeyStyle.DIGIT)),
        KeyDef(ins("5", "5", KeyStyle.DIGIT)),
        KeyDef(ins("6", "6", KeyStyle.DIGIT)),
        KeyDef(cmd("▶", KeyAction.CursorRight, KeyStyle.FUNCTION)),
        KeyDef(ins("%", "%", KeyStyle.OPERATOR), fn("abs", Sym.ABS)),

        // ── 7행 ────────────────────────────────────────────────────────────
        KeyDef(ins("1", "1", KeyStyle.DIGIT)),
        KeyDef(ins("2", "2", KeyStyle.DIGIT)),
        KeyDef(ins("3", "3", KeyStyle.DIGIT)),
        KeyDef(ins(",", ",", KeyStyle.FUNCTION), fn("nPr", Sym.NPR)),
        KeyDef(ins("Ans", Sym.ANS.toString(), KeyStyle.FUNCTION), fn("logₐ", Sym.LOGB)),

        // ── 8행 ────────────────────────────────────────────────────────────
        KeyDef(cmd("AC", KeyAction.Clear, KeyStyle.WARN)),
        KeyDef(ins("0", "0", KeyStyle.DIGIT)),
        KeyDef(ins(".", ".", KeyStyle.DIGIT)),
        KeyDef(cmd("±", KeyAction.ToggleSign, KeyStyle.FUNCTION)),
        KeyDef(cmd("=", KeyAction.Equals, KeyStyle.ACCENT))
    )
}
