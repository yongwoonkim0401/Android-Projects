package com.example.androidcctv

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * 인코더가 이미 만들어 놓은 H.264 샘플을 MediaMuxer 로 **일반 MP4** 로 저장한다.
 * 다시 인코딩하지 않으므로 녹화를 켜도 CPU 부담이 거의 늘지 않는다.
 * 소리는 담지 않는다(카메라 사용 조합을 두 개로 유지하기 위해 마이크 경로를 쓰지 않음).
 */
class Mp4Recorder(val file: File, private val format: MediaFormat, private val rotation: Int) {

    companion object {
        private const val TAG = "CctvRecorder"
    }

    private val lock = Object()
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var started = false
    private var closed = false
    private var firstPts = -1L
    private var frames = 0

    @Volatile var bytes = 0L; private set

    fun open(): Boolean {
        synchronized(lock) {
            return try {
                val m = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                try {
                    m.setOrientationHint(((rotation % 360) + 360) % 360)
                } catch (ignored: Throwable) {
                }
                track = m.addTrack(format)
                m.start()
                muxer = m
                started = true
                true
            } catch (t: Throwable) {
                Log.e(TAG, "muxer 시작 실패", t)
                try {
                    muxer?.release()
                } catch (ignored: Throwable) {
                }
                muxer = null
                false
            }
        }
    }

    /** 첫 키프레임 이전 샘플은 버린다(그 지점부터 재생 가능한 파일이 된다). */
    fun write(annexB: ByteArray, ptsUs: Long, key: Boolean) {
        synchronized(lock) {
            val m = muxer ?: return
            if (!started || closed) return
            if (firstPts < 0) {
                if (!key) return
                firstPts = ptsUs
            }
            try {
                val info = MediaCodec.BufferInfo()
                info.offset = 0
                info.size = annexB.size
                info.presentationTimeUs = (ptsUs - firstPts).coerceAtLeast(0L)
                info.flags = if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                m.writeSampleData(track, ByteBuffer.wrap(annexB), info)
                bytes += annexB.size
                frames++
            } catch (t: Throwable) {
                Log.e(TAG, "샘플 기록 실패", t)
            }
        }
    }

    /** @return 저장된 파일(내용이 없으면 null) */
    fun close(): File? {
        synchronized(lock) {
            if (closed) return if (frames > 0) file else null
            closed = true
            val m = muxer ?: return null
            muxer = null
            return try {
                if (frames > 0) {
                    m.stop()
                    m.release()
                    file
                } else {
                    // 프레임이 하나도 없으면 stop() 이 예외를 던지고 파일도 쓸모없다.
                    m.release()
                    file.delete()
                    null
                }
            } catch (t: Throwable) {
                Log.e(TAG, "muxer 종료 실패", t)
                try {
                    m.release()
                } catch (ignored: Throwable) {
                }
                if (frames > 0) file else null
            }
        }
    }
}
