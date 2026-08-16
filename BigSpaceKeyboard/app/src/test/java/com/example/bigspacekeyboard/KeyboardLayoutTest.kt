package com.example.bigspacekeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 이 키보드가 지키기로 한 배치 불변식을 못 박아 둔다. 레이어에 키를 더하거나 뺄 때 여기가 깨지면
 * 스페이스바가 움직인다는 뜻이다.
 */
class KeyboardLayoutTest {

    private val config = KeyboardConfig()

    private fun rows(layer: Layer) =
        KeyboardLayouts.rowsFor(layer, config, Layer.LETTERS, null)

    @Test
    fun `모든 레이어가 4행 + 스페이스바 행`() {
        for (layer in Layer.values()) {
            val rows = rows(layer)
            assertEquals("$layer 의 줄 수", 5, rows.size)
            for (index in 0 until 4) {
                assertEquals("$layer 의 ${index}번째 줄 높이", 1f, rows[index].heightWeight, 0.001f)
            }
            assertEquals(
                "$layer 의 스페이스바 줄 높이",
                config.spaceHeightWeight, rows.last().heightWeight, 0.001f
            )
        }
    }

    @Test
    fun `글자 줄은 어느 레이어에서나 10칸`() {
        for (layer in Layer.values()) {
            rows(layer).dropLast(1).forEachIndexed { index, row ->
                assertEquals("$layer 의 ${index}번째 줄", 10f, row.units, 0.001f)
            }
        }
    }

    @Test
    fun `스페이스바의 위치와 폭이 모든 레이어에서 같다`() {
        for (layer in Layer.values()) {
            val bottom = rows(layer).last()
            val spaceIndex = bottom.keys.indexOfFirst { it.code == KeyCode.SPACE }
            assertTrue("$layer 에 스페이스바가 없다", spaceIndex >= 0)

            // 쌓인 키(subRow 1)는 짝의 칸을 나눠 쓰므로 폭을 두 번 세면 안 된다
            val left = bottom.keys.take(spaceIndex)
                .filter { it.subRow != 1 }.sumOf { it.width.toDouble() }.toFloat()
            val right = bottom.keys.drop(spaceIndex + 1)
                .filter { it.subRow != 1 }.sumOf { it.width.toDouble() }.toFloat()

            assertEquals("$layer 의 왼쪽 칸수", KeyboardLayouts.LEFT_UNITS, left, 0.001f)
            assertEquals("$layer 의 오른쪽 칸수", KeyboardLayouts.RIGHT_UNITS, right, 0.001f)
            assertEquals(
                "$layer 의 스페이스바 폭",
                config.spaceWidthUnits, bottom.keys[spaceIndex].width, 0.001f
            )
        }
    }

    @Test
    fun `스페이스바 양옆은 2단으로 쌓인다`() {
        for (layer in Layer.values()) {
            val bottom = rows(layer).last()
            assertEquals(
                "$layer 의 윗단",
                listOf("@", ":", "/", "?", "!"),
                bottom.keys.filter { it.subRow == 0 }.map { it.label },
            )
            // 윗단과 아랫단은 짝이 맞아야 한다 (스페이스바만 단이 없음)
            assertEquals(
                "$layer 의 아랫단 개수",
                5, bottom.keys.count { it.subRow == 1 },
            )
            assertEquals(1, bottom.keys.count { it.subRow == null })
        }
    }

    @Test
    fun `클립보드가 비면 붙여넣기 줄이 없다`() {
        for (layer in Layer.values()) {
            assertTrue(rows(layer).none { row -> row.keys.any { it.code == KeyCode.PASTE } })
        }
    }

    @Test
    fun `클립보드가 있으면 맨 위에 짧은 줄이 붙는다`() {
        for (layer in Layer.values()) {
            val rows = KeyboardLayouts.rowsFor(layer, config, Layer.LETTERS, null, "붙여넣기")
            assertEquals("$layer 의 줄 수", 6, rows.size)
            val strip = rows.first()
            assertEquals(KeyboardLayouts.CLIP_ROW_WEIGHT, strip.heightWeight, 0.001f)
            assertEquals(1, strip.keys.size)
            assertEquals(KeyCode.PASTE, strip.keys.first().code)
            assertEquals("붙여넣기", strip.keys.first().label)
        }
    }

