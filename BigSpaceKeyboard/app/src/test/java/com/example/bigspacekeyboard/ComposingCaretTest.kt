package com.example.bigspacekeyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 조합 중에 커서가 움직였을 때, 그게 키보드가 옮긴 것인지 사용자가 옮긴 것인지 가리는 규칙.
 *
 * 이걸 잘못 판단하면 문장 중간을 눌러도 다음 글자가 문장 끝(예전 조합 자리)에 찍히고 커서까지
 * 따라갑니다. 사용자 입장에서는 "터치해도 커서가 안 옮겨진다"로 보입니다.
 */
class ComposingCaretTest {

    /** "한" 을 조합 중: 5번 자리에 한 글자짜리 조합 구간, 커서는 그 끝인 6. */
    private val composingStart = 5
    private val composingEnd = 6

    private fun ownAt(selStart: Int, selEnd: Int = selStart) =
        isOwnCaretPosition(selStart, selEnd, composingStart, composingEnd)

    @Test
    fun `조합 구간 끝에 커서가 붙어 있으면 키보드가 놓은 자리`() {
        assertTrue(ownAt(composingEnd))
    }

    @Test
    fun `문장 중간을 터치하면 사용자가 옮긴 것`() {
        assertFalse("조합 구간보다 앞", ownAt(2))
        assertFalse("조합 구간보다 뒤", ownAt(9))
        // 조합 구간 시작점도 우리 자리가 아니다: setComposingText 는 커서를 항상 끝에 둔다
        assertFalse("조합 구간 시작점", ownAt(composingStart))
    }

    @Test
    fun `범위를 선택하면 조합을 놓아준다`() {
        assertFalse(ownAt(composingStart, composingEnd))
        assertFalse(ownAt(2, 9))
    }

    @Test
    fun `조합 구간이 사라졌으면 우리 자리일 수 없다`() {
        assertFalse(isOwnCaretPosition(composingEnd, composingEnd, -1, -1))
    }
}
