package com.example.androidcctv

import java.util.concurrent.atomic.AtomicInteger

/**
 * 카메라가 만든 최신 JPEG 한 장을 보관하고, 접속한 모든 클라이언트에게 나눠 준다.
 * 프레임은 복사하지 않고 참조만 공유한다(생성 후 변경하지 않음).
 *
 * MJPEG 은 프레임을 건너뛰어도 되므로(모든 프레임이 독립적인 이미지) 최신 한 장만 들고 있으면 된다.
 * H.264 는 그럴 수 없어서 [VideoHub] 가 시청자별 큐를 따로 관리한다.
 */
object StreamHub {

    class Frame(val seq: Long, val data: ByteArray, val at: Long)

    /** MJPEG 시청자 수. 0 이면 JPEG 인코딩 자체를 건너뛰어 CPU 를 아낀다. */
    val viewers = AtomicInteger(0)

    private val lock = Object()
    private var frame: Frame? = null
    private var seq = 0L

    /** 최근 프레임 간격으로 계산한 실제 FPS */
    @Volatile
    var fps: Double = 0.0
        private set

    private var lastAt = 0L

    fun publish(jpeg: ByteArray) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (lastAt != 0L) {
                val dt = (now - lastAt).coerceAtLeast(1L)
                val inst = 1000.0 / dt
                fps = if (fps == 0.0) inst else fps * 0.8 + inst * 0.2
            }
            lastAt = now
            seq++
            frame = Frame(seq, jpeg, now)
            lock.notifyAll()
        }
    }

    fun latest(): Frame? {
        synchronized(lock) { return frame }
    }

    fun lastFrameAgeMs(): Long {
        synchronized(lock) {
            val f = frame ?: return -1L
            return System.currentTimeMillis() - f.at
        }
    }

    /** afterSeq 보다 새로운 프레임을 기다린다. 시간 초과 시 null. */
    fun waitForNext(afterSeq: Long, timeoutMs: Long): Frame? {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (true) {
                val f = frame
                if (f != null && f.seq > afterSeq) return f
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0L) break
                try {
                    lock.wait(remain)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        return null
    }

    fun reset() {
        synchronized(lock) {
            frame = null
            fps = 0.0
            lastAt = 0L
            lock.notifyAll()
        }
    }
}
