package com.scicalc.app.core

/**
 * 수식 입력창의 저장소로 쓰는 갭 버퍼(gap buffer).
 *
 * ### 왜 String / StringBuilder 가 아닌가
 * `text = text + "5"` 같은 방식은 키를 누를 때마다 길이 n 의 배열을 새로 만들고 복사한다.
 * 수식이 n 자가 될 때까지 총 O(n²) 바이트를 할당하고 그만큼 GC 쓰레기를 만든다.
 * 5,000 자 수식이면 누적 수십 MB 가 쓰레기로 지나가고, 그때마다 GC 가 UI 프레임을 갉아먹는다.
 *
 * 갭 버퍼는 **하나의 CharArray 만** 유지한다. 커서 위치에 '갭'(빈 공간)을 두고,
 * 삽입은 갭의 앞을 한 칸 먹고, 삭제는 한 칸 되돌린다. 둘 다 O(1) 이고 **할당이 없다**.
 * 커서 이동만 이동 거리에 비례한 `System.arraycopy` 를 쓰는데, 이는 네이티브 memmove 라
 * 수천 자를 옮겨도 마이크로초 단위다.
 *
 * ```
 * 내부 표현:  [ 앞부분 ][      갭      ][ 뒷부분 ]
 *             0    gapStart        gapEnd    size
 * 논리 길이 = size - (gapEnd - gapStart)
 * 커서 위치 = gapStart
 * ```
 *
 * 스레드 안전하지 않다. 메인 스레드에서만 변형해야 한다.
 */
class GapBuffer(initialCapacity: Int = DEFAULT_CAPACITY) : CharSource {

    private var buf = CharArray(if (initialCapacity < MIN_CAPACITY) MIN_CAPACITY else initialCapacity)
    private var gapStart = 0
    private var gapEnd = buf.size

    /** 입력된 문자 수(갭 제외). */
    override val length: Int get() = buf.size - (gapEnd - gapStart)

    /** 실제로 잡고 있는 배열 크기. 진단/테스트용. */
    val capacity: Int get() = buf.size

    /** 커서의 논리 위치(0..length). */
    val cursor: Int get() = gapStart

    val isEmpty: Boolean get() = length == 0

    /** 논리 인덱스로 문자를 읽는다. 분기 하나뿐이라 순차 스캔에도 충분히 빠르다. */
    override fun get(index: Int): Char =
        buf[if (index < gapStart) index else index + (gapEnd - gapStart)]

    // ---------------------------------------------------------------- 커서

    fun moveCursorTo(position: Int) {
        val target = position.coerceIn(0, length)
        if (target == gapStart) return
        if (target < gapStart) {
            // 갭을 왼쪽으로: 왼쪽 조각을 갭 뒤로 옮긴다.
            val n = gapStart - target
            System.arraycopy(buf, target, buf, gapEnd - n, n)
            gapStart = target
            gapEnd -= n
        } else {
            // 갭을 오른쪽으로: 갭 뒤 조각을 갭 앞으로 옮긴다.
            val n = target - gapStart
            System.arraycopy(buf, gapEnd, buf, gapStart, n)
            gapStart += n
            gapEnd += n
        }
    }

    fun moveCursorBy(delta: Int) = moveCursorTo(gapStart + delta)

    fun moveCursorToEnd() = moveCursorTo(length)

    // ---------------------------------------------------------------- 편집

    /** 커서 위치에 문자 하나 삽입. 갭에 여유가 있으면 할당 없이 O(1). */
    fun insert(c: Char): Boolean {
        if (length >= MAX_LENGTH) return false
        ensureGap(1)
        buf[gapStart++] = c
        return true
    }

    /** 커서 위치에 문자열 삽입. 갭 확장은 한 번만 일어난다. */
    fun insert(s: CharSequence): Boolean {
        if (length + s.length > MAX_LENGTH) return false
        ensureGap(s.length)
        for (i in 0 until s.length) buf[gapStart++] = s[i]
        return true
    }

