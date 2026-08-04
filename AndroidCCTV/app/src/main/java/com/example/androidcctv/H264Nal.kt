package com.example.androidcctv

import java.io.ByteArrayOutputStream

/**
 * H.264 비트스트림 조각 다루기. 안드로이드 API 를 쓰지 않아 단위 테스트가 가능하다.
 *
 * MediaCodec 은 Annex-B(시작코드 00 00 01 / 00 00 00 01)로 내보내지만
 * MP4 는 4바이트 길이 접두(AVCC) 형식을 요구한다.
 */
object H264Nal {

    fun splitNals(data: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>(4)
        var i = 0
        var start = -1
        while (i + 2 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                val sc = when {
                    data[i + 2] == 1.toByte() -> 3
                    data[i + 2] == 0.toByte() && i + 3 < data.size && data[i + 3] == 1.toByte() -> 4
                    else -> 0
                }
                if (sc == 0) {
                    i++
                    continue
                }
                if (start >= 0 && i > start) out.add(data.copyOfRange(start, i))
                start = i + sc
                i = start
            } else {
                i++
            }
        }
        if (start in 0 until data.size) out.add(data.copyOfRange(start, data.size))
        return out
    }

    fun typeOf(nal: ByteArray): Int = if (nal.isEmpty()) -1 else nal[0].toInt() and 0x1F

    /** SPS(7)/PPS(8)/AUD(9) 는 avcC 에 이미 담기므로 샘플에서 뺀다. */
    fun toAvcc(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size + 16)
        for (nal in splitNals(data)) {
            when (typeOf(nal)) {
                -1, 7, 8, 9 -> continue
            }
            val n = nal.size
            out.write((n ushr 24) and 0xFF)
            out.write((n ushr 16) and 0xFF)
            out.write((n ushr 8) and 0xFF)
            out.write(n and 0xFF)
            out.write(nal, 0, n)
        }
        return out.toByteArray()
    }

    /** codec-config 버퍼에서 SPS / PPS 를 골라낸다. */
    fun findSpsPps(annexB: ByteArray): Pair<ByteArray?, ByteArray?> {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (nal in splitNals(annexB)) {
            when (typeOf(nal)) {
                7 -> sps = nal
                8 -> pps = nal
            }
        }
        return sps to pps
    }
}
