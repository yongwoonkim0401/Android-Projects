package com.scicalc.app.ui

/**
 * 백그라운드 계산에 넘길 스냅샷 배열 풀.
 *
 * 긴 수식은 UI 스레드에서 계산하지 않고 워커 스레드로 보낸다. 이때 갭 버퍼를 직접 넘기면
 * 사용자가 계속 타이핑하는 동안 워커가 같은 배열을 읽게 되어 경쟁 상태가 된다.
 * 그래서 UI 스레드에서 [CharArray] 로 한 번 복사(memcpy)해 넘긴다.
 *
 * 이 복사본을 매번 새로 만들면 10 만 자 기준 200KB 짜리 배열이 타이핑마다 버려진다.
 * 풀에 [maxBuffers] 개만 두고 돌려쓰면 최대 사용량이 상수로 묶인다.
 * 모두 사용 중이면 `null` 을 돌려주고, 그 틱의 미리보기는 건너뛴다(다음 입력에서 다시 계산된다).
 */
class ScratchPool(private val maxBuffers: Int = 3) {

    private val free = ArrayList<CharArray>(maxBuffers)
    private var created = 0

    @Synchronized
    fun borrow(minSize: Int): CharArray? {
        val needed = if (minSize < MIN_SIZE) MIN_SIZE else minSize
        // 충분히 큰 것이 있으면 그대로 쓴다.
        for (i in free.indices) {
            if (free[i].size >= needed) return free.removeAt(i)
        }
        // 작은 것이 있으면 키워서 쓴다(개수는 늘리지 않는다).
        if (free.isNotEmpty()) {
            free.removeAt(free.size - 1)
            return CharArray(needed)
        }
        if (created < maxBuffers) {
            created++
            return CharArray(needed)
        }
        return null
    }

    @Synchronized
    fun release(buffer: CharArray) {
        if (free.size < maxBuffers) free.add(buffer)
    }

    companion object {
        private const val MIN_SIZE = 1024
    }
}
