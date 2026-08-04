package com.example.androidcctv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fMP4 컨테이너 구조 검증.
 * 박스 크기 계산이 1바이트라도 어긋나면 브라우저가 조용히 재생을 포기하므로
 * 박스 트리를 다시 파싱해 크기 합·필드 위치·data_offset 을 직접 확인한다.
 */
class Fmp4MuxerTest {

    // 640x480 baseline 3.0 스트림에서 나올 만한 SPS/PPS (NAL 헤더 포함)
    private val sps = byteArrayOf(
        0x67, 0x42, 0xC0.toByte(), 0x1E, 0xDA.toByte(), 0x02, 0x80.toByte(),
        0xBF.toByte(), 0xE5.toByte(), 0xC0.toByte(), 0x44, 0x00, 0x00, 0x03,
        0x00, 0x04, 0x00, 0x00, 0x03, 0x00, 0xC8.toByte(), 0x3C, 0x60, 0xC9.toByte(), 0x20
    )
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())

    // ---------------------------------------------------------------- 박스 파서

    private class Box(val type: String, val start: Int, val size: Int) {
        val payload: Int get() = start + 8
        val end: Int get() = start + size
    }

    /** [from, to) 구간을 박스 목록으로 나누고, 끝이 정확히 맞는지 확인한다. */
    private fun boxes(data: ByteArray, from: Int, to: Int): List<Box> {
        val out = ArrayList<Box>()
        var i = from
        while (i + 8 <= to) {
            val size = be32(data, i)
            val type = String(data, i + 4, 4, Charsets.US_ASCII)
            assertTrue("$type 박스 크기 비정상: $size", size >= 8)
            assertTrue("$type 박스가 구간을 넘음 (start=$i size=$size limit=$to)", i + size <= to)
            out.add(Box(type, i, size))
            i += size
        }
        assertEquals("구간 끝이 박스 경계와 정확히 일치해야 한다", to, i)
        return out
    }

    private fun children(data: ByteArray, b: Box) = boxes(data, b.payload, b.end)

    private fun child(data: ByteArray, b: Box, type: String): Box {
        val c = children(data, b).firstOrNull { it.type == type }
        assertNotNull("${b.type} 안에 $type 이 있어야 한다", c)
        return c!!
    }

    private fun be32(d: ByteArray, i: Int): Int =
        ((d[i].toInt() and 0xFF) shl 24) or ((d[i + 1].toInt() and 0xFF) shl 16) or
            ((d[i + 2].toInt() and 0xFF) shl 8) or (d[i + 3].toInt() and 0xFF)

    private fun be16(d: ByteArray, i: Int): Int =
        ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)

    private fun be64(d: ByteArray, i: Int): Long {
        var v = 0L
        for (k in 0 until 8) v = (v shl 8) or (d[i + k].toLong() and 0xFF)
        return v
    }

    private fun muxer(rotation: Int = 0) = Fmp4Muxer(640, 480, rotation).apply { setConfig(sps, pps) }

    // ------------------------------------------------------------ init 세그먼트

    @Test
    fun initSegment_박스구조가_유효하다() {
        val init = muxer().initSegment
        assertNotNull("init 세그먼트가 만들어져야 한다", init)
        val d = init!!

        val top = boxes(d, 0, d.size)
        assertEquals(listOf("ftyp", "moov"), top.map { it.type })

        val moov = top[1]
        assertEquals(listOf("mvhd", "trak", "mvex"), children(d, moov).map { it.type })

        val trak = child(d, moov, "trak")
        assertEquals(listOf("tkhd", "mdia"), children(d, trak).map { it.type })

        val mdia = child(d, trak, "mdia")
        assertEquals(listOf("mdhd", "hdlr", "minf"), children(d, mdia).map { it.type })

        val minf = child(d, mdia, "minf")
        assertEquals(listOf("vmhd", "dinf", "stbl"), children(d, minf).map { it.type })

        val stbl = child(d, minf, "stbl")
        assertEquals(
            listOf("stsd", "stts", "stsc", "stsz", "stco"),
            children(d, stbl).map { it.type }
        )

        // stsd 는 (version+flags, entry_count) 뒤에 샘플 엔트리가 온다
        val stsd = child(d, stbl, "stsd")
        assertEquals(1, be32(d, stsd.payload + 4))
        val entries = boxes(d, stsd.payload + 8, stsd.end)
        assertEquals(listOf("avc1"), entries.map { it.type })

        val avc1 = entries[0]
        // VisualSampleEntry: 앞 78바이트가 고정 필드, 그 뒤에 avcC
        assertEquals(640, be16(d, avc1.payload + 24))
        assertEquals(480, be16(d, avc1.payload + 26))
        val avcC = boxes(d, avc1.payload + 78, avc1.end).first()
        assertEquals("avcC", avcC.type)
    }

    @Test
    fun avcC_에_SPS_PPS_가_그대로_담긴다() {
        val d = muxer().initSegment!!
        val moov = boxes(d, 0, d.size)[1]
        val stbl = child(d, child(d, child(d, child(d, moov, "trak"), "mdia"), "minf"), "stbl")
        val stsd = child(d, stbl, "stsd")
        val avc1 = boxes(d, stsd.payload + 8, stsd.end).first()
        val avcC = boxes(d, avc1.payload + 78, avc1.end).first()

        var p = avcC.payload
        assertEquals("configurationVersion", 1, d[p].toInt())
        assertEquals("profile", 0x42, d[p + 1].toInt() and 0xFF)
        assertEquals("compatibility", 0xC0, d[p + 2].toInt() and 0xFF)
        assertEquals("level", 0x1E, d[p + 3].toInt() and 0xFF)
        assertEquals("lengthSizeMinusOne=3", 0xFF, d[p + 4].toInt() and 0xFF)
        assertEquals("numOfSPS=1", 0xE1, d[p + 5].toInt() and 0xFF)
        p += 6
        val spsLen = be16(d, p)
        assertEquals(sps.size, spsLen)
        assertArrayEquals(sps, d.copyOfRange(p + 2, p + 2 + spsLen))
        p += 2 + spsLen
        assertEquals("numOfPPS=1", 1, d[p].toInt())
        val ppsLen = be16(d, p + 1)
        assertEquals(pps.size, ppsLen)
        assertArrayEquals(pps, d.copyOfRange(p + 3, p + 3 + ppsLen))
    }

    @Test
    fun 코덱문자열이_SPS_에서_만들어진다() {
        assertEquals("avc1.42C01E", muxer().codecString)
    }

    @Test
    fun tkhd_회전행렬이_각도별로_다르다() {
        fun matrixOf(rot: Int): List<Int> {
            val d = muxer(rot).initSegment!!
            val tkhd = child(d, child(d, boxes(d, 0, d.size)[1], "trak"), "tkhd")
            // version/flags(4) + 2*4 + trackID(4) + reserved(4) + duration(4)
            // + reserved(8) + layer(2) + altGroup(2) + volume(2) + reserved(2) = 40
            val m = tkhd.payload + 40
            return (0 until 9).map { be32(d, m + it * 4) }
        }
        val one = 0x00010000
        val neg = -0x00010000
        assertEquals(listOf(one, 0, 0, 0, one, 0, 0, 0, 0x40000000), matrixOf(0))
        assertEquals(listOf(0, one, 0, neg, 0, 0, 480 shl 16, 0, 0x40000000), matrixOf(90))
        assertEquals(listOf(neg, 0, 0, 0, neg, 0, 640 shl 16, 480 shl 16, 0x40000000), matrixOf(180))
        assertEquals(listOf(0, neg, 0, one, 0, 0, 0, 640 shl 16, 0x40000000), matrixOf(270))

        // tkhd 의 width/height 는 코딩 해상도(16.16 고정소수점)
        val d = muxer(90).initSegment!!
        val tkhd = child(d, child(d, boxes(d, 0, d.size)[1], "trak"), "tkhd")
        assertEquals(640 shl 16, be32(d, tkhd.payload + 76))
        assertEquals(480 shl 16, be32(d, tkhd.payload + 80))
    }

    // ---------------------------------------------------------------- 프래그먼트

    @Test
    fun 첫_샘플은_다음_샘플이_와야_출력된다() {
        val m = muxer()
        assertNull("첫 offer 는 아직 길이를 모르므로 null", m.offer(sample(100), 0, true))
        assertNotNull("두 번째 offer 에서 첫 조각이 완성된다", m.offer(sample(100), 100_000, false))
    }

    @Test
    fun 프래그먼트_구조와_data_offset_이_정확하다() {
        val m = muxer()
        val payload = sample(1234)
        m.offer(payload, 0, true)
        val (frag, key) = m.offer(sample(500), 100_000, false)!!
        assertTrue("첫 조각은 키프레임이어야 한다", key)

        val top = boxes(frag, 0, frag.size)
        assertEquals(listOf("moof", "mdat"), top.map { it.type })
        val moof = top[0]
        val mdat = top[1]

        assertEquals(listOf("mfhd", "traf"), children(frag, moof).map { it.type })
        val traf = child(frag, moof, "traf")
        assertEquals(listOf("tfhd", "tfdt", "trun"), children(frag, traf).map { it.type })

        // mfhd sequence_number 는 1부터
        assertEquals(1, be32(frag, child(frag, moof, "mfhd").payload + 4))

        // tfhd: version/flags = default-base-is-moof(0x020000), track_ID = 1
        val tfhd = child(frag, traf, "tfhd")
        assertEquals(0x00020000, be32(frag, tfhd.payload))
        assertEquals(1, be32(frag, tfhd.payload + 4))

        // tfdt: version 1 → 64비트
        val tfdt = child(frag, traf, "tfdt")
        assertEquals(0x01000000, be32(frag, tfdt.payload))
        assertEquals(0L, be64(frag, tfdt.payload + 4))

        val trun = child(frag, traf, "trun")
        assertEquals("flags = data-offset|duration|size|flags", 0x00000701, be32(frag, trun.payload))
        assertEquals("샘플 1개", 1, be32(frag, trun.payload + 4))
        val dataOffset = be32(frag, trun.payload + 8)
        assertEquals(
            "data_offset 은 moof 시작점 기준 mdat 페이로드 위치여야 한다",
            mdat.payload - moof.start, dataOffset
        )
        assertEquals("duration 은 두 pts 차이", 100_000, be32(frag, trun.payload + 12))
        assertEquals("size 는 실제 샘플 크기", payload.size, be32(frag, trun.payload + 16))
        assertEquals("키프레임 플래그", 0x02000000, be32(frag, trun.payload + 20))

        assertEquals("mdat 페이로드 = 샘플", payload.size, mdat.end - mdat.payload)
        assertArrayEquals(payload, frag.copyOfRange(mdat.payload, mdat.end))
    }

    @Test
    fun 타임라인에_빈틈이_없다() {
        val m = muxer()
        val pts = longArrayOf(0, 100_000, 233_000, 300_000, 466_000)
        val fragments = ArrayList<ByteArray>()
        for ((i, t) in pts.withIndex()) {
            m.offer(sample(64), t, i == 0)?.let { fragments.add(it.first) }
        }
        assertEquals(pts.size - 1, fragments.size)

        var expected = 0L
        for ((i, frag) in fragments.withIndex()) {
            val moof = boxes(frag, 0, frag.size)[0]
            val traf = child(frag, moof, "traf")
            val tfdt = be64(frag, child(frag, traf, "tfdt").payload + 4)
            val trun = child(frag, traf, "trun")
            assertEquals("조각 $i 의 tfdt 는 누적 길이와 같아야 한다", expected, tfdt)
            assertEquals("sequence_number 는 1씩 증가", i + 1, be32(frag, child(frag, moof, "mfhd").payload + 4))
            expected += be32(frag, trun.payload + 12).toLong()
        }
        // 길이의 합 = 마지막 pts - 첫 pts (빈틈도 겹침도 없음)
        assertEquals("타임라인 총 길이", pts[pts.size - 1] - pts[0], expected)
    }

    @Test
    fun 비정상_타임스탬프는_보정된다() {
        val m = muxer()
        m.offer(sample(32), 5_000_000, true)
        // 타임스탬프가 거꾸로 와도 길이가 음수가 되면 안 된다
        val (frag, _) = m.offer(sample(32), 4_000_000, false)!!
        val trun = child(frag, child(frag, boxes(frag, 0, frag.size)[0], "traf"), "trun")
        val duration = be32(frag, trun.payload + 12)
        assertTrue("길이는 양수여야 한다 (실제: $duration)", duration > 0)

        // 너무 큰 간격도 상한으로 잘린다
        val m2 = muxer()
        m2.offer(sample(32), 0, true)
        val (f2, _) = m2.offer(sample(32), 60_000_000, false)!!
        val t2 = child(f2, child(f2, boxes(f2, 0, f2.size)[0], "traf"), "trun")
        assertEquals(2_000_000, be32(f2, t2.payload + 12))
    }

    @Test
    fun 비키프레임_플래그가_구분된다() {
        val m = muxer()
        m.offer(sample(16), 0, false)
        val (frag, key) = m.offer(sample(16), 100_000, true)!!
        assertTrue("두 번째 조각의 대상은 첫 샘플(비키프레임)", !key)
        val trun = child(frag, child(frag, boxes(frag, 0, frag.size)[0], "traf"), "trun")
        assertEquals(0x01010000, be32(frag, trun.payload + 20))
    }

    private fun sample(size: Int) = ByteArray(size) { (it % 251).toByte() }
}
