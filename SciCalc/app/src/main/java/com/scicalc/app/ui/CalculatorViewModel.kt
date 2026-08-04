package com.scicalc.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scicalc.app.core.AngleMode
import com.scicalc.app.core.CharArraySource
import com.scicalc.app.core.Err
import com.scicalc.app.core.EvalContext
import com.scicalc.app.core.ExpressionEngine
import com.scicalc.app.core.GapBuffer
import com.scicalc.app.core.HistoryRing
import com.scicalc.app.core.NumberFormatter
import com.scicalc.app.core.Sym
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** 화면에 필요한 상태 한 묶음. */
data class UiState(
    /** 버퍼가 바뀔 때마다 증가. 뷰는 이 값이 바뀔 때만 다시 그린다. */
    val revision: Int = 0,
    val preview: String = "",
    val errorCode: Int = Err.NONE,
    val errorPos: Int = -1,
    val angleMode: AngleMode = AngleMode.DEG,
    val secondMode: Boolean = false,
    val hasMemory: Boolean = false,
    val length: Int = 0,
    val computing: Boolean = false,
    val resultShown: Boolean = false
)

/** 워커 스레드에서 돌려받는 계산 결과 묶음. */
private class Outcome(val ok: Boolean, val value: Double, val code: Int, val pos: Int)

sealed class UiEvent {
    /** 입력 길이 상한에 도달. */
    object LimitReached : UiEvent()

    /** '=' 를 누를 때 괄호 [count] 개를 자동으로 닫음. */
    class ParensClosed(val count: Int) : UiEvent()

    /** 메모리에 저장/차감 완료. */
    class MemoryChanged(val value: Double) : UiEvent()
}

/**
 * 입력 버퍼와 계산 파이프라인을 소유한다.
 *
 * ### 계산을 어디서 돌리는가
 * - 짧은 수식([INLINE_EVAL_LIMIT] 자 이하)은 UI 스레드에서 바로 계산한다.
 *   수백 자 파싱은 수십 마이크로초라 스레드를 오가는 비용이 더 크다.
 * - 그보다 길면 [DEBOUNCE_MS] 만큼 입력이 멈추길 기다렸다가 워커 스레드에서 계산한다.
 *   타이핑 중에는 UI 스레드가 파싱을 전혀 하지 않으므로 입력이 밀리지 않는다.
 *
 * 엔진은 UI 용 1개, 워커용 1개를 따로 둔다. 서로 배열을 공유하지 않으므로 락이 필요 없고,
 * 각자 배열을 재사용하므로 계산이 반복돼도 할당이 늘지 않는다.
 */
class CalculatorViewModel : ViewModel() {

    /** 입력 버퍼. UI 스레드에서만 변형한다. */
    val buffer = GapBuffer()

    val history = HistoryRing()

    private val context = EvalContext()
    private val uiEngine = ExpressionEngine()
    private val workerEngine = ExpressionEngine()
    private val snapshotSource = CharArraySource()
    private val scratchPool = ScratchPool()
    private val historyRenderer = ExpressionRenderer()

