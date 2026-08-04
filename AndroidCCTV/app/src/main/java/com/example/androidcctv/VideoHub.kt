package com.example.androidcctv

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * H.264 조각을 시청자에게 나눠 준다.
 *
 * MJPEG 와 달리 조각을 임의로 버리면 디코딩이 깨지므로 **시청자마다 큐를 따로** 둔다.
 * 네트워크가 느려 큐가 밀리면 그 시청자만 비우고 다음 키프레임부터 다시 붙인다.
 */
object VideoHub {

    class Fragment(val seq: Long, val data: ByteArray, val key: Boolean, val at: Long)

    /** 시청자 1명 = 큐 1개 */
    class Sub {
        val queue = LinkedBlockingQueue<Fragment>(300)

        /** true 면 다음 키프레임까지 조각을 받지 않는다(붙는 시점 / 밀린 뒤 복구) */
        @Volatile
        var resync = true

        fun poll(timeoutMs: Long): Fragment? = try {
            queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private const val QUEUE_LIMIT = 150      // 약 5초분(30fps)

    private val subs = CopyOnWriteArrayList<Sub>()
    private var seqCounter = 0L

    val viewers = AtomicInteger(0)

    @Volatile var initSegment: ByteArray? = null; private set
    @Volatile var codecString: String? = null; private set
    @Volatile var width = 0; private set
    @Volatile var height = 0; private set
    @Volatile var kbps = 0; private set
    @Volatile var lastFragmentAt = 0L; private set

    /** 키프레임이 필요할 때 인코더를 찔러 주는 콜백(CameraController 가 설정) */
    @Volatile
    var keyFrameRequester: (() -> Unit)? = null

    /** 녹화 중이면 원본 Annex-B 를 함께 받아 갈 대상 */
    @Volatile
    var recorder: Mp4Recorder? = null

    private var windowBytes = 0L
    private var windowStart = 0L

    fun setInit(bytes: ByteArray, codec: String, w: Int, h: Int) {
        initSegment = bytes
        codecString = codec
        width = w
        height = h
    }

    val ready: Boolean get() = initSegment != null && codecString != null

    fun publish(data: ByteArray, key: Boolean) {
        val now = System.currentTimeMillis()
        lastFragmentAt = now
        seqCounter++
        val frag = Fragment(seqCounter, data, key, now)

        if (windowStart == 0L) windowStart = now
        windowBytes += data.size
        val elapsed = now - windowStart
        if (elapsed >= 2000) {
            kbps = ((windowBytes * 8.0) / elapsed).toInt()   // bytes/ms*8 = kbit/s
            windowBytes = 0
            windowStart = now
        }

        var needKey = false
        for (s in subs) {
            if (s.queue.size >= QUEUE_LIMIT) {
                // 이 시청자는 따라오지 못하고 있다 → 비우고 다음 키프레임부터 재동기화
                s.queue.clear()
                s.resync = true
                needKey = true
            }
            if (s.resync) {
                if (!key) continue
                s.resync = false
            }
            if (!s.queue.offer(frag)) {
                s.queue.clear()
                s.resync = true
                needKey = true
            }
        }
        if (needKey) requestKeyFrame()
    }

    fun subscribe(): Sub {
        val s = Sub()
        subs.add(s)
        viewers.incrementAndGet()
        requestKeyFrame()
        return s
    }

    fun unsubscribe(s: Sub) {
        if (subs.remove(s)) viewers.decrementAndGet()
        s.queue.clear()
    }

    /** 시청자가 모두 나가 조각 생성을 멈췄을 때 표시용 값 정리 */
    fun markIdle() {
        kbps = 0
        windowBytes = 0
        windowStart = 0
    }

    fun requestKeyFrame() {
        try {
            keyFrameRequester?.invoke()
        } catch (ignored: Throwable) {
        }
    }

    fun reset() {
        initSegment = null
        codecString = null
        width = 0
        height = 0
        kbps = 0
        windowBytes = 0
        windowStart = 0
        seqCounter = 0
        for (s in subs) {
            s.queue.clear()
            s.resync = true
        }
    }
}
