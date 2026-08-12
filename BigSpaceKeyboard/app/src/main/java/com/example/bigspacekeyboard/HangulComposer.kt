package com.example.bigspacekeyboard

/**
 * 두벌식 한글 오토마타. 자모를 하나씩 받아 조합 중인 음절을 만들고, 음절이 끝나면 확정할 문자열을
 * 함께 돌려준다. 입력기 쪽에서는 [Result.commit]을 커밋한 뒤 [Result.composing]을 조합 문자열로
 * 세팅하면 된다.
 *
 * 상태는 초성/중성/종성 인덱스 세 개가 전부다. 종성이 있는 상태에서 모음이 들어오면 종성(겹받침이면
 * 뒷자음)을 떼어 다음 음절의 초성으로 넘긴다.
 */
class HangulComposer {

    data class Result(val commit: String, val composing: String)

    private var cho = -1
    private var jung = -1
    private var jong = 0

    /** 같은 키를 연달아 두 번 눌러 쌍자음/중모음을 만들지 여부. */
    var doubleTapEnabled = true

    private var lastJamo: Char? = null
    private var lastJamoAt = 0L

    val isComposing: Boolean get() = cho >= 0 || jung >= 0 || jong > 0

    fun reset() {
        cho = -1
        jung = -1
        jong = 0
        lastJamo = null
    }

    /** 조합 중이던 글자를 확정하고 상태를 비운다. */
    fun flush(): String {
        val text = composingText()
        reset()
        return text
    }

    /**
     * 자모 하나를 넣는다. [atMillis]를 주면 같은 키를 [DOUBLE_TAP_WINDOW_MS] 안에 다시 눌렀을 때
     * 앞서 넣은 자모를 쌍자음/중모음으로 바꿔치기한다(ㅅㅅ→ㅆ, ㅐㅐ→ㅒ).
     *
     * 연타로 한 번 바뀐 뒤에는 추적을 지운다. 그래서 세 번째 타건은 다시 홑자모가 되고,
     * `있습니다`(ㅇㅣㅅㅅㅅㅡㅂ…)처럼 쌍받침 뒤에 같은 자음이 초성으로 오는 입력이 가능하다.
     */
    fun input(jamo: Char, atMillis: Long = NO_TIME): Result {
        val doubled = DOUBLE_TAP[jamo]
        val vowel = JUNG.indexOf(jamo) >= 0

        // Vowels double unconditionally: ㅑㅕㅛㅠ have no key of their own, and a vowel repeated
        // straight after itself is never anything else — Korean spelling always puts a consonant
        // (at least ㅇ) in front of a syllable's vowel. Consonants are the ambiguous case, so they
        // keep both the time window and the user's switch.
        val eligible = doubled != null && isComposing && lastJamo == jamo
        val isDoubleTap = eligible && if (vowel) {
            true
        } else {
            doubleTapEnabled &&
                atMillis != NO_TIME &&
                atMillis - lastJamoAt in 0 until DOUBLE_TAP_WINDOW_MS
        }

        if (isDoubleTap) {
            removeLastPiece()
            lastJamo = null
            return compose(doubled!!)
        }

        // Recorded after composing, not before: composing a syllable break runs reset(), which
        // clears the tracking. Setting it first would silently disable the double tap whenever
        // the previous jamo had just started a new syllable.
        val result = compose(jamo)
        lastJamo = jamo
        lastJamoAt = atMillis
        return result
    }

    private fun compose(jamo: Char): Result =
        if (JUNG.indexOf(jamo) >= 0) inputVowel(jamo) else inputConsonant(jamo)

    private fun inputConsonant(c: Char): Result {
        // 아직 아무것도 없으면 초성으로 시작
        if (!isComposing) {
            cho = CHO.indexOf(c)
            return Result("", composingText())
        }

        // 중성이 없는 상태(자음만) — 앞 자음을 확정하고 새로 시작
        if (jung < 0) return restartWithCho(c)

        // 초성 없이 모음만 있던 상태 — 모음을 확정하고 새로 시작
        if (cho < 0) return restartWithCho(c)

        if (jong == 0) {
            val candidate = JONG.indexOf(c)
            // ㄸ·ㅃ·ㅉ 은 받침이 될 수 없어 새 음절의 초성이 된다
            if (candidate > 0) {
                jong = candidate
                return Result("", composingText())
            }
            return restartWithCho(c)
        }

        // 이미 받침이 있으면 겹받침을 시도
        val combined = JONG_COMPOSE["${JONG[jong]}$c"]
        if (combined != null) {
            jong = JONG.indexOf(combined)
            return Result("", composingText())
        }
        return restartWithCho(c)
    }

    private fun inputVowel(v: Char): Result {
        if (jong != 0) {
            // 받침을 떼어 다음 음절의 초성으로 넘긴다 (겹받침이면 뒷자음만)
            val tail = JONG[jong]
            val split = JONG_DECOMPOSE[tail]
            val moved: Char
            if (split != null) {
                jong = JONG.indexOf(split.first)
                moved = split.second
            } else {
                jong = 0
                moved = tail
            }
            val commit = composingText()
            reset()
            cho = CHO.indexOf(moved)
            jung = JUNG.indexOf(v)
            return Result(commit, composingText())
        }

        if (jung >= 0) {
            val combined = VOWEL_COMPOSE["${JUNG[jung]}$v"]
            if (combined != null) {
                jung = JUNG.indexOf(combined)
                return Result("", composingText())
            }
            val commit = composingText()
            reset()
            jung = JUNG.indexOf(v)
            return Result(commit, composingText())
        }

        jung = JUNG.indexOf(v)
        return Result("", composingText())
    }

