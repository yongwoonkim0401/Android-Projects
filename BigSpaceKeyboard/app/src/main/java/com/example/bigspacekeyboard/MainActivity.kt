package com.example.bigspacekeyboard

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import com.example.bigspacekeyboard.databinding.ActivityMainBinding
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Setup and settings screen. The keyboard pinned to the bottom is the real [KeyboardView], so the
 * sliders can be judged by feel — it types into the test field without the IME being enabled yet.
 */
class MainActivity : AppCompatActivity(), KeyboardView.OnKeyboardActionListener {

    private lateinit var binding: ActivityMainBinding
    private var config = KeyboardConfig()
    private var lastShiftTap = 0L

    private val hangul = HangulComposer()

    /** How many characters at the cursor are still being composed (0 or 1). */
    private var composingLength = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Snap on load so a value saved by an older build lands on one of the current stops.
        config = KeyPrefs.load(this).let { it.copy(hintSizeRatio = snapHint(it.hintSizeRatio)) }

        binding.testInput.showSoftInputOnFocus = false
        binding.testInput.requestFocus()
        // Same rule as the real keyboard: tapping into the text ends the syllable being composed,
        // otherwise the next jamo would rewrite the character at the old spot.
        binding.testInput.setOnClickListener { stopComposing() }
        binding.keyboardPreview.actionListener = this
        binding.keyboardPreview.textLayer = KeyPrefs.loadTextLayer(this)
        binding.keyboardPreview.layer = binding.keyboardPreview.textLayer

        setUpControls()
        applyConfig()

