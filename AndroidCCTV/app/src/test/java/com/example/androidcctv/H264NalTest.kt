package com.example.androidcctv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class H264NalTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun 시작코드_3바이트와_4바이트를_모두_인식한다() {
        val data = bytes(
            0, 0, 0, 1, 0x67, 0xAA,          // 4바이트 시작코드 + SPS
            0, 0, 1, 0x68, 0xBB,             // 3바이트 시작코드 + PPS
            0, 0, 0, 1, 0x65, 0xCC, 0xDD     // 4바이트 시작코드 + IDR
        )
        val nals = H264Nal.splitNals(data)
        assertEquals(3, nals.size)
        assertArrayEquals(bytes(0x67, 0xAA), nals[0])
        assertArrayEquals(bytes(0x68, 0xBB), nals[1])
        assertArrayEquals(bytes(0x65, 0xCC, 0xDD), nals[2])
    }

    @Test
    fun AVCC_변환은_길이접두를_붙이고_SPS_PPS_AUD_를_뺀다() {
        val data = bytes(
            0, 0, 0, 1, 0x09, 0x10,          // AUD → 제외
            0, 0, 0, 1, 0x67, 0xAA,          // SPS → 제외
            0, 0, 0, 1, 0x68, 0xBB,          // PPS → 제외
            0, 0, 0, 1, 0x65, 0x01, 0x02, 0x03
        )
        val avcc = H264Nal.toAvcc(data)
        // 남는 것은 IDR 4바이트 → 길이(4) + 데이터(4)
        assertEquals(8, avcc.size)
        assertArrayEquals(bytes(0, 0, 0, 4, 0x65, 0x01, 0x02, 0x03), avcc)
    }

    @Test
    fun 페이로드에_0x000001_이_없어도_안전하다() {
        val data = bytes(0, 0, 0, 1, 0x65, 0x00, 0x00, 0x02, 0x00, 0x00, 0x03)
        val nals = H264Nal.splitNals(data)
        assertEquals(1, nals.size)
        assertEquals(7, nals[0].size)
    }

    @Test
    fun 시작코드가_없으면_빈결과() {
        assertEquals(0, H264Nal.splitNals(bytes(0x65, 0x01, 0x02)).size)
        assertEquals(0, H264Nal.toAvcc(bytes(0x65, 0x01, 0x02)).size)
        assertEquals(0, H264Nal.splitNals(ByteArray(0)).size)
    }

    @Test
    fun SPS_PPS_를_찾아낸다() {
        val csd = bytes(0, 0, 0, 1, 0x67, 0x42, 0xC0, 0x1E, 0, 0, 0, 1, 0x68, 0xCE, 0x3C, 0x80)
        val (sps, pps) = H264Nal.findSpsPps(csd)
        assertNotNull(sps)
        assertNotNull(pps)
        assertEquals(7, H264Nal.typeOf(sps!!))
        assertEquals(8, H264Nal.typeOf(pps!!))
        assertArrayEquals(bytes(0x67, 0x42, 0xC0, 0x1E), sps)
    }
}
