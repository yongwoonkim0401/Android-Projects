package com.example.androidcctv

import java.io.ByteArrayOutputStream

/**
 * H.264 NAL 을 fragmented MP4(ISO BMFF) 조각으로 포장한다.
 * 브라우저의 MSE(Media Source Extensions)가 그대로 받아 재생할 수 있는 형태다.
 *
 *  - init 세그먼트: ftyp + moov (SPS/PPS 를 avcC 에 담음)
 *  - 조각: moof + mdat (프레임 1장 = 조각 1개 → 지연 최소)
 *
 * 프레임 길이는 다음 프레임의 타임스탬프를 봐야 알 수 있으므로 한 프레임씩 늦춰
 * 내보낸다(10fps 에서 약 100ms). 그 대신 타임라인에 빈틈이 생기지 않는다.
 */
class Fmp4Muxer(private val width: Int, private val height: Int, private val rotation: Int) {

    companion object {
        /** 마이크로초 단위 타임스케일 → MediaCodec 의 presentationTimeUs 를 그대로 쓸 수 있다 */
        const val TIMESCALE = 1_000_000

        private const val MIN_DURATION = 1_000L
        private const val MAX_DURATION = 2_000_000L
    }

    var initSegment: ByteArray? = null
        private set

    /** MSE 에 넘길 코덱 문자열. 예: avc1.42E01E */
    var codecString: String? = null
        private set

    private var sequence = 1
    private var timeline = 0L
    private var pendingData: ByteArray? = null
    private var pendingPts = 0L
    private var pendingKey = false

    fun setConfig(sps: ByteArray, pps: ByteArray) {
        if (sps.size < 4) return
        codecString = String.format(
            "avc1.%02X%02X%02X",
            sps[1].toInt() and 0xFF, sps[2].toInt() and 0xFF, sps[3].toInt() and 0xFF
        )
        initSegment = ftyp() + moov(sps, pps)
    }

    fun reset() {
        sequence = 1
        timeline = 0L
        pendingData = null
    }

    /**
     * 샘플 하나를 넣는다. 직전 샘플이 완성되면 (조각 바이트, 키프레임여부) 를 돌려준다.
     * @param avcc 4바이트 길이 접두 형식의 NAL 들
     */
    fun offer(avcc: ByteArray, ptsUs: Long, key: Boolean): Pair<ByteArray, Boolean>? {
        val prevData = pendingData
        val prevPts = pendingPts
        val prevKey = pendingKey
        pendingData = avcc
        pendingPts = ptsUs
        pendingKey = key
        if (prevData == null) return null

        var duration = ptsUs - prevPts
        if (duration < MIN_DURATION) duration = MIN_DURATION
        if (duration > MAX_DURATION) duration = MAX_DURATION

        val frag = fragment(prevData, timeline, duration, prevKey, sequence++)
        timeline += duration
        return frag to prevKey
    }

    // ------------------------------------------------------------------ init

    private fun ftyp(): ByteArray {
        val b = ByteArrayOutputStream()
        b.w("isom".toByteArray(Charsets.US_ASCII))
        b.w(be32(0x200))
        for (brand in arrayOf("isom", "iso2", "avc1", "mp41", "iso5", "dash")) {
            b.w(brand.toByteArray(Charsets.US_ASCII))
        }
        return box("ftyp", b.toByteArray())
    }

    private fun moov(sps: ByteArray, pps: ByteArray) = box("moov", mvhd(), trak(sps, pps), mvex())

    private fun mvhd(): ByteArray {
        val b = ByteArrayOutputStream()
        b.w(be32(0))                 // version + flags
        b.w(be32(0)); b.w(be32(0))   // creation / modification
        b.w(be32(TIMESCALE))
        b.w(be32(0))                 // duration (fragmented → 0)
        b.w(be32(0x00010000))        // rate
        b.w(be16(0x0100))            // volume
        b.w(be16(0))                 // reserved
        b.w(be32(0)); b.w(be32(0))   // reserved
        b.w(matrix(0))               // 무비 레벨은 항등 행렬
        for (i in 0 until 6) b.w(be32(0))
        b.w(be32(2))                 // next_track_ID
        return box("mvhd", b.toByteArray())
    }

