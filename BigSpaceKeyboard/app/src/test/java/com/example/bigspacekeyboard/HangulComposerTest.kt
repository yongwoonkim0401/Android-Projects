package com.example.bigspacekeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HangulComposerTest {

    /** 자모를 순서대로 넣고, 확정된 글자 + 마지막 조합 중인 글자를 이어붙인 결과. */
    private fun type(jamos: String): String {
        val composer = HangulComposer()
        val out = StringBuilder()
        for (c in jamos) out.append(composer.input(c).commit)
        return out.append(composer.flush()).toString()
    }

    @Test
    fun `기본 음절`() {
        assertEquals("한글", type("ㅎㅏㄴㄱㅡㄹ"))
        assertEquals("안녕하세요", type("ㅇㅏㄴㄴㅕㅇㅎㅏㅅㅔㅇㅛ"))
        assertEquals("가", type("ㄱㅏ"))
        assertEquals("각", type("ㄱㅏㄱ"))
    }

    @Test
    fun `복합 모음`() {
        assertEquals("과", type("ㄱㅗㅏ"))
        assertEquals("왜", type("ㅇㅗㅐ"))
        assertEquals("의", type("ㅇㅡㅣ"))
        assertEquals("워", type("ㅇㅜㅓ"))
        assertEquals("취", type("ㅊㅜㅣ"))
    }

    @Test
    fun `겹받침`() {
        assertEquals("값", type("ㄱㅏㅂㅅ"))
        assertEquals("없", type("ㅇㅓㅂㅅ"))
        assertEquals("않", type("ㅇㅏㄴㅎ"))
        assertEquals("닭", type("ㄷㅏㄹㄱ"))
    }

    @Test
    fun `받침이 다음 음절 초성으로 넘어간다`() {
        assertEquals("가방", type("ㄱㅏㅂㅏㅇ"))
        assertEquals("안자", type("ㅇㅏㄴㅈㅏ"))
        assertEquals("갑시", type("ㄱㅏㅂㅅㅣ"))
        assertEquals("앉아", type("ㅇㅏㄴㅈㅇㅏ"))
    }

    @Test
    fun `쌍자음`() {
        assertEquals("까", type("ㄲㅏ"))
        assertEquals("있", type("ㅇㅣㅆ"))
        // ㄸ·ㅃ·ㅉ 는 받침이 될 수 없어 새 음절의 초성이 된다
        assertEquals("가ㄸ", type("ㄱㅏㄸ"))
    }

    @Test
    fun `홀로 남은 자모`() {
        assertEquals("ㄱㄴㄷ", type("ㄱㄴㄷ"))
        assertEquals("ㅏㅑ", type("ㅏㅑ"))
        assertEquals("ㅋ", type("ㅋ"))
    }

    @Test
    fun `백스페이스는 한 조각씩 지운다`() {
        val composer = HangulComposer()
        "ㅎㅏㄴ".forEach { composer.input(it) }
        assertEquals("하", composer.backspace()!!.composing)
        assertEquals("ㅎ", composer.backspace()!!.composing)
        assertEquals("", composer.backspace()!!.composing)
        assertNull(composer.backspace())
    }

    @Test
    fun `백스페이스로 겹받침과 복합모음이 되돌아간다`() {
        val composer = HangulComposer()
        "ㄱㅏㅂㅅ".forEach { composer.input(it) } // 값
        assertEquals("갑", composer.backspace()!!.composing)
        assertEquals("가", composer.backspace()!!.composing)
        assertEquals("ㄱ", composer.backspace()!!.composing)
        assertEquals("", composer.backspace()!!.composing)
        assertNull(composer.backspace())

        val vowel = HangulComposer()
        "ㄱㅗㅏ".forEach { vowel.input(it) } // 과
        assertEquals("고", vowel.backspace()!!.composing)
    }

    /** 같은 자모를 [gapMs] 간격으로 연달아 눌렀을 때의 결과. */
    private fun typeTimed(jamos: String, gapMs: Long = 100L): String {
        val composer = HangulComposer()
        val out = StringBuilder()
        var now = 10_000L
        for (c in jamos) {
            out.append(composer.input(c, now).commit)
            now += gapMs
        }
        return out.append(composer.flush()).toString()
    }

    @Test
    fun `연타로 쌍자음을 만든다`() {
        assertEquals("까", typeTimed("ㄱㄱㅏ"))
        assertEquals("따", typeTimed("ㄷㄷㅏ"))
        assertEquals("빠", typeTimed("ㅂㅂㅏ"))
        assertEquals("싸", typeTimed("ㅅㅅㅏ"))
        assertEquals("짜", typeTimed("ㅈㅈㅏ"))
    }

    @Test
    fun `연타로 쌍받침을 만든다`() {
        assertEquals("있", typeTimed("ㅇㅣㅅㅅ"))
        assertEquals("갔", typeTimed("ㄱㅏㅅㅅ"))
        assertEquals("밖", typeTimed("ㅂㅏㄱㄱ"))
    }

    @Test
    fun `연타로 중모음을 만든다`() {
        assertEquals("걔", typeTimed("ㄱㅐㅐ"))
        assertEquals("계", typeTimed("ㄱㅔㅔ"))
        assertEquals("ㅒ", typeTimed("ㅐㅐ"))
    }

    @Test
    fun `연타로 복모음을 만든다`() {
        assertEquals("야", typeTimed("ㅇㅏㅏ"))
        assertEquals("여", typeTimed("ㅇㅓㅓ"))
        assertEquals("요", typeTimed("ㅇㅗㅗ"))
        assertEquals("유", typeTimed("ㅇㅜㅜ"))
        assertEquals("교", typeTimed("ㄱㅗㅗ"))
        assertEquals("안녕하세요", typeTimed("ㅇㅏㄴㄴㅓㅓㅇㅎㅏㅅㅔㅇㅗㅗ"))
    }

    @Test
    fun `모음 연타는 간격과 설정에 영향받지 않는다`() {
        // ㅑㅕㅛㅠ 는 자판에 키가 없으므로 연타가 항상 통해야 한다
        assertEquals("야", typeTimed("ㅇㅏㅏ", gapMs = 3_000L))

        val composer = HangulComposer()
        composer.doubleTapEnabled = false
        val out = StringBuilder()
        for (c in "ㅇㅏㅏ") out.append(composer.input(c, 10_000L).commit)
        assertEquals("야", out.append(composer.flush()).toString())
    }

    @Test
    fun `줄어든 자판으로도 겹모음이 모두 나온다`() {
        assertEquals("과", typeTimed("ㄱㅗㅏ"))
        assertEquals("왜", typeTimed("ㅇㅗㅐ"))
        assertEquals("외", typeTimed("ㅇㅗㅣ"))
        assertEquals("워", typeTimed("ㅇㅜㅓ"))
        assertEquals("웨", typeTimed("ㅇㅜㅔ"))
        assertEquals("위", typeTimed("ㅇㅜㅣ"))
        assertEquals("의", typeTimed("ㅇㅡㅣ"))
    }

    @Test
    fun `자음 사이에 낀 같은 모음은 붙지 않는다`() {
        assertEquals("아아", typeTimed("ㅇㅏㅇㅏ"))
        assertEquals("가자", typeTimed("ㄱㅏㅈㅏ"))
    }

    @Test
    fun `세 번째 타건은 다시 홑자모가 된다`() {
        // 있 + 습 — 쌍받침 뒤에 같은 자음이 초성으로 이어지는 경우
        assertEquals("있습", typeTimed("ㅇㅣㅅㅅㅅㅡㅂ"))
    }

    @Test
    fun `연타 간격을 넘기면 붙지 않는다`() {
        assertEquals("ㄱ가", typeTimed("ㄱㄱㅏ", gapMs = 400L))

        // 학교: 받침 ㄱ 다음 초성 ㄱ. 사이를 쉬면 제대로 들어간다.
        val composer = HangulComposer()
        val out = StringBuilder()
        var now = 10_000L
        for (c in "ㅎㅏㄱ") {
            out.append(composer.input(c, now).commit); now += 100
        }
        now += 500 // 잠깐 쉬고
        for (c in "ㄱㅛ") {
            out.append(composer.input(c, now).commit); now += 100
        }
        assertEquals("학교", out.append(composer.flush()).toString())
    }

    @Test
    fun `연타를 끄면 Shift로만 쌍자음이 된다`() {
        val composer = HangulComposer()
        composer.doubleTapEnabled = false
        val out = StringBuilder()
        var now = 10_000L
        for (c in "ㄱㄱㅏ") {
            out.append(composer.input(c, now).commit); now += 100
        }
        assertEquals("ㄱ가", out.append(composer.flush()).toString())
    }

    @Test
    fun `백스페이스 뒤에는 연타가 이어지지 않는다`() {
        val composer = HangulComposer()
        composer.input('ㅅ', 10_000L)
        composer.backspace()
        val result = composer.input('ㅅ', 10_050L)
        assertEquals("ㅅ", result.composing)
    }

    @Test
    fun `자모 판별`() {
        assertEquals(true, HangulComposer.isJamo('ㄱ'))
        assertEquals(true, HangulComposer.isJamo('ㅢ'))
        assertEquals(false, HangulComposer.isJamo('a'))
        assertEquals(false, HangulComposer.isJamo('가'))
    }
}
