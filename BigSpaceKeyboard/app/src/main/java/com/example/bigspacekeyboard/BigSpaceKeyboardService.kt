package com.example.bigspacekeyboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/** The input method itself; all rendering and gesture work lives in [KeyboardView]. */
class BigSpaceKeyboardService : InputMethodService(), KeyboardView.OnKeyboardActionListener {

    private lateinit var keyboardView: KeyboardView
    private val hangul = HangulComposer()
    private var lastShiftTap = 0L

    private var clipboard: ClipboardManager? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { refreshClipboard() }
    private var listeningToClipboard = false
    private var savedSymbolPage = -1

    override fun onCreate() {
        super.onCreate()
        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }

    override fun onDestroy() {
        stopListeningToClipboard()
        super.onDestroy()
    }

    /**
     * The listener is only attached while the keyboard is on screen.
     *
     * This service is alive for as long as it is the selected input method — hours, usually — and
     * the callback fires whenever *any* app on the phone copies anything. Listening the whole time
     * would rebuild the key layout on every copy made anywhere, for a keyboard nobody is looking at.
     */
    override fun onWindowShown() {
        super.onWindowShown()
        if (!listeningToClipboard) {
            clipboard?.addPrimaryClipChangedListener(clipListener)
            listeningToClipboard = true
        }
        refreshClipboard()
    }

    override fun onWindowHidden() {
        stopListeningToClipboard()
        super.onWindowHidden()
    }

    private fun stopListeningToClipboard() {
        if (!listeningToClipboard) return
        clipboard?.removePrimaryClipChangedListener(clipListener)
        listeningToClipboard = false
    }

    override fun onCreateInputView(): View {
        keyboardView = KeyboardView(this).apply {
            actionListener = this@BigSpaceKeyboardService
            config = currentConfig()
            textLayer = KeyPrefs.loadTextLayer(this@BigSpaceKeyboardService)
            layer = textLayer
            symbolPage = KeyPrefs.loadSymbolPage(this@BigSpaceKeyboardService)
        }
        savedSymbolPage = keyboardView.symbolPage
        return keyboardView
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // Only when it actually moved: this runs every time the keyboard is dismissed.
        if (::keyboardView.isInitialized && keyboardView.symbolPage != savedSymbolPage) {
            savedSymbolPage = keyboardView.symbolPage
            KeyPrefs.saveSymbolPage(this, savedSymbolPage)
        }
        super.onFinishInputView(finishingInput)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        hangul.reset()
        val config = currentConfig()
        hangul.doubleTapEnabled = config.doubleTapJamo
        keyboardView.config = config
        keyboardView.layer = keyboardView.textLayer
        keyboardView.shiftState = ShiftState.OFF
        refreshClipboard()
        updateShiftState()
    }

    override fun onFinishInput() {
        hangul.reset()
        super.onFinishInput()
    }

    // ------------------------------------------------------------- clipboard

    /**
     * An IME is one of the few things allowed to read the clipboard without being the focused
     * app, so the strip can be filled in before the user does anything.
     */
    private fun refreshClipboard() {
        if (!::keyboardView.isInitialized) return
        keyboardView.clipboardText = readClipboard()
    }

    private fun readClipboard(): CharSequence? {
        if (isPasswordField()) return null
        val clip = clipboard?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        // Anything the source app flagged as sensitive (a password manager, say) stays hidden.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            clip.description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
        ) {
            return null
        }
        return clip.getItemAt(0).text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun isPasswordField(): Boolean {
        val inputType = currentInputEditorInfo?.inputType ?: return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD

            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        // While we compose, the editor reports our composing span. If it is gone but we still
        // think we are composing, the cursor was moved from outside — drop the half-built syllable
        // so the next jamo does not get pasted back at the old spot.
        if (hangul.isComposing && candidatesStart < 0) hangul.reset()
        updateShiftState()
    }

    // Landscape needs no special case: key height follows key width, and KeyboardView caps the
    // total at a fraction of the display height, so a wide screen flattens the keys by itself.
    private fun currentConfig(): KeyboardConfig = KeyPrefs.load(this)

    // -------------------------------------------------------------- key input

    override fun onKey(code: Int, output: String?) {
        val ic = currentInputConnection ?: return
        when (code) {
            KeyCode.SHIFT -> toggleShift()

            KeyCode.BACKSPACE -> backspace(ic)

            KeyCode.ENTER -> {
                finishHangul(ic)
                performEnter()
            }

            KeyCode.PASTE -> {
                val text = keyboardView.clipboardText ?: return
                finishHangul(ic)
                ic.commitText(text, 1)
                // The strip has done its job; it comes back on the next clip or the next field.
                keyboardView.clipboardText = null
                updateShiftState()
            }

            KeyCode.TO_SYMBOLS -> {
                finishHangul(ic)
                keyboardView.layer = Layer.SYMBOLS
            }

            KeyCode.TO_PAD -> {
                finishHangul(ic)
                keyboardView.layer = Layer.SYMBOL_PAD
            }

            KeyCode.TO_TEXT -> {
                keyboardView.layer = keyboardView.textLayer
                updateShiftState()
            }

            KeyCode.SETTINGS -> {
                finishHangul(ic)
                keyboardView.layer = Layer.SETTINGS
            }

            KeyCode.SETTINGS_APP -> {
                finishHangul(ic)
                openSettingsApp()
            }

            KeyCode.LANGUAGE -> {
                finishHangul(ic)
                val next =
                    if (keyboardView.textLayer == Layer.HANGUL) Layer.LETTERS else Layer.HANGUL
                keyboardView.textLayer = next
                keyboardView.layer = next
                KeyPrefs.saveTextLayer(this, next)
                updateShiftState()
            }

            else -> {
                val text = output ?: return
                val jamo = text.singleOrNull()
                if (keyboardView.layer == Layer.HANGUL && jamo != null &&
                    HangulComposer.isJamo(jamo)
                ) {
                    apply(ic, hangul.input(jamo, SystemClock.uptimeMillis()))
                } else {
                    finishHangul(ic)
                    ic.commitText(text, 1)
                }
                if (keyboardView.shiftState == ShiftState.ONE_SHOT) {
                    keyboardView.shiftState = ShiftState.OFF
                }
                if (code == KeyCode.SPACE || code == '.'.code) updateShiftState()
            }
        }
    }