    private fun trak(sps: ByteArray, pps: ByteArray) = box("trak", tkhd(), mdia(sps, pps))

    private fun tkhd(): ByteArray {
        val b = ByteArrayOutputStream()
        b.w(be32(0x00000007))        // version 0, flags = enabled|in_movie|in_preview
        b.w(be32(0)); b.w(be32(0))
        b.w(be32(1))                 // track_ID
        b.w(be32(0))                 // reserved
        b.w(be32(0))                 // duration
        b.w(be32(0)); b.w(be32(0))   // reserved
        b.w(be16(0))                 // layer
        b.w(be16(0))                 // alternate_group
        b.w(be16(0))                 // volume
        b.w(be16(0))                 // reserved
        b.w(matrix(rotation))        // 회전은 여기 행렬로 처리(플레이어가 알아서 돌려 준다)
        b.w(be32(width shl 16))
        b.w(be32(height shl 16))
        return box("tkhd", b.toByteArray())
    }

    private fun mdia(sps: ByteArray, pps: ByteArray) = box("mdia", mdhd(), hdlr(), minf(sps, pps))

    private fun mdhd(): ByteArray {
        val b = ByteArrayOutputStream()
        b.w(be32(0))
        b.w(be32(0)); b.w(be32(0))
        b.w(be32(TIMESCALE))
        b.w(be32(0))
        b.w(be16(0x55C4))            // 'und'
        b.w(be16(0))
        return box("mdhd", b.toByteArray())
    }

    private fun hdlr(): ByteArray {
        val b = ByteArrayOutputStream()
        b.w(be32(0))
        b.w(be32(0))
        b.w("vide".toByteArray(Charsets.US_ASCII))
        b.w(ByteArray(12))
        b.w("VideoHandler".toByteArray(Charsets.US_ASCII))
        b.write(0)
        return box("hdlr", b.toByteArray())
    }

    private fun minf(sps: ByteArray, pps: ByteArray) = box("minf", vmhd(), dinf(), stbl(sps, pps))

    private fun vmhd(): ByteArray {
        val b = ByteArrayOutputStream()
        b.w(be32(0x00000001))
        b.w(be16(0))                 // graphicsmode
        b.w(be16(0)); b.w(be16(0)); b.w(be16(0))
        return box("vmhd", b.toByteArray())
    }

    private fun dinf(): ByteArray {
        val url = box("url ", be32(0x00000001))
        val dref = box("dref", be32(0) + be32(1) + url)
        return box("dinf", dref)
    }

    private fun stbl(sps: ByteArray, pps: ByteArray) = box(
        "stbl",
        box("stsd", be32(0) + be32(1) + avc1(sps, pps)),
        box("stts", be32(0) + be32(0)),
        box("stsc", be32(0) + be32(0)),
        box("stsz", be32(0) + be32(0) + be32(0)),
        box("stco", be32(0) + be32(0))
    )

    private fun avc1(sps: ByteArray, pps: ByteArray): ByteArray {
        val b = ByteArrayOutputStream()
        b.w(ByteArray(6))            // reserved
        b.w(be16(1))                 // data_reference_index
        b.w(be16(0)); b.w(be16(0))   // pre_defined / reserved
        b.w(ByteArray(12))           // pre_defined
        b.w(be16(width))
        b.w(be16(height))
        b.w(be32(0x00480000))        // horiz resolution 72dpi
        b.w(be32(0x00480000))        // vert resolution
        b.w(be32(0))                 // reserved
        b.w(be16(1))                 // frame_count
        b.w(ByteArray(32))           // compressorname
        b.w(be16(0x0018))            // depth
        b.w(be16(0xFFFF))            // pre_defined
        b.w(avcC(sps, pps))
        return box("avc1", b.toByteArray())
    }

