package com.example.androidcctv

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface

/**
 * 하드웨어 H.264 인코더. 카메라가 그리는 Surface 를 입력으로 받으므로
 * CPU 로 픽셀을 만지지 않는다(= 구형 폰에서 JPEG 소프트웨어 인코딩보다 훨씬 가볍다).
 */
class H264Encoder(
    width: Int,
    height: Int,
    bitrateKbps: Int,
    frameRate: Int,
    keyIntervalSec: Int,
    private val onConfig: (sps: ByteArray, pps: ByteArray, format: MediaFormat) -> Unit,
    private val onSample: (annexB: ByteArray, ptsUs: Long, key: Boolean) -> Unit
) {

    companion object {
        private const val TAG = "CctvEncoder"
        private const val MIME = "video/avc"
    }

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MIME)
    val inputSurface: Surface

    @Volatile private var running = false
    private var thread: Thread? = null
    private var configSent = false

    init {
        val fmt = MediaFormat.createVideoFormat(MIME, width, height)
        fmt.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps.coerceIn(100, 8000) * 1000)
        // 레이트 컨트롤 기준값. 카메라에 요청한 FPS 와 맞춰야 비트 배분이 정상적으로 된다.
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate.coerceIn(1, 60))
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyIntervalSec.coerceIn(1, 10))
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
    }

    fun start() {
        codec.start()
        running = true
        thread = Thread({ drain() }, "cctv-h264").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    fun release() {
        running = false
        try {
            thread?.join(700)
        } catch (ignored: InterruptedException) {
        }
        thread = null
        try {
            codec.stop()
        } catch (ignored: Throwable) {
        }
        try {
            codec.release()
        } catch (ignored: Throwable) {
        }
        try {
            inputSurface.release()
        } catch (ignored: Throwable) {
        }
    }

    /** 새 시청자가 붙었을 때 즉시 화면이 뜨도록 키프레임을 요청한다. */
    fun requestKeyFrame() {
        try {
            val b = Bundle()
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            codec.setParameters(b)
        } catch (t: Throwable) {
            Log.w(TAG, "키프레임 요청 실패", t)
        }
    }

    /** 재바인딩 없이 비트레이트만 바꾼다. */
    fun setBitrate(kbps: Int) {
        try {
            val b = Bundle()
            b.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, kbps.coerceIn(100, 8000) * 1000)
            codec.setParameters(b)
        } catch (t: Throwable) {
            Log.w(TAG, "비트레이트 변경 실패", t)
        }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            try {
                val index = codec.dequeueOutputBuffer(info, 20_000)
                if (index >= 0) {
                    val buf = codec.getOutputBuffer(index)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val data = ByteArray(info.size)
                        buf.get(data)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            publishConfig(data)
                        } else {
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            onSample(data, info.presentationTimeUs, key)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val fmt = codec.outputFormat
                    val csd0 = fmt.getByteBuffer("csd-0")
                    if (csd0 != null) {
                        val bytes = ByteArray(csd0.remaining())
                        csd0.duplicate().get(bytes)
                        val csd1 = fmt.getByteBuffer("csd-1")
                        val all = if (csd1 != null) {
                            val b1 = ByteArray(csd1.remaining())
                            csd1.duplicate().get(b1)
                            bytes + b1
                        } else {
                            bytes
                        }
                        publishConfig(all, fmt)
                    }
                }
            } catch (t: Throwable) {
                if (running) Log.e(TAG, "drain", t)
                break
            }
        }
    }

    private fun publishConfig(annexB: ByteArray, format: MediaFormat = codec.outputFormat) {
        if (configSent) return
        val (sps, pps) = H264Nal.findSpsPps(annexB)
        if (sps != null && pps != null) {
            configSent = true
            onConfig(sps, pps, format)
        }
    }
}