    /** 커서 왼쪽 문자 삭제. 배열은 건드리지 않고 갭만 넓힌다. */
    fun backspace(): Boolean {
        if (gapStart == 0) return false
        gapStart--
        return true
    }

    /** 커서 오른쪽 문자 삭제. */
    fun deleteForward(): Boolean {
        if (gapEnd == buf.size) return false
        gapEnd++
        return true
    }

    /**
     * 전체 삭제. 갭 전체를 되돌리는 것이므로 O(1) 이다.
     * 다만 한 번 크게 늘어난 배열을 계속 붙들고 있지 않도록, 과도하게 크면 이때 회수한다.
     */
    fun clear() {
        gapStart = 0
        gapEnd = buf.size
        if (buf.size > TRIM_THRESHOLD) buf = CharArray(DEFAULT_CAPACITY).also { gapEnd = it.size }
    }

    /** 내용을 통째로 [text] 로 바꾼다(계산 결과 이어쓰기 등). */
    fun setContent(text: CharSequence) {
        clear()
        insert(text)
    }

    // ---------------------------------------------------------------- 읽기

    /**
     * [from], [to) 구간을 [out] 에 덧붙인다. 중간 String 을 만들지 않는다.
     * 화면에 보이는 구간만 뽑아 쓰기 위한 통로다.
     */
    fun appendRangeTo(out: StringBuilder, from: Int, to: Int) {
        val s = from.coerceIn(0, length)
        val e = to.coerceIn(s, length)
        for (i in s until e) out.append(get(i))
    }

    /**
     * 전체 내용을 [dest] 에 복사한다. 백그라운드 계산용 스냅샷 전용이다.
     * 갭 앞/뒤 두 조각을 `arraycopy` 로 옮기므로 문자 하나씩 도는 것보다 훨씬 빠르다.
     *
     * @return 복사한 문자 수. [dest] 가 작으면 -1.
     */
    fun copyInto(dest: CharArray): Int {
        val len = length
        if (dest.size < len) return -1
        System.arraycopy(buf, 0, dest, 0, gapStart)
        System.arraycopy(buf, gapEnd, dest, gapStart, buf.size - gapEnd)
        return len
    }

    /** 디버깅/영속화용. 일반 경로에서는 호출하지 않는다(String 을 새로 만들기 때문). */
    fun contentToString(): String {
        val sb = StringBuilder(length)
        appendRangeTo(sb, 0, length)
        return sb.toString()
    }

    // ---------------------------------------------------------------- 내부

    /**
     * 갭이 [needed] 만큼 없으면 배열을 1.5 배 + 여유분으로 키운다.
     * 증가폭이 기하급수라 삽입 한 번의 **분할상환 비용이 O(1)** 이다.
     */
    private fun ensureGap(needed: Int) {
        if (gapEnd - gapStart >= needed) return
        val len = length
        var newCap = buf.size
        while (newCap - len < needed + GAP_SLACK) {
            newCap += (newCap shr 1) + GAP_SLACK
        }
        val nb = CharArray(newCap)
        val tail = buf.size - gapEnd
        System.arraycopy(buf, 0, nb, 0, gapStart)
        System.arraycopy(buf, gapEnd, nb, newCap - tail, tail)
        buf = nb
        gapEnd = newCap - tail
    }

    companion object {
        /** 초기 용량. 일반적인 수식은 이 안에서 끝나 재할당이 한 번도 일어나지 않는다. */
        const val DEFAULT_CAPACITY = 256
        const val MIN_CAPACITY = 32

        /** 새로 확보할 때 미리 잡아두는 갭 여유분. 연속 입력 시 재할당 빈도를 낮춘다. */
        const val GAP_SLACK = 64

        /** clear() 시 이보다 크면 배열을 놓아준다(장시간 사용 후 메모리 반환). */
        const val TRIM_THRESHOLD = 8 * 1024

        /** 입력 상한. 넘어가면 조용히 무시해 OOM 대신 정상 동작을 택한다. */
        const val MAX_LENGTH = 100_000
    }
}