    private fun avcC(sps: ByteArray, pps: ByteArray): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(1)                                   // configurationVersion
        b.write(sps[1].toInt() and 0xFF)             // AVCProfileIndication
        b.write(sps[2].toInt() and 0xFF)             // profile_compatibility
        b.write(sps[3].toInt() and 0xFF)             // AVCLevelIndication
        b.write(0xFF)                                // lengthSizeMinusOne = 3
        b.write(0xE1)                                // numOfSequenceParameterSets = 1
        b.w(be16(sps.size)); b.w(sps)
        b.write(1)                                   // numOfPictureParameterSets
        b.w(be16(pps.size)); b.w(pps)
        return box("avcC", b.toByteArray())
    }

    private fun mvex() = box(
        "mvex",
        box("trex", be32(0) + be32(1) + be32(1) + be32(0) + be32(0) + be32(0))
    )

    // -------------------------------------------------------------- fragment

    private fun fragment(
        avcc: ByteArray,
        decodeTime: Long,
        duration: Long,
        key: Boolean,
        seq: Int
    ): ByteArray {
        // moof 크기를 먼저 계산해야 trun 의 data_offset 을 채울 수 있다.
        val sampleCount = 1
        val trunSize = 20 + 12 * sampleCount
        val trafSize = 8 + 16 + 20 + trunSize     // traf 헤더 + tfhd + tfdt + trun
        val moofSize = 8 + 16 + trafSize          // moof 헤더 + mfhd + traf
        val dataOffset = moofSize + 8             // mdat 헤더까지 건너뛴 위치

        val mfhd = box("mfhd", be32(0) + be32(seq))
        val tfhd = box("tfhd", be32(0x00020000) + be32(1))   // default-base-is-moof
        val tfdt = box("tfdt", be32(0x01000000) + be64(decodeTime))

        val t = ByteArrayOutputStream()
        t.w(be32(0x00000701))    // data-offset | sample-duration | sample-size | sample-flags
        t.w(be32(sampleCount))
        t.w(be32(dataOffset))
        t.w(be32(duration.toInt()))
        t.w(be32(avcc.size))
        t.w(be32(if (key) 0x02000000 else 0x01010000))
        val trun = box("trun", t.toByteArray())

        val moof = box("moof", mfhd, box("traf", tfhd, tfdt, trun))
        return moof + box("mdat", avcc)
    }

    // ----------------------------------------------------------------- 유틸

    private fun matrix(degrees: Int): ByteArray {
        val one = 0x00010000
        val neg = -0x00010000
        val w16 = width shl 16
        val h16 = height shl 16
        val m = when (((degrees % 360) + 360) % 360) {
            90 -> intArrayOf(0, one, 0, neg, 0, 0, h16, 0, 0x40000000)
            180 -> intArrayOf(neg, 0, 0, 0, neg, 0, w16, h16, 0x40000000)
            270 -> intArrayOf(0, neg, 0, one, 0, 0, 0, w16, 0x40000000)
            else -> intArrayOf(one, 0, 0, 0, one, 0, 0, 0, 0x40000000)
        }
        val b = ByteArrayOutputStream(36)
        for (v in m) b.w(be32(v))
        return b.toByteArray()
    }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        var size = 8
        for (p in parts) size += p.size
        val out = ByteArrayOutputStream(size)
        out.w(be32(size))
        out.w(type.toByteArray(Charsets.US_ASCII))
        for (p in parts) out.w(p)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.w(b: ByteArray) = write(b, 0, b.size)

    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())

    private fun be32(v: Int) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )

    private fun be64(v: Long) = byteArrayOf(
        (v ushr 56).toByte(), (v ushr 48).toByte(), (v ushr 40).toByte(), (v ushr 32).toByte(),
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )
}