    /**
     * The panel changed a value. Saved straight away rather than on dismissal: the keyboard is
     * torn down and rebuilt constantly, and a setting the user watched take effect must not come
     * back undone the next time the keyboard opens.
     */
    override fun onConfigChange(newConfig: KeyboardConfig) {
        KeyPrefs.save(this, newConfig)
        hangul.doubleTapEnabled = newConfig.doubleTapJamo
    }

    /**
     * The panel covers what is worth changing mid-sentence; everything else lives in the app.
     * Getting there means leaving the field being typed into, so it is a deliberate second hold
     * rather than a key anyone can brush.
     */
    private fun openSettingsApp() {
        // Started before the keyboard is dismissed: an input method may launch an activity while
        // its window is up, and hiding first would drop the very thing that permits it.
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
        )
        requestHideSelf(0)
    }

    override fun onCursorMove(steps: Int) {
        currentInputConnection?.let { finishHangul(it) }
        val keyCode = if (steps > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(kotlin.math.abs(steps)) { sendDownUpKeyEvents(keyCode) }
    }

    // ---------------------------------------------------------------- hangul

    /** Pushes a composer step into the editor: commit what is settled, keep the rest composing. */
    private fun apply(ic: InputConnection, result: HangulComposer.Result) {
        ic.beginBatchEdit()
        if (result.commit.isNotEmpty()) ic.commitText(result.commit, 1)
        if (result.composing.isEmpty()) {
            // Clear the span before ending it, otherwise its old text would stay behind.
            ic.setComposingText("", 1)
            ic.finishComposingText()
        } else {
            ic.setComposingText(result.composing, 1)
        }
        ic.endBatchEdit()
    }

    /** Accepts the syllable currently being composed as-is. */
    private fun finishHangul(ic: InputConnection) {
        if (!hangul.isComposing) return
        hangul.reset()
        ic.finishComposingText()
    }

    private fun backspace(ic: InputConnection) {
        hangul.backspace()?.let {
            apply(ic, it)
            return
        }

        val selected = ic.getSelectedText(0)
        val before = ic.getTextBeforeCursor(2, 0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else if (before.isNullOrEmpty()) {
            // Nothing readable in front of the cursor (some fields hide their text).
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        } else {
            val surrogatePair =
                before.length == 2 && Character.isSurrogatePair(before[0], before[1])
            ic.deleteSurroundingText(if (surrogatePair) 2 else 1, 0)
        }
        updateShiftState()
    }

    // ----------------------------------------------------------------- keys

    private fun performEnter() {
        val editorInfo = currentInputEditorInfo
        val action = editorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
            ?: EditorInfo.IME_ACTION_NONE
        val actionSuppressed =
            (editorInfo?.imeOptions?.and(EditorInfo.IME_FLAG_NO_ENTER_ACTION) ?: 0) != 0

        if (!actionSuppressed &&
            action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            currentInputConnection?.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        updateShiftState()
    }

    /** Tap arms shift for one character; a second tap right after locks caps. */
    private fun toggleShift() {
        val now = SystemClock.uptimeMillis()
        keyboardView.shiftState = when (keyboardView.shiftState) {
            ShiftState.OFF -> ShiftState.ONE_SHOT
            ShiftState.ONE_SHOT ->
                if (now - lastShiftTap < DOUBLE_TAP_MS) ShiftState.LOCKED else ShiftState.OFF

            ShiftState.LOCKED -> ShiftState.OFF
        }
        keyboardView.shiftIsAutomatic = false
        lastShiftTap = now
    }

    /**
     * Follows the field's auto-capitalisation hint (start of sentence, name fields, ...), when it
     * is switched on at all. Only meaningful for the Latin layer — on the Hangul layer Shift picks
     * doubled consonants, so arming it automatically would type ㅃ where the user wanted ㅂ.
     */
    private fun updateShiftState() {
        if (!::keyboardView.isInitialized) return
        if (keyboardView.shiftState == ShiftState.LOCKED) return
        if (keyboardView.layer != Layer.LETTERS) return

        // Left alone entirely when off, so a Shift the user pressed themselves still stands.
        if (!keyboardView.config.autoCapitalize) {
            if (keyboardView.shiftIsAutomatic) {
                keyboardView.shiftIsAutomatic = false
                keyboardView.shiftState = ShiftState.OFF
            }
            return
        }

        val editorInfo = currentInputEditorInfo
        val ic = currentInputConnection
        val caps = if (editorInfo != null && ic != null &&
            editorInfo.inputType != InputType.TYPE_NULL
        ) ic.getCursorCapsMode(editorInfo.inputType) else 0

        keyboardView.shiftIsAutomatic = caps != 0
        keyboardView.shiftState = if (caps != 0) ShiftState.ONE_SHOT else ShiftState.OFF
    }

    private companion object {
        const val DOUBLE_TAP_MS = 400L
    }
}
