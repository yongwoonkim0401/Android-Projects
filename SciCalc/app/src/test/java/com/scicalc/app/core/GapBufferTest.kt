package com.scicalc.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GapBufferTest {

    private fun bufferOf(text: String) = GapBuffer().apply { insert(text) }

    @Test
    fun `삽입과 읽기`() {
        val b = bufferOf("1+2")
        assertEquals(3, b.length)
        assertEquals('1', b[0])
        assertEquals('+', b[1])
        assertEquals('2', b[2])
        assertEquals("1+2", b.contentToString())
    }

    @Test
    fun `커서 중간 삽입`() {
        val b = bufferOf("13")
        b.moveCursorTo(1)
        b.insert('2')
        assertEquals("123", b.contentToString())
        assertEquals(2, b.cursor)
    }

    @Test
    fun `커서 이동 후 백스페이스는 왼쪽 문자를 지운다`() {
        val b = bufferOf("1234")
        b.moveCursorTo(2)
        assertTrue(b.backspace())
        assertEquals("134", b.contentToString())
        assertEquals(1, b.cursor)
    }

    @Test
    fun `앞뒤 경계에서의 삭제는 실패로 알린다`() {
        val b = bufferOf("5")
        b.moveCursorTo(0)
        assertFalse(b.backspace())
        b.moveCursorToEnd()
        assertFalse(b.deleteForward())
    }

    @Test
    fun `deleteForward 는 커서 오른쪽을 지운다`() {
        val b = bufferOf("abc")
        b.moveCursorTo(1)
        assertTrue(b.deleteForward())
        assertEquals("ac", b.contentToString())
    }

    @Test
    fun `배열 증가는 분할상환 - 1만 자 입력에 재할당이 20회 미만`() {
        val b = GapBuffer()
        var lastCapacity = b.capacity
        var reallocations = 0
        repeat(10_000) {
            b.insert('7')
            if (b.capacity != lastCapacity) {
                reallocations++
                lastCapacity = b.capacity
            }
        }
        assertEquals(10_000, b.length)
        assertTrue("재할당 $reallocations 회는 너무 많다", reallocations < 20)
    }

    @Test
    fun `clear 는 길이를 0으로 되돌린다`() {
        val b = bufferOf("123456")
        b.clear()
        assertEquals(0, b.length)
        assertEquals(0, b.cursor)
        b.insert("9")
        assertEquals("9", b.contentToString())
    }

    @Test
    fun `clear 는 과도하게 커진 배열을 회수한다`() {
        val b = GapBuffer()
        b.insert(buildString { repeat(20_000) { append('1') } })
        assertTrue(b.capacity > GapBuffer.TRIM_THRESHOLD)
        b.clear()
        assertEquals(GapBuffer.DEFAULT_CAPACITY, b.capacity)
    }

    @Test
    fun `MAX_LENGTH 를 넘으면 삽입을 거부한다`() {
        val b = GapBuffer()
        b.insert(buildString { repeat(GapBuffer.MAX_LENGTH) { append('1') } })
        assertEquals(GapBuffer.MAX_LENGTH, b.length)
        assertFalse(b.insert('2'))
        assertEquals(GapBuffer.MAX_LENGTH, b.length)
    }

    @Test
    fun `copyInto 는 갭 양쪽을 모두 복사한다`() {
        val b = bufferOf("abcdef")
        b.moveCursorTo(3) // 갭이 가운데 있는 상태
        val dest = CharArray(10)
        val n = b.copyInto(dest)
        assertEquals(6, n)
        assertEquals("abcdef", String(dest, 0, n))
    }
}