    @Test
    fun `여러 줄 클립보드는 한 줄로 눌러 담는다`() {
        val rows = KeyboardLayouts.rowsFor(
            Layer.LETTERS, config, Layer.LETTERS, null, "  첫 줄\n\n둘째   줄\t끝  "
        )
        assertEquals("첫 줄 둘째 줄 끝", rows.first().keys.first().label)
    }

    /** 52자 = 6줄짜리 분류. 한 번에 4줄만 보이므로 2줄만큼 스크롤할 수 있다. */
    private fun samplePage() = SymbolCatalog.Page(
        "테스트",
        (('a'..'z') + ('A'..'Z')).map { it.toString() },
    )

    private fun gridLabels(page: SymbolCatalog.Page, scrollRow: Int) =
        KeyboardLayouts.rowsFor(Layer.SYMBOL_PAD, config, Layer.LETTERS, page, null, scrollRow)
            .dropLast(1)
            .map { row -> row.keys.joinToString("") { it.label } }

    @Test
    fun `분류 하나가 페이지 하나`() {
        val page = samplePage()
        assertEquals(6, page.rowCount)
        assertEquals("테스트", page.label)
    }

    @Test
    fun `스크롤한 만큼 뒤쪽 기호가 보인다`() {
        val page = samplePage()

        // 처음에는 1~4줄 (a~N)
        val top = gridLabels(page, 0)
        assertEquals("abcdefghij", top[0])
        assertEquals("EFGHIJKLMN", top[3])

        // 두 줄 내리면 3~6줄 (u~Z)
        val scrolled = gridLabels(page, 2)
        assertEquals("uvwxyzABCD", scrolled[0])
        assertEquals("OPQRSTUVWX", scrolled[2])
    }

    @Test
    fun `분류 끝의 남는 자리는 빈 칸으로 채운다`() {
        val lastGrid = KeyboardLayouts
            .rowsFor(Layer.SYMBOL_PAD, config, Layer.LETTERS, samplePage(), null, 2)
            .dropLast(1).last()

        assertEquals("YZ", lastGrid.keys.joinToString("") { it.label })
        // 남는 자리도 칸은 유지해야 마지막 줄 키가 늘어나지 않는다
        assertEquals(10, lastGrid.keys.size)
        assertEquals(8, lastGrid.keys.count { it.code == KeyCode.NONE })
    }

    @Test
    fun `스크롤해도 줄 수와 칸 수는 그대로다`() {
        for (scrollRow in 0..3) {
            val rows = KeyboardLayouts.rowsFor(
                Layer.SYMBOL_PAD, config, Layer.LETTERS, samplePage(), null, scrollRow
            )
            assertEquals(5, rows.size)
            rows.dropLast(1).forEach { assertEquals(10f, it.units, 0.001f) }
        }
    }

    @Test
    fun `쉼표와 마침표는 길게 눌러도 그대로다`() {
        val bottom = rows(Layer.LETTERS).last()
        val comma = bottom.keys.first { it.code == ','.code }
        val period = bottom.keys.first { it.code == '.'.code }
        assertEquals(null, comma.longPress)
        assertEquals(null, period.longPress)
    }

    @Test
    fun `한글 자판의 모음은 여덟 개`() {
        val vowels = rows(Layer.HANGUL).drop(1).flatMap { it.keys }
            .filter { it.isPrintable && HangulComposer.JUNG.indexOf(it.code.toChar()) >= 0 }
            .map { it.label }
        assertEquals(listOf("ㅗ", "ㅐ", "ㅔ", "ㅓ", "ㅏ", "ㅣ", "ㅜ", "ㅡ"), vowels)
    }
}