    private fun restartWithCho(c: Char): Result {
        val commit = composingText()
        reset()
        cho = CHO.indexOf(c)
        return Result(commit, composingText())
    }

    /** 조합 중이면 한 조각만 지우고 새 조합 문자열을 돌려준다. 조합 중이 아니면 null. */
    fun backspace(): Result? {
        if (!isComposing) return null
        lastJamo = null
        removeLastPiece()
        return Result("", composingText())
    }

    /** 종성 → 중성 → 초성 순으로 마지막 한 조각만 떼어낸다. */
    private fun removeLastPiece() {
        when {
            jong != 0 -> {
                val split = JONG_DECOMPOSE[JONG[jong]]
                jong = if (split != null) JONG.indexOf(split.first) else 0
            }

            jung >= 0 -> {
                val split = VOWEL_DECOMPOSE[JUNG[jung]]
                jung = if (split != null) JUNG.indexOf(split.first) else -1
            }

            else -> cho = -1
        }
    }

    private fun composingText(): String = when {
        cho >= 0 && jung >= 0 -> {
            val code = SYLLABLE_BASE + (cho * JUNG.length + jung) * JONG.length + jong
            code.toChar().toString()
        }

        cho >= 0 -> CHO[cho].toString()
        jung >= 0 -> JUNG[jung].toString()
        else -> ""
    }

    companion object {
        private const val SYLLABLE_BASE = 0xAC00

        /** [input]에 시각을 주지 않았다는 표시. 연타 판정을 건너뛴다. */
        const val NO_TIME = Long.MIN_VALUE

        /** 연타로 인정하는 간격. 짧게 잡아야 `학교`·`웃습`처럼 받침과 초성이 같은 입력이 덜 깨진다. */
        const val DOUBLE_TAP_WINDOW_MS = 300L

        /** 같은 키를 두 번 눌렀을 때 바뀌는 자모. Shift로도 똑같이 낼 수 있다. */
        private val DOUBLE_TAP = mapOf(
            'ㄱ' to 'ㄲ', 'ㄷ' to 'ㄸ', 'ㅂ' to 'ㅃ', 'ㅅ' to 'ㅆ', 'ㅈ' to 'ㅉ',
            'ㅏ' to 'ㅑ', 'ㅓ' to 'ㅕ', 'ㅗ' to 'ㅛ', 'ㅜ' to 'ㅠ',
            'ㅐ' to 'ㅒ', 'ㅔ' to 'ㅖ',
        )

        /** 호환 자모(U+3131~U+3163) 기준 표. */
        const val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
        const val JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"

        /** 0번은 받침 없음 자리. */
        private const val JONG = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"

        private val VOWEL_COMPOSE = mapOf(
            "ㅗㅏ" to 'ㅘ', "ㅗㅐ" to 'ㅙ', "ㅗㅣ" to 'ㅚ',
            "ㅜㅓ" to 'ㅝ', "ㅜㅔ" to 'ㅞ', "ㅜㅣ" to 'ㅟ',
            "ㅡㅣ" to 'ㅢ',
        )

        private val VOWEL_DECOMPOSE = mapOf(
            'ㅘ' to ('ㅗ' to 'ㅏ'), 'ㅙ' to ('ㅗ' to 'ㅐ'), 'ㅚ' to ('ㅗ' to 'ㅣ'),
            'ㅝ' to ('ㅜ' to 'ㅓ'), 'ㅞ' to ('ㅜ' to 'ㅔ'), 'ㅟ' to ('ㅜ' to 'ㅣ'),
            'ㅢ' to ('ㅡ' to 'ㅣ'),
        )

        private val JONG_COMPOSE = mapOf(
            "ㄱㅅ" to 'ㄳ', "ㄴㅈ" to 'ㄵ', "ㄴㅎ" to 'ㄶ',
            "ㄹㄱ" to 'ㄺ', "ㄹㅁ" to 'ㄻ', "ㄹㅂ" to 'ㄼ', "ㄹㅅ" to 'ㄽ',
            "ㄹㅌ" to 'ㄾ', "ㄹㅍ" to 'ㄿ', "ㄹㅎ" to 'ㅀ',
            "ㅂㅅ" to 'ㅄ',
        )

        // ㄲ·ㅆ 은 한 번의 타건(Shift)으로 들어오므로 분해하지 않는다.
        private val JONG_DECOMPOSE = mapOf(
            'ㄳ' to ('ㄱ' to 'ㅅ'), 'ㄵ' to ('ㄴ' to 'ㅈ'), 'ㄶ' to ('ㄴ' to 'ㅎ'),
            'ㄺ' to ('ㄹ' to 'ㄱ'), 'ㄻ' to ('ㄹ' to 'ㅁ'), 'ㄼ' to ('ㄹ' to 'ㅂ'),
            'ㄽ' to ('ㄹ' to 'ㅅ'), 'ㄾ' to ('ㄹ' to 'ㅌ'), 'ㄿ' to ('ㄹ' to 'ㅍ'),
            'ㅀ' to ('ㄹ' to 'ㅎ'), 'ㅄ' to ('ㅂ' to 'ㅅ'),
        )

        /** 두벌식 자판에서 나올 수 있는 자모인지. */
        fun isJamo(c: Char): Boolean = CHO.indexOf(c) >= 0 || JUNG.indexOf(c) >= 0
    }
}
