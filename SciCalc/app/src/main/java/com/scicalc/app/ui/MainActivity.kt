package com.scicalc.app.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.gridlayout.widget.GridLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.scicalc.app.R
import com.scicalc.app.core.Err
import com.scicalc.app.core.NumberFormatter
import com.scicalc.app.databinding.ActivityMainBinding
import com.scicalc.app.databinding.ItemHistoryBinding
import kotlinx.coroutines.launch

/**
 * 계산기 화면.
 *
 * 뷰는 40 개 키 버튼을 **한 번만** 만들고 이후에는 라벨/색만 바꾼다.
 * 수식은 [ExpressionRenderer] 가 잘라준 커서 주변 구간만 그리므로,
 * 버퍼에 10 만 자가 있어도 TextView 가 다루는 문자열은 100 자 남짓으로 고정된다.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: CalculatorViewModel by viewModels()
    private val renderer = ExpressionRenderer()

    private val keyButtons = ArrayList<MaterialButton>(Keys.table.size)
    private val handler = Handler(Looper.getMainLooper())

    private var lastRenderedRevision = -1
    private var lastSecondMode = false
    private var caretColor = 0

    private val deleteRepeater = object : Runnable {
        override fun run() {
            viewModel.onKey(KeyAction.Delete)
            handler.postDelayed(this, DELETE_REPEAT_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        caretColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)

        buildKeypad()
        bindToolbar()
        observeState()

        renderExpression()
    }

    override fun onDestroy() {
        handler.removeCallbacks(deleteRepeater)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 구성

    private fun buildKeypad() {
        val grid = binding.gridKeypad
        val margin = resources.getDimensionPixelSize(R.dimen.key_margin)
        val keyHeight = resources.getDimensionPixelSize(R.dimen.key_height)

        for (index in Keys.table.indices) {
            val row = index / Keys.COLUMNS
            val column = index % Keys.COLUMNS

            val button = layoutInflater.inflate(R.layout.item_key, grid, false) as MaterialButton
            // 열은 가중치로 화면 폭을 나눠 갖고, 행은 고정 높이를 쓴다.
            // 그래서 키 높이가 화면 크기와 무관하게 항상 @dimen/key_height 로 유지된다.
            val params = GridLayout.LayoutParams(
                GridLayout.spec(row, 1),
                GridLayout.spec(column, 1, 1f)
            ).apply {
                width = 0
                height = keyHeight
                setMargins(margin, margin, margin, margin)
            }
            button.layoutParams = params

            val keyIndex = index
            button.setOnClickListener { view ->
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                viewModel.onKey(Keys.table[keyIndex].face(viewModel.state.value.secondMode).action)
            }

            keyButtons.add(button)
            grid.addView(button)
            applyFace(button, Keys.table[index].face(false))
        }

        attachDeleteRepeat()
    }

    /** DEL 을 길게 누르면 반복 삭제. */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDeleteRepeat() {
        val deleteIndex = Keys.table.indexOfFirst { it.primary.action == KeyAction.Delete }
        if (deleteIndex < 0) return
        val button = keyButtons[deleteIndex]
        button.setOnLongClickListener {
            handler.post(deleteRepeater)
            true
        }
        button.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                handler.removeCallbacks(deleteRepeater)
            }
            false
        }
    }

    private fun bindToolbar() {
        binding.buttonAngleMode.setOnClickListener { viewModel.onKey(KeyAction.ToggleAngle) }
        binding.buttonMemClear.setOnClickListener { viewModel.onKey(KeyAction.MemoryClear) }
        binding.buttonMemRecall.setOnClickListener { viewModel.onKey(KeyAction.MemoryRecall) }
        binding.buttonMemAdd.setOnClickListener { viewModel.onKey(KeyAction.MemoryAdd) }
        binding.buttonMemSubtract.setOnClickListener { viewModel.onKey(KeyAction.MemorySubtract) }
        binding.buttonHistory.setOnClickListener { showHistory() }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { render(it) }
                }
                launch {
                    viewModel.events.collect { handleEvent(it) }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 표시

    private fun render(state: UiState) {
        if (state.revision != lastRenderedRevision) {
            lastRenderedRevision = state.revision
            renderExpression()
        }

        if (state.secondMode != lastSecondMode) {
            lastSecondMode = state.secondMode
            for (i in keyButtons.indices) {
                applyFace(keyButtons[i], Keys.table[i].face(state.secondMode))
            }
        }

        binding.buttonAngleMode.text = state.angleMode.label
        binding.textMemoryIndicator.visibility = if (state.hasMemory) View.VISIBLE else View.INVISIBLE

        if (state.errorCode != Err.NONE) {
            binding.textPreview.text = errorMessage(state.errorCode, state.errorPos)
            binding.textPreview.setTextColor(
                MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorError)
            )
        } else {
            binding.textPreview.text = if (state.preview.isEmpty()) {
                ""
            } else if (state.resultShown) {
                "= " + state.preview
            } else {
                state.preview
            }
            binding.textPreview.setTextColor(
                MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
            )
        }

        binding.textStatus.text = when {
            state.computing -> getString(R.string.computing)
            state.length > 0 -> getString(R.string.char_count, state.length)
            else -> ""
        }
    }

    /**
     * 커서 주변 구간만 다시 그린다.
     * 만들어지는 문자열은 항상 100 자 안팎이라, 수식 길이와 무관하게 비용이 일정하다.
     */
    private fun renderExpression() {
        val text = renderer.render(viewModel.buffer, showCaret = true)
        val spannable = SpannableString(text)
        val caret = renderer.caretIndex
        if (caret in 0 until spannable.length) {
            spannable.setSpan(
                ForegroundColorSpan(caretColor),
                caret,
                caret + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        binding.textExpression.text = spannable
    }

    private fun errorMessage(code: Int, position: Int): String {
        val base = getString(
            when (code) {
                Err.SYNTAX -> R.string.err_syntax
                Err.UNBALANCED -> R.string.err_unbalanced
                Err.BAD_CHAR -> R.string.err_bad_char
                Err.DIV_ZERO -> R.string.err_div_zero
                Err.DOMAIN -> R.string.err_domain
                Err.OVERFLOW -> R.string.err_overflow
                Err.ARITY -> R.string.err_arity
                Err.EMPTY -> R.string.err_empty
                else -> R.string.err_too_complex
            }
        )
        return if (position >= 0) getString(R.string.err_at_position, base, position + 1) else base
    }

    private fun handleEvent(event: UiEvent) {
        val message = when (event) {
            UiEvent.LimitReached -> getString(R.string.limit_reached)
            is UiEvent.ParensClosed -> getString(R.string.parens_closed, event.count)
            is UiEvent.MemoryChanged -> getString(
                R.string.memory_changed,
                NumberFormatter.format(event.value)
            )
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun applyFace(button: MaterialButton, face: KeyFace) {
        button.text = face.label

        // 함수 키만 테마 색이 아닌 고정 연두색을 쓴다(다크 모드는 values-night 에서 대체됨).
        if (face.style == KeyStyle.FUNCTION) {
            button.setBackgroundColor(ContextCompat.getColor(this, R.color.key_function_background))
            button.setTextColor(ContextCompat.getColor(this, R.color.key_function_text))
            return
        }

        val backgroundAttr: Int
        val textAttr: Int
        when (face.style) {
            KeyStyle.OPERATOR -> {
                backgroundAttr = com.google.android.material.R.attr.colorSecondaryContainer
                textAttr = com.google.android.material.R.attr.colorOnSecondaryContainer
            }
            KeyStyle.ACCENT -> {
                backgroundAttr = com.google.android.material.R.attr.colorPrimary
                textAttr = com.google.android.material.R.attr.colorOnPrimary
            }
            KeyStyle.WARN -> {
                backgroundAttr = com.google.android.material.R.attr.colorErrorContainer
                textAttr = com.google.android.material.R.attr.colorOnErrorContainer
            }
            else -> { // KeyStyle.DIGIT
                backgroundAttr = com.google.android.material.R.attr.colorSurfaceVariant
                textAttr = com.google.android.material.R.attr.colorOnSurface
            }
        }
        button.setBackgroundColor(MaterialColors.getColor(button, backgroundAttr))
        button.setTextColor(MaterialColors.getColor(button, textAttr))
    }

    // ------------------------------------------------------------------ 기록

    private fun showHistory() {
        val history = viewModel.history
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        if (history.size == 0) {
            container.addView(TextView(this).apply {
                text = getString(R.string.history_empty)
                setPadding(60, 40, 60, 40)
            })
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.history_title)
            .setView(ScrollView(this).apply { addView(container) })
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.history_clear) { _, _ -> history.clear() }
            .create()

        for (i in 0 until history.size) {
            val item = ItemHistoryBinding.inflate(layoutInflater, container, false)
            item.textHistoryExpression.text = history.expressionAt(i)
            item.textHistoryResult.text = "= " + history.resultAt(i)
            item.root.setOnClickListener {
                viewModel.insertHistoryResult(i)
                dialog.dismiss()
            }
            container.addView(item.root)
        }
        dialog.show()
    }

    companion object {
        private const val DELETE_REPEAT_MS = 55L
    }
}