    private val workerExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "calc-worker").apply { isDaemon = true }
    }
    private val workerDispatcher: CoroutineDispatcher = workerExecutor.asCoroutineDispatcher()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private var previewJob: Job? = null
    private var revision = 0

    /** 직전 입력이 '=' 였는지. 다음 입력의 동작을 결정한다. */
    private var justEvaluated = false

    // ------------------------------------------------------------------ 입력

    fun onKey(action: KeyAction) {
        // 2nd 는 한 번만 적용되고 풀린다(공학용 계산기 관례).
        if (action !is KeyAction.ToggleSecond && _state.value.secondMode) {
            _state.value = _state.value.copy(secondMode = false)
        }
        when (action) {
            is KeyAction.Insert -> insertText(action.text)
            is KeyAction.Func -> insertFunction(action.sentinel)
            KeyAction.Delete -> {
                justEvaluated = false
                if (buffer.backspace()) afterEdit()
            }
            KeyAction.Clear -> {
                justEvaluated = false
                buffer.clear()
                afterEdit(clearError = true)
            }
            KeyAction.CursorLeft -> {
                justEvaluated = false
                buffer.moveCursorBy(-1)
                bumpRevision()
            }
            KeyAction.CursorRight -> {
                justEvaluated = false
                buffer.moveCursorBy(1)
                bumpRevision()
            }
            KeyAction.ToggleSign -> {
                toggleSignAtCursor()
                afterEdit()
            }
            KeyAction.ToggleSecond ->
                _state.value = _state.value.copy(secondMode = !_state.value.secondMode)

            KeyAction.ToggleAngle -> {
                context.angleMode = context.angleMode.next()
                _state.value = _state.value.copy(angleMode = context.angleMode)
                scheduleEvaluation()
            }
            KeyAction.Equals -> evaluateNow()
            KeyAction.MemoryClear -> {
                context.memory = 0.0
                _state.value = _state.value.copy(hasMemory = false)
                emit(UiEvent.MemoryChanged(0.0))
            }
            KeyAction.MemoryRecall -> insertText(Sym.MEM.toString())
            KeyAction.MemoryAdd -> applyToMemory(1.0)
            KeyAction.MemorySubtract -> applyToMemory(-1.0)
        }
    }

    private fun insertText(text: String) {
        if (text.isEmpty()) return
        prepareForNewInput(startsValue = startsValue(text[0]))
        if (!buffer.insert(text)) {
            emit(UiEvent.LimitReached)
            return
        }
        afterEdit()
    }

    private fun insertFunction(sentinel: Char) {
        prepareForNewInput(startsValue = true)
        // 함수 기호와 여는 괄호를 함께 넣는다. 사용자가 괄호를 잊는 실수를 없앤다.
        // 두 글자가 모두 들어갈 수 있을 때만 넣어 반쪽짜리 입력이 남지 않게 한다.
        if (buffer.length + 2 > GapBuffer.MAX_LENGTH) {
            emit(UiEvent.LimitReached)
            return
        }
        buffer.insert(sentinel)
        buffer.insert('(')
        afterEdit()
    }

    /**
     * '=' 직후의 입력 처리.
     * - 숫자/함수처럼 값으로 시작하면 새 계산으로 본다 -> 버퍼를 비운다.
     * - 연산자로 시작하면 직전 결과에 이어서 계산한다 -> 버퍼를 Ans 로 바꾼다.
     */
    private fun prepareForNewInput(startsValue: Boolean) {
        if (!justEvaluated) return
        justEvaluated = false
        buffer.clear()
        if (!startsValue) buffer.insert(Sym.ANS.toString())
        _state.value = _state.value.copy(resultShown = false, errorCode = Err.NONE, errorPos = -1)
    }

    private fun startsValue(c: Char): Boolean =
        c in '0'..'9' || c == '.' || c == '(' || Sym.isSentinel(c)

    /**
     * 커서 바로 왼쪽 숫자의 부호를 뒤집는다.
     * 숫자가 없으면 커서 위치에 '-' 를 넣는다.
     */
    private fun toggleSignAtCursor() {
        justEvaluated = false
        val cursor = buffer.cursor
        var start = cursor
        while (start > 0) {
            val c = buffer[start - 1]
            if (c in '0'..'9' || c == '.') start-- else break
        }
        if (start > 0 && buffer[start - 1] == '-') {
            // 이미 음수면 '-' 를 제거한다(앞이 값이 아닐 때만 = 이항 뺄셈이 아닐 때).
            val before = if (start >= 2) buffer[start - 2] else ' '
            if (start == 1 || before == '(' || before == ',' || isOperatorChar(before)) {
                buffer.moveCursorTo(start)
                buffer.backspace()
                buffer.moveCursorTo(cursor - 1)
                return
            }
        }
        buffer.moveCursorTo(start)
        buffer.insert('-')
        buffer.moveCursorTo(cursor + 1)
    }

    private fun isOperatorChar(c: Char): Boolean =
        c == '+' || c == '-' || c == '*' || c == '/' || c == '^'

    /**
     * M+ / M-. 이미 계산된 결과가 있으면 그 값을 쓰고, 없으면 지금 계산한다.
     * 아주 긴 수식은 UI 를 잡지 않도록 확정 계산('=')을 먼저 하도록 유도한다.
     */
    private fun applyToMemory(sign: Double) {
        val amount: Double = when {
            _state.value.resultShown -> context.ans
            buffer.length <= INLINE_EVAL_LIMIT -> {
                if (!evaluateInline(silent = false)) return
                uiEngine.value
            }
            else -> {
                evaluateNow()
                return
            }
        }
        context.memory += sign * amount
        _state.value = _state.value.copy(hasMemory = context.memory != 0.0)
        emit(UiEvent.MemoryChanged(context.memory))
    }

    // ------------------------------------------------------------------ 계산

    private fun afterEdit(clearError: Boolean = false) {
        revision++
        _state.value = _state.value.copy(
            revision = revision,
            length = buffer.length,
            resultShown = false,
            errorCode = if (clearError) Err.NONE else _state.value.errorCode
        )
        scheduleEvaluation()
    }

    private fun bumpRevision() {
        revision++
        _state.value = _state.value.copy(revision = revision, length = buffer.length)
    }

    /**
     * 미리보기 계산을 예약한다. 짧으면 즉시, 길면 디바운스 후 워커에서.
     * 실패는 조용히 무시한다. 타이핑 중에는 미완성 수식이 정상이기 때문이다.
     */
    private fun scheduleEvaluation() {
        previewJob?.cancel()
        previewJob = null

        if (buffer.isEmpty) {
            _state.value = _state.value.copy(preview = "", computing = false)
            return
        }

        if (buffer.length <= INLINE_EVAL_LIMIT) {
            evaluateInline(silent = true)
            return
        }

        previewJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            _state.value = _state.value.copy(computing = true)

            val scratch = scratchPool.borrow(buffer.length)
            if (scratch == null) {
                // 워커가 모두 사용 중. 이번 틱은 건너뛴다(다음 입력에서 다시 계산된다).
                _state.value = _state.value.copy(computing = false)
                return@launch
            }
            try {
                val copied = buffer.copyInto(scratch) // UI 스레드에서 memcpy 한 번
                val preview = withContext(workerDispatcher) {
                    snapshotSource.chars = scratch
                    snapshotSource.len = copied
                    if (workerEngine.evaluate(snapshotSource, context, autoCloseParens = true)) {
                        NumberFormatter.format(workerEngine.value)
                    } else {
                        ""
                    }
                }
                _state.value = _state.value.copy(preview = preview, computing = false)
            } finally {
                scratchPool.release(scratch)
            }
        }
    }

    /**
     * UI 스레드에서 갭 버퍼를 **복사 없이** 그대로 읽어 계산한다.
     * @param silent 참이면 실패해도 오류를 표시하지 않는다.
     */
    private fun evaluateInline(silent: Boolean): Boolean {
        val ok = uiEngine.evaluate(buffer, context, autoCloseParens = true)
        _state.value = if (ok) {
            _state.value.copy(
                preview = NumberFormatter.format(uiEngine.value),
                errorCode = Err.NONE,
                errorPos = -1,
                computing = false
            )
        } else {
            _state.value.copy(
                preview = "",
                errorCode = if (silent) Err.NONE else uiEngine.errorCode,
                errorPos = if (silent) -1 else uiEngine.errorPos,
                computing = false
            )
        }
        return ok
    }

    /** '=' 처리. 닫히지 않은 괄호는 실제 버퍼에 채워 넣고 확정 계산한다. */
    private fun evaluateNow() {
        previewJob?.cancel()
        if (buffer.isEmpty) return

        val missing = countUnclosedParens()
        if (missing > 0) {
            val cursor = buffer.cursor
            buffer.moveCursorToEnd()
            for (i in 0 until missing) buffer.insert(')')
            buffer.moveCursorTo(cursor + missing)
            emit(UiEvent.ParensClosed(missing))
            revision++
        }

        if (buffer.length <= INLINE_EVAL_LIMIT) {
            finishEvaluation(
                ok = uiEngine.evaluate(buffer, context, autoCloseParens = false),
                value = uiEngine.value,
                errorCode = uiEngine.errorCode,
                errorPos = uiEngine.errorPos
            )
            return
        }

        // 긴 수식은 워커에서. UI 는 잠기지 않는다.
        viewModelScope.launch {
            _state.value = _state.value.copy(computing = true, revision = revision)
            val scratch = scratchPool.borrow(buffer.length)
            if (scratch == null) {
                _state.value = _state.value.copy(computing = false)
                return@launch
            }
            try {
                val copied = buffer.copyInto(scratch)
                val outcome = withContext(workerDispatcher) {
                    snapshotSource.chars = scratch
                    snapshotSource.len = copied
                    val ok = workerEngine.evaluate(snapshotSource, context, autoCloseParens = false)
                    Outcome(ok, workerEngine.value, workerEngine.errorCode, workerEngine.errorPos)
                }
                finishEvaluation(outcome.ok, outcome.value, outcome.code, outcome.pos)
            } finally {
                scratchPool.release(scratch)
            }
        }
    }

    private fun finishEvaluation(ok: Boolean, value: Double, errorCode: Int, errorPos: Int) {
        if (ok) {
            context.ans = value
            justEvaluated = true
            val text = NumberFormatter.format(value)
            history.add(historyRenderer.renderPrefix(buffer, HistoryRing.MAX_STORED_CHARS), text, value)
            _state.value = _state.value.copy(
                revision = ++revision,
                preview = text,
                errorCode = Err.NONE,
                errorPos = -1,
                resultShown = true,
                computing = false,
                length = buffer.length
            )
        } else {
            justEvaluated = false
            _state.value = _state.value.copy(
                revision = ++revision,
                preview = "",
                errorCode = errorCode,
                errorPos = errorPos,
                resultShown = false,
                computing = false,
                length = buffer.length
            )
        }
    }

    /** 열린 채로 남은 '(' 의 개수. '=' 를 누를 때만 한 번 훑는다. */
    private fun countUnclosedParens(): Int {
        var depth = 0
        for (i in 0 until buffer.length) {
            val c = buffer[i]
            if (c == '(') depth++
            else if (c == ')' && depth > 0) depth--
        }
        return depth
    }

    // ------------------------------------------------------------------ 기타

    /** 기록에서 결과를 골라 현재 커서 위치에 넣는다. 표시 문자열이 아니라 원본 값을 넣는다. */
    fun insertHistoryResult(index: Int) {
        if (index >= history.size) return
        prepareForNewInput(startsValue = true)
        buffer.insert(NumberFormatter.toInputText(history.valueAt(index)))
        afterEdit()
    }

    fun setAngleMode(mode: AngleMode) {
        context.angleMode = mode
        _state.value = _state.value.copy(angleMode = mode)
        scheduleEvaluation()
    }

    val memoryValue: Double get() = context.memory

    private fun emit(event: UiEvent) {
        _events.tryEmit(event)
    }

    override fun onCleared() {
        super.onCleared()
        workerExecutor.shutdownNow()
    }

    companion object {
        /** 이 길이까지는 UI 스레드에서 바로 계산한다. */
        const val INLINE_EVAL_LIMIT = 512

        /** 긴 수식의 미리보기를 미루는 시간. 연속 타이핑 중에는 계산이 아예 돌지 않는다. */
        const val DEBOUNCE_MS = 90L
    }
}