        binding.enableButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        binding.pickButton.setOnClickListener {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        binding.resetButton.setOnClickListener {
            config = KeyboardConfig()
            setUpControls()
            applyConfig()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        // The keyboard's own panel may have moved things since this screen was last looked at —
        // including on the way here, since holding its bottom-left key is how you arrive.
        val stored = KeyPrefs.load(this).let { it.copy(hintSizeRatio = snapHint(it.hintSizeRatio)) }
        if (stored != config) {
            config = stored
            setUpControls()
            applyConfig()
        }
        // The activity has focus, so it may read the clipboard the same way the IME does.
        val clip = (getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip
        binding.keyboardPreview.clipboardText = clip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    // ------------------------------------------------------------- settings

    /** Values are pushed before listeners are attached so this can also serve as a reset. */
    private fun setUpControls() = with(binding) {
        spaceWidthSlider.clearOnChangeListeners()
        spaceHeightSlider.clearOnChangeListeners()
        keyHeightSlider.clearOnChangeListeners()
        hintSizeSlider.clearOnChangeListeners()
        vibrateSlider.clearOnChangeListeners()
        soundSlider.clearOnChangeListeners()
        repeatSpeedSlider.clearOnChangeListeners()
        longPressSlider.clearOnChangeListeners()

        spaceWidthSlider.value = (config.spaceWidthUnits * 10f).roundToInt()
            .coerceIn(40, 80).let { (it / 5) * 5 }.toFloat()
        spaceHeightSlider.value =
            (config.spaceHeightWeight * 10f).roundToInt().coerceIn(10, 22).toFloat()
        keyHeightSlider.value = (config.keyHeightRatio * 100f).roundToInt()
            .coerceIn(80, 140).let { (it / 5) * 5 }.toFloat()

        hintSizeSlider.value = hintStopIndex(config.hintSizeRatio).toFloat()

        vibrateSlider.value = percentStop(config.vibrateStrength)
        soundSlider.value = percentStop(config.soundVolume)
        repeatSpeedSlider.value = (config.repeatSpeed * 100f).roundToInt()
            .coerceIn(50, 200).let { (it / 25) * 25 }.toFloat()
        longPressSlider.value = config.longPressMs
            .coerceIn(150, 600).let { (it / 50) * 50 }.toFloat()

        previewSwitch.isChecked = config.previewEnabled
        swipeSwitch.isChecked = config.spaceCursorSwipe
        doubleTapSwitch.isChecked = config.doubleTapJamo
        autoCapsSwitch.isChecked = config.autoCapitalize

        spaceWidthSlider.addOnChangeListener { _, value, _ ->
            update(config.copy(spaceWidthUnits = value / 10f))
        }
        spaceHeightSlider.addOnChangeListener { _, value, _ ->
            update(config.copy(spaceHeightWeight = value / 10f))
        }
        keyHeightSlider.addOnChangeListener { _, value, _ ->
            update(config.copy(keyHeightRatio = value / 100f))
        }
        hintSizeSlider.addOnChangeListener { _, value, _ ->
            update(config.copy(hintSizeRatio = HINT_PERCENTS[value.toInt()] / 100f))
        }
        vibrateSlider.addOnChangeListener { _, value, _ ->
            update(config.copy(vibrateStrength = value / 100f))
        }
        soundSlider.addOnChangeListener { _, value, _ ->
            update(config.copy(soundVolume = value / 100f))
        }
        repeatSpeedSlider.addOnChangeListener { _, value, _ ->
            update(config.copy(repeatSpeed = value / 100f))
        }
        longPressSlider.addOnChangeListener { _, value, _ ->
            update(config.copy(longPressMs = value.roundToInt()))
        }
        previewSwitch.setOnCheckedChangeListener { _, checked ->
            update(config.copy(previewEnabled = checked))
        }
        swipeSwitch.setOnCheckedChangeListener { _, checked ->
            update(config.copy(spaceCursorSwipe = checked))
        }
        doubleTapSwitch.setOnCheckedChangeListener { _, checked ->
            update(config.copy(doubleTapJamo = checked))
        }
        autoCapsSwitch.setOnCheckedChangeListener { _, checked ->
            update(config.copy(autoCapitalize = checked))
        }
    }

    private fun update(newConfig: KeyboardConfig) {
        config = newConfig
        applyConfig()
    }

    private fun applyConfig() {
        KeyPrefs.save(this, config)
        binding.keyboardPreview.config = config
        hangul.doubleTapEnabled = config.doubleTapJamo
        binding.spaceWidthLabel.text =
            getString(R.string.label_space_width, config.spaceWidthUnits, config.spaceWidthPercent)
        binding.spaceHeightLabel.text =
            getString(R.string.label_space_height, config.spaceHeightWeight)
        binding.keyHeightLabel.text = getString(
            R.string.label_key_height, config.keyHeightRatio, keyHeightDp().roundToInt()
        )
        val hintPercent = (config.hintSizeRatio * 100f).roundToInt()
        binding.hintSizeLabel.text = if (hintPercent <= 0) {
            getString(R.string.label_hint_hidden)
        } else {
            getString(R.string.label_hint_size, hintPercent)
        }
        binding.vibrateLabel.text = percentLabel(
            config.vibrateStrength, R.string.label_vibrate, R.string.label_vibrate_off
        )
        binding.soundLabel.text = percentLabel(
            config.soundVolume, R.string.label_sound, R.string.label_sound_off
        )
        binding.repeatSpeedLabel.text = getString(
            R.string.label_repeat_speed, (config.repeatSpeed * 100f).roundToInt()
        )
        binding.longPressLabel.text =
            getString(R.string.label_long_press_ms, config.longPressMs)
        binding.totalHeightLabel.text = getString(
            R.string.label_total_height, (keyHeightDp() * config.totalWeight).roundToInt()
        )
    }

    /** 0..1 fraction to a slider stop (0, 10, ... 100). */
    private fun percentStop(fraction: Float) =
        ((fraction * 100f).roundToInt().coerceIn(0, 100) / 10) * 10f

    /**
     * The hint slider steps through [HINT_PERCENTS] rather than raw percentages: a linear 0..40%
     * slider spends its bottom half on sizes too small to read, which is useless travel.
     */
    private fun hintStopIndex(ratio: Float): Int {
        val percent = (ratio * 100f).roundToInt()
        return HINT_PERCENTS.indices.minByOrNull { abs(HINT_PERCENTS[it] - percent) } ?: 0
    }

    private fun snapHint(ratio: Float) = HINT_PERCENTS[hintStopIndex(ratio)] / 100f

    private fun percentLabel(fraction: Float, onFormat: Int, offText: Int): String {
        val percent = (fraction * 100f).roundToInt()
        return if (percent <= 0) getString(offText) else getString(onFormat, percent)
    }

    /** Mirrors KeyboardView: a key is as tall as it is wide, capped on very tall keyboards. */
    private fun keyHeightDp(): Float {
        val metrics = resources.displayMetrics
        val square = metrics.widthPixels / KeyboardLayouts.STANDARD_UNITS * config.keyHeightRatio
        val cap =
            metrics.heightPixels * KeyboardLayouts.MAX_SCREEN_FRACTION / config.totalWeight
        return min(square, cap) / metrics.density
    }

    private fun updateStatus() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val selected = Settings.Secure.getString(
            contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD
        )?.startsWith("$packageName/") == true

        binding.statusText.setText(
            when {
                !enabled -> R.string.status_not_enabled
                !selected -> R.string.status_not_selected
                else -> R.string.status_ready
            }
        )
    }

    // ------------------------------------------------- preview keyboard I/O

    override fun onKey(code: Int, output: String?) {
        val view = binding.keyboardPreview
        when (code) {
            KeyCode.SHIFT -> {
                val now = SystemClock.uptimeMillis()
                view.shiftState = when (view.shiftState) {
                    ShiftState.OFF -> ShiftState.ONE_SHOT
                    ShiftState.ONE_SHOT ->
                        if (now - lastShiftTap < 400L) ShiftState.LOCKED else ShiftState.OFF

                    ShiftState.LOCKED -> ShiftState.OFF
                }
                lastShiftTap = now
            }

            KeyCode.BACKSPACE -> {
                val step = hangul.backspace()
                if (step != null) applyComposing(step) else deleteBackward()
            }

            KeyCode.ENTER -> {
                stopComposing()
                replaceSelection("\n")
            }

            KeyCode.TO_SYMBOLS -> {
                stopComposing()
                view.layer = Layer.SYMBOLS
            }

            KeyCode.TO_PAD -> {
                stopComposing()
                view.layer = Layer.SYMBOL_PAD
            }

            KeyCode.TO_TEXT -> view.layer = view.textLayer

            KeyCode.SETTINGS -> {
                stopComposing()
                view.layer = Layer.SETTINGS
            }

            // Already in the settings app — the full list is on the screen above the keyboard.
            KeyCode.SETTINGS_APP -> {
                stopComposing()
                view.layer = view.textLayer
            }

            KeyCode.PASTE -> {
                val text = view.clipboardText ?: return
                stopComposing()
                replaceSelection(text.toString())
                view.clipboardText = null
            }

            KeyCode.LANGUAGE -> {
                stopComposing()
                val next = if (view.textLayer == Layer.HANGUL) Layer.LETTERS else Layer.HANGUL
                view.textLayer = next
                view.layer = next
                KeyPrefs.saveTextLayer(this, next)
            }

            else -> {
                val text = output ?: return
                val jamo = text.singleOrNull()
                if (view.layer == Layer.HANGUL && jamo != null && HangulComposer.isJamo(jamo)) {
                    applyComposing(hangul.input(jamo, SystemClock.uptimeMillis()))
                } else {
                    stopComposing()
                    replaceSelection(text)
                }
                if (view.shiftState == ShiftState.ONE_SHOT) view.shiftState = ShiftState.OFF
            }
        }
    }

    /** The keyboard's own settings panel moved a value; the sliders follow it. */
    override fun onConfigChange(newConfig: KeyboardConfig) {
        config = newConfig
        setUpControls()
        applyConfig()
    }

    override fun onCursorMove(steps: Int) {
        stopComposing()
        val editable = binding.testInput.text ?: return
        val target = (binding.testInput.selectionEnd + steps).coerceIn(0, editable.length)
        binding.testInput.setSelection(target)
    }

    /**
     * The preview has no InputConnection, so the syllable under construction is just the last
     * character in the field and gets rewritten in place on every jamo.
     */
    private fun applyComposing(step: HangulComposer.Result) {
        val editable = binding.testInput.text ?: return
        val end = max(0, binding.testInput.selectionEnd)
        val start = max(0, end - composingLength)
        editable.replace(start, end, step.commit + step.composing)
        composingLength = step.composing.length
    }

    private fun stopComposing() {
        hangul.reset()
        composingLength = 0
    }

    private fun replaceSelection(text: String) {
        val editable = binding.testInput.text ?: return
        val start = max(0, min(binding.testInput.selectionStart, binding.testInput.selectionEnd))
        val end = max(0, max(binding.testInput.selectionStart, binding.testInput.selectionEnd))
        editable.replace(start, end, text)
    }

    private companion object {
        /** Shared with the on-keyboard panel so both offer the same stops. */
        val HINT_PERCENTS = KeyboardSettings.HINT_PERCENTS
    }

    private fun deleteBackward() {
        val editable = binding.testInput.text ?: return
        val start = max(0, min(binding.testInput.selectionStart, binding.testInput.selectionEnd))
        val end = max(0, max(binding.testInput.selectionStart, binding.testInput.selectionEnd))
        when {
            start != end -> editable.replace(start, end, "")
            start > 0 -> editable.replace(start - 1, start, "")
        }
    }
}
