package com.example.bigspacekeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.SparseArray
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws and drives the whole keyboard. Everything is painted by hand rather than using the
 * deprecated framework KeyboardView because that one gives every key in a row the same height —
 * which makes an over-sized space bar impossible.
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    interface OnKeyboardActionListener {
        /** [output] is the text to commit for printable keys, null for commands. */
        fun onKey(code: Int, output: String?)

        /** Horizontal swipe over the space bar; [steps] is negative for left. */
        fun onCursorMove(steps: Int)
    }

    var actionListener: OnKeyboardActionListener? = null

    var config: KeyboardConfig = KeyboardConfig()
        set(value) {
            field = value
            rebuild()
            requestLayout()
        }

    var layer: Layer = Layer.LETTERS
        set(value) {
            if (field == value) return
            field = value
            if (value != Layer.LETTERS) shiftState = ShiftState.OFF
            rebuild()
            invalidate()
        }

    /** Which text layer the "ABC" / "한글" key returns to. */
    var textLayer: Layer = Layer.LETTERS
        set(value) {
            if (field == value) return
            field = value
            rebuild()
            invalidate()
        }

    /** Clipboard text offered on the paste strip. null removes the strip entirely. */
    var clipboardText: CharSequence? = null
        set(value) {
            if (field?.toString() == value?.toString()) return
            field = value
            rebuild()
            requestLayout()
            invalidate()
        }

    /** Lazily built once: symbols the device has no glyph for are dropped. */
    private val symbolPages: List<SymbolCatalog.Page> by lazy { SymbolCatalog.pages(Paint()) }
    private var pageIndex = 0

    /** Which palette page is showing. Saved between sessions — there are a lot of pages. */
    var symbolPage: Int
        get() = pageIndex
        set(value) {
            if (symbolPages.isEmpty()) return
            val clamped = value.coerceIn(0, symbolPages.size - 1)
            if (clamped == pageIndex) return
            pageIndex = clamped
            if (layer == Layer.SYMBOL_PAD) {
                rebuild()
                invalidate()
            }
        }

    private fun currentPage(): SymbolCatalog.Page? =
        if (layer == Layer.SYMBOL_PAD) symbolPages.getOrNull(pageIndex) else null

    var shiftState: ShiftState = ShiftState.OFF
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** True when Shift was armed by auto-capitalisation rather than by the user. */
    var shiftIsAutomatic: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private var rows: List<KeyRow> = emptyList()
    private val placed = mutableListOf<PlacedKey>()

    /** One finger on the keyboard. [handled] means a repeat or long press already fired for it. */
    private class Touch(var placed: PlacedKey, var anchorX: Float) {
        var swiped = false
        var handled = false
    }

    private val touches = SparseArray<Touch>()

    /** Whose key the preview bubble shows — the finger that landed most recently. */
    private var previewPointerId = -1

    private val keyGap = dp(3f)
    private val keyRadius = dp(8f)
    private val strokeWidth = dp(2f)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    // TextPaint rather than Paint so TextUtils.ellipsize can measure the clipboard preview.
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // onDraw runs twice per keystroke over every key, so its shapes are reused rather than
    // allocated. Each is filled in and handed straight to canvas — never held across a call.
    private val iconPath = Path()
    private val scratchRect = RectF()

    private val colBackground = color(R.color.kb_background)
    private val colKey = color(R.color.key_bg)
    private val colKeyPressed = color(R.color.key_bg_pressed)
    private val colFn = color(R.color.key_fn_bg)
    private val colFnPressed = color(R.color.key_fn_bg_pressed)
    private val colSpace = color(R.color.key_space_bg)
    private val colSpacePressed = color(R.color.key_space_bg_pressed)
    private val colAccent = color(R.color.key_accent_bg)
    private val colAccentPressed = color(R.color.key_accent_bg_pressed)
    private val colText = color(R.color.key_text)
    private val colTextMuted = color(R.color.key_text_muted)
    private val colTextOnAccent = color(R.color.key_text_on_accent)
    private val colPreview = color(R.color.preview_bg)
    private val colClip = color(R.color.clip_bg)
    private val colClipPressed = color(R.color.clip_bg_pressed)

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    /** Repeat (backspace) and long-press share one slot: no key ever does both. */
    private var holdRunnable: Runnable? = null
    private var holdPointerId = -1

    /** How far the finger must travel on the space bar to move the cursor one character. */
    private var swipeStep = dp(24f)

    init {
        setBackgroundColor(colBackground)
        isHapticFeedbackEnabled = true
        rebuild()
    }

    private fun dp(value: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
    )

    private fun color(id: Int) = ContextCompat.getColor(context, id)

    private fun rebuild() {
        rows = KeyboardLayouts.rowsFor(layer, config, textLayer, currentPage(), clipboardText)
        if (width > 0) place()
    }

    private fun movePage(step: Int) {
        if (symbolPages.isEmpty()) return
        pageIndex = (pageIndex + step + symbolPages.size) % symbolPages.size
        rebuild()
        invalidate()
    }

    // ---------------------------------------------------------------- layout

    /**
     * Keys are square: their height comes from their width, not from a dp constant. That is what
     * keeps the keyboard short even with the number row added.
     *
     * The cap matters on wide screens (tablets, landscape) where a square key would be enormous —
     * there the keyboard stops growing at a fraction of the display height and keys go wide-and-flat
     * instead.
     */
    private fun keyHeight(availableWidth: Float): Float {
        val square = availableWidth / KeyboardLayouts.STANDARD_UNITS * config.keyHeightRatio
        val totalWeight = rows.sumOf { it.heightWeight.toDouble() }.toFloat()
        if (totalWeight <= 0f) return square
        val cap =
            resources.displayMetrics.heightPixels * KeyboardLayouts.MAX_SCREEN_FRACTION / totalWeight
        return min(square, cap)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val totalWeight = rows.sumOf { it.heightWeight.toDouble() }.toFloat()
        val available = (w - paddingLeft - paddingRight).toFloat()
        val h = keyHeight(available) * totalWeight + paddingTop + paddingBottom
        setMeasuredDimension(w, h.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        place()
    }

    private fun place() {
        placed.clear()
        if (width == 0 || rows.isEmpty()) return

        val availableWidth = (width - paddingLeft - paddingRight).toFloat()
        val keyHeight = keyHeight(availableWidth)
        var top = paddingTop.toFloat()

        for ((rowIndex, row) in rows.withIndex()) {
            val rowHeight = keyHeight * row.heightWeight
            val unit = availableWidth / row.units
            var x = paddingLeft + row.sideGap * unit
            val first = placed.size
            var columnLeft = x
            var columnRight = x

            for (key in row.keys) {
                // subRow 1 continues the column its partner opened instead of starting a new one.
                if (key.subRow != 1) {
                    columnLeft = x
                    columnRight = x + key.width * unit
                    x = columnRight
                }

                val bandHeight = if (key.subRow == null) rowHeight else rowHeight / 2f
                val bandTop = top + if (key.subRow == 1) rowHeight / 2f else 0f
                // Stacked keys sit closer together than full-height keys do.
                val verticalGap = if (key.subRow == null) keyGap else keyGap * 0.5f

                placed.add(
                    PlacedKey(
                        key,
                        RectF(columnLeft, bandTop, columnRight, bandTop + bandHeight),
                        RectF(
                            columnLeft + keyGap, bandTop + verticalGap,
                            columnRight - keyGap, bandTop + bandHeight - verticalGap
                        )
                    )
                )
            }

            // Stretch the outermost touch targets to the screen edges so no tap is wasted. Both
            // halves of an edge column need it, so this goes by column rather than by key.
            val rowKeys = placed.subList(first, placed.size)
            val leftEdge = rowKeys.first().hit.left
            val rightEdge = rowKeys.last().hit.right
            rowKeys.forEach {
                if (it.hit.left == leftEdge) it.hit.left = 0f
                if (it.hit.right == rightEdge) it.hit.right = width.toFloat()
                if (rowIndex == 0) it.hit.top = 0f
                // Only the keys actually touching the bottom reach for the screen edge.
                if (rowIndex == rows.size - 1 && it.key.subRow != 0) it.hit.bottom = height.toFloat()
            }
            top += rowHeight
        }

        swipeStep = availableWidth / 10f
    }

    // ----------------------------------------------------------------- touch

    /**
     * Every pointer is tracked separately. Typing at speed means the next finger lands before the
     * last one lifts, and those arrive as ACTION_POINTER_DOWN / ACTION_POINTER_UP — handling only
     * ACTION_DOWN / ACTION_UP silently drops every overlapping key.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                beginTouch(event.getPointerId(index), event.getX(index), event.getY(index))
            }

            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    moveTouch(event.getPointerId(index), event.getX(index), event.getY(index))
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                endTouch(event.getPointerId(event.actionIndex))

            MotionEvent.ACTION_CANCEL -> {
                cancelHold()
                touches.clear()
                invalidate()
            }
        }
        return true
    }

    private fun beginTouch(pointerId: Int, x: Float, y: Float) {
        val target = keyAt(x, y) ?: return
        if (target.key.code == KeyCode.NONE) return

        commitEarlierTouches()
        touches.put(pointerId, Touch(target, x))
        previewPointerId = pointerId
        invalidate()
        feedback(target.key)

        // Only one key can be held at a time; the newest finger takes the timer over.
        cancelHold()
        when {
            target.key.repeatable -> startRepeat(pointerId, target.key)
            target.key.longPress != null -> startLongPress(pointerId, target.key)
        }
    }

    /**
     * A new finger landing settles whatever the fingers already down were resting on.
     *
     * Keys normally type on release so a slightly-off press can be dragged onto the right key,
     * but with two fingers down that would order the output by *lift* — press ㅂ, press ㅈ, lift ㅈ,
     * lift ㅂ and you get "ㅈㅂ". Committing the older key here keeps the output in press order,
     * and makes fast typing feel more immediate as a side effect.
     */
    private fun commitEarlierTouches() {
        for (index in 0 until touches.size()) {
            val earlier = touches.valueAt(index)
            if (earlier.handled || earlier.swiped) continue
            earlier.handled = true
            emit(earlier.placed.key)
        }
    }

    private fun moveTouch(pointerId: Int, x: Float, y: Float) {
        val touch = touches.get(pointerId) ?: return
        val key = touch.placed.key

        if (key.code == KeyCode.SPACE && config.spaceCursorSwipe) {
            var delta = x - touch.anchorX
            while (abs(delta) >= swipeStep) {
                val steps = if (delta > 0) 1 else -1
                actionListener?.onCursorMove(steps)
                touch.anchorX += swipeStep * steps
                delta = x - touch.anchorX
                touch.swiped = true
            }
            return
        }

        // Sliding onto a different key re-targets, so a slightly-off press can be saved by
        // dragging before lifting the finger.
        if (touch.handled || key.repeatable || touch.placed.hit.contains(x, y)) return
        val next = keyAt(x, y) ?: return
        if (next === touch.placed || next.key.code == KeyCode.NONE) return
        if (holdPointerId == pointerId) cancelHold()
        touch.placed = next
        touch.anchorX = x
        invalidate()
    }

    private fun endTouch(pointerId: Int) {
        // Released before the touch lookup: a repeat that outlived its key must still be stopped.
        if (holdPointerId == pointerId) cancelHold()
        val touch = touches.get(pointerId) ?: return
        touches.remove(pointerId)
        invalidate()
        if (!touch.swiped && !touch.handled) emit(touch.placed.key)
    }

    private fun isPressed(candidate: PlacedKey): Boolean {
        for (index in 0 until touches.size()) {
            if (touches.valueAt(index).placed === candidate) return true
        }
        return false
    }

    /** Falls back to the nearest key so a tap in a gap still types something sensible. */
    private fun keyAt(x: Float, y: Float): PlacedKey? {
        placed.firstOrNull { it.hit.contains(x, y) }?.let { return it }
        return placed.minByOrNull { p ->
            val dx = x - p.hit.centerX()
            val dy = y - p.hit.centerY()
            dx * dx + dy * dy
        }
    }

    private fun emit(key: Key) {
        // Paging is pure view state, so it never reaches the service.
        when (key.code) {
            KeyCode.NONE -> return
            KeyCode.PAGE_PREV -> return movePage(-1)
            KeyCode.PAGE_NEXT -> return movePage(1)
        }
        actionListener?.onKey(key.code, if (key.isPrintable) outputOf(key) else null)
    }

    private fun outputOf(key: Key): String {
        val shifted = shiftState != ShiftState.OFF &&
            (key.respondsToAutoShift || !shiftIsAutomatic)
        if (shifted) {
            key.shiftOutput?.let { return it }
            // Emoji live outside the BMP and have no upper case, so only plain chars are folded.
            if (!Character.isSupplementaryCodePoint(key.code)) {
                return key.code.toChar().uppercaseChar().toString()
            }
        }
        return codePointText(key.code)
    }

    private fun codePointText(code: Int): String =
        if (Character.isSupplementaryCodePoint(code)) String(Character.toChars(code))
        else code.toChar().toString()

    /** Holding backspace (or a page key) fires repeatedly, accelerating to a floor. */
    private fun startRepeat(pointerId: Int, key: Key) {
        val speed = config.repeatSpeed.coerceAtLeast(0.1f)
        val floor = (REPEAT_FLOOR_MS / speed).toLong().coerceAtLeast(15L)
        var delay = (REPEAT_START_MS / speed).toLong()
        val runnable = object : Runnable {
            override fun run() {
                touches.get(pointerId)?.handled = true
                emit(key)
                feedback(key)
                delay = (delay * 0.75f).toLong().coerceAtLeast(floor)
                handler.postDelayed(this, delay)
            }
        }
        holdPointerId = pointerId
        holdRunnable = runnable
        handler.postDelayed(runnable, delay)
    }

    /** Holding a key past the configured delay types its corner symbol instead of the key. */
    private fun startLongPress(pointerId: Int, key: Key) {
        val symbol = key.longPress ?: return
        val runnable = Runnable {
            touches.get(pointerId)?.handled = true
            actionListener?.onKey(symbol[0].code, symbol)
            feedback(key)
            invalidate() // the preview bubble switches to the symbol
        }
        holdPointerId = pointerId
        holdRunnable = runnable
        handler.postDelayed(runnable, config.longPressMs.toLong())
    }

    private fun cancelHold() {
        holdRunnable?.let { handler.removeCallbacks(it) }
        holdRunnable = null
        holdPointerId = -1
    }

    private fun feedback(key: Key) {
        vibrate()
        playClick(key)
    }

    /**
     * Driven through [Vibrator] rather than `performHapticFeedback` because that one offers no
     * strength control. Devices with amplitude control get a fixed short pulse at the requested
     * amplitude; the rest can only be made stronger by pulsing longer.
     */
    private fun vibrate() {
        val strength = config.vibrateStrength
        if (strength <= 0f) return
        val device = vibrator ?: return
        if (!device.hasVibrator()) return

        val scaledDuration = (8f + strength * 32f).toLong()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val hasAmplitude = device.hasAmplitudeControl()
            val effect = if (hasAmplitude) {
                VibrationEffect.createOneShot(
                    18L, (strength * 255f).roundToInt().coerceIn(1, 255)
                )
            } else {
                VibrationEffect.createOneShot(scaledDuration, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            device.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            device.vibrate(scaledDuration)
        }
    }

    private fun playClick(key: Key) {
        val volume = config.soundVolume
        if (volume <= 0f) return
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val effect = when (key.code) {
            KeyCode.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
            KeyCode.BACKSPACE -> AudioManager.FX_KEYPRESS_DELETE
            KeyCode.ENTER -> AudioManager.FX_KEYPRESS_RETURN
            else -> AudioManager.FX_KEYPRESS_STANDARD
        }
        audio.playSoundEffect(effect, volume)
    }

    override fun onDetachedFromWindow() {
        cancelHold()
        touches.clear()
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ draw

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (p in placed) drawKey(canvas, p, isPressed(p))
        touches.get(previewPointerId)?.let { drawPreview(canvas, it) }
    }

    private fun drawKey(canvas: Canvas, p: PlacedKey, isPressed: Boolean) {
        val key = p.key
        if (key.code == KeyCode.NONE) return
        fillPaint.color = when (key.style) {
            KeyStyle.NORMAL -> if (isPressed) colKeyPressed else colKey
            KeyStyle.FUNCTION -> if (isPressed) colFnPressed else colFn
            KeyStyle.SPACE -> if (isPressed) colSpacePressed else colSpace
            KeyStyle.ACCENT -> if (isPressed) colAccentPressed else colAccent
            KeyStyle.CLIP -> if (isPressed) colClipPressed else colClip
        }
        if (key.code == KeyCode.PASTE) return drawClip(canvas, p)
        // The shift key lights up while it is armed so the caps state is never a guess.
        if (key.code == KeyCode.SHIFT && shiftState != ShiftState.OFF) {
            fillPaint.color = if (isPressed) colAccentPressed else colAccent
        }
        canvas.drawRoundRect(p.draw, keyRadius, keyRadius, fillPaint)

        val onAccent = fillPaint.color == colAccent || fillPaint.color == colAccentPressed
        val foreground = if (onAccent) colTextOnAccent else colText

        when (key.code) {
            KeyCode.SHIFT -> drawShift(canvas, p.draw, foreground, shiftState == ShiftState.LOCKED)
            KeyCode.BACKSPACE -> drawBackspace(canvas, p.draw, foreground)
            KeyCode.ENTER -> drawEnter(canvas, p.draw, foreground)
            KeyCode.SPACE -> drawSpaceHint(canvas, p.draw)
            else -> drawLabel(canvas, p, foreground)
        }
        drawLongPressHint(canvas, p)
    }

    /**
     * The paste strip: a pill with a clipboard mark and the start of the clipboard text, cut off
     * with an ellipsis. Seeing what is about to be pasted is the whole point, so the text gets
     * every pixel left over after the icon.
     */
    private fun drawClip(canvas: Canvas, p: PlacedKey) {
        val r = p.draw
        canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fillPaint)

        val iconSize = r.height() * 0.46f
        val iconCentre = r.left + r.height() * 0.62f
        drawClipboardIcon(canvas, iconCentre, r.centerY(), iconSize)

        val textLeft = iconCentre + iconSize * 0.5f + dp(7f)
        val available = r.right - dp(12f) - textLeft
        if (available <= 0f) return

        textPaint.color = colText
        textPaint.textSize = r.height() * 0.52f
        textPaint.textAlign = Paint.Align.LEFT
        val shown = TextUtils.ellipsize(
            p.key.label, textPaint, available, TextUtils.TruncateAt.END
        )
        val fm = textPaint.fontMetrics
        canvas.drawText(
            shown, 0, shown.length, textLeft, r.centerY() - (fm.ascent + fm.descent) / 2f, textPaint
        )
        textPaint.textAlign = Paint.Align.CENTER // every other label is centred
    }

    private fun drawClipboardIcon(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        iconPaint.color = colTextMuted
        iconPaint.style = Paint.Style.STROKE
        iconPaint.strokeWidth = strokeWidth * 0.75f
        val halfWidth = size * 0.36f
        val halfHeight = size * 0.5f
        scratchRect.set(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight)
        canvas.drawRoundRect(scratchRect, size * 0.16f, size * 0.16f, iconPaint)
        iconPaint.style = Paint.Style.FILL
        scratchRect.set(
            cx - halfWidth * 0.55f, cy - halfHeight * 1.2f,
            cx + halfWidth * 0.55f, cy - halfHeight * 0.7f
        )
        canvas.drawRoundRect(scratchRect, size * 0.09f, size * 0.09f, iconPaint)
    }

    /** The small symbol in the top-right corner, shown so the hold targets are discoverable. */
    private fun drawLongPressHint(canvas: Canvas, p: PlacedKey) {
        val symbol = p.key.longPress ?: return
        if (config.hintSizeRatio <= 0f) return

        textPaint.color = colTextMuted
        textPaint.textSize = p.draw.height() * config.hintSizeRatio
        // Positioned off the measured glyph so a wide one (₩, …) cannot spill over the key edge
        // however large the hint is set.
        val inset = dp(2f)
        val fm = textPaint.fontMetrics
        canvas.drawText(
            symbol,
            p.draw.right - textPaint.measureText(symbol) / 2f - inset,
            p.draw.top + inset - fm.ascent,
            textPaint
        )
    }

    private fun drawLabel(canvas: Canvas, p: PlacedKey, foreground: Int) {
        val key = p.key
        val label = if (key.isPrintable) outputOf(key) else key.label
        if (label.isEmpty()) return

        // The corner hint claims a band across the top of the key; the main label centres itself
        // in whatever is left, and shrinks once the hint is set large enough to squeeze it. That
        // keeps the two from colliding at any hint size.
        val height = p.draw.height()
        val hintBand = if (key.longPress != null) height * config.hintSizeRatio * 0.85f else 0f
        val remaining = height - hintBand

        // Emoji carry their detail in the glyph rather than in a letterform, so they get drawn
        // bigger than a letter would at the same key size.
        val emoji = Character.isSupplementaryCodePoint(key.code)
        val base = when {
            key.style == KeyStyle.FUNCTION -> height * 0.34f
            emoji -> min(height * 0.62f, remaining * 0.8f)
            else -> min(height * 0.44f, remaining * 0.62f)
        }
        textPaint.color = foreground
        textPaint.textSize = fitTextSize(label, p.draw.width() * 0.8f, base)

        val centre = p.draw.top + hintBand + remaining / 2f
        val fm = textPaint.fontMetrics
        canvas.drawText(label, p.draw.centerX(), centre - (fm.ascent + fm.descent) / 2f, textPaint)
    }

    private fun fitTextSize(label: String, maxWidth: Float, desired: Float): Float {
        textPaint.textSize = desired
        val measured = textPaint.measureText(label)
        return if (measured <= maxWidth) desired else desired * maxWidth / measured
    }

    /**
     * A muted centre line — enough to identify the space bar without shouting. In the symbol pad
     * the same space doubles as the page indicator, which costs no layout width.
     */
    private fun drawSpaceHint(canvas: Canvas, r: RectF) {
        val page = currentPage()
        if (page != null) {
            textPaint.color = colTextMuted
            textPaint.textSize = fitTextSize(page.label, r.width() * 0.8f, r.height() * 0.3f)
            val fm = textPaint.fontMetrics
            canvas.drawText(page.label, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2f, textPaint)
            return
        }
        fillPaint.color = colTextMuted
        val halfWidth = min(r.width() * 0.18f, dp(56f))
        val thickness = dp(2f)
        scratchRect.set(
            r.centerX() - halfWidth, r.centerY() - thickness / 2f,
            r.centerX() + halfWidth, r.centerY() + thickness / 2f
        )
        canvas.drawRoundRect(scratchRect, thickness, thickness, fillPaint)
    }

    private fun drawShift(canvas: Canvas, r: RectF, tint: Int, locked: Boolean) {
        val s = min(r.width(), r.height()) * 0.36f
        val cx = r.centerX()
        val cy = r.centerY() - if (locked) s * 0.12f else 0f
        iconPaint.color = tint
        iconPaint.style = Paint.Style.FILL
        iconPath.reset()
        iconPath.moveTo(cx, cy - s)
        iconPath.lineTo(cx + s, cy + s * 0.1f)
        iconPath.lineTo(cx + s * 0.45f, cy + s * 0.1f)
        iconPath.lineTo(cx + s * 0.45f, cy + s * 0.8f)
        iconPath.lineTo(cx - s * 0.45f, cy + s * 0.8f)
        iconPath.lineTo(cx - s * 0.45f, cy + s * 0.1f)
        iconPath.lineTo(cx - s, cy + s * 0.1f)
        iconPath.close()
        canvas.drawPath(iconPath, iconPaint)
        if (locked) {
            canvas.drawRect(cx - s * 0.45f, cy + s, cx + s * 0.45f, cy + s * 1.25f, iconPaint)
        }
    }

    private fun drawBackspace(canvas: Canvas, r: RectF, tint: Int) {
        val h = min(r.width(), r.height()) * 0.5f
        val w = h * 1.5f
        val cx = r.centerX()
        val cy = r.centerY()
        iconPaint.color = tint
        iconPaint.style = Paint.Style.STROKE
        iconPaint.strokeWidth = strokeWidth
        iconPaint.strokeJoin = Paint.Join.ROUND
        iconPath.reset()
        iconPath.moveTo(cx - w / 2f, cy)
        iconPath.lineTo(cx - w / 2f + h * 0.55f, cy - h / 2f)
        iconPath.lineTo(cx + w / 2f, cy - h / 2f)
        iconPath.lineTo(cx + w / 2f, cy + h / 2f)
        iconPath.lineTo(cx - w / 2f + h * 0.55f, cy + h / 2f)
        iconPath.close()
        canvas.drawPath(iconPath, iconPaint)
        val x = h * 0.2f
        canvas.drawLine(cx + x * 0.2f - x, cy - x, cx + x * 0.2f + x, cy + x, iconPaint)
        canvas.drawLine(cx + x * 0.2f - x, cy + x, cx + x * 0.2f + x, cy - x, iconPaint)
    }

    private fun drawEnter(canvas: Canvas, r: RectF, tint: Int) {
        val s = min(r.width(), r.height()) * 0.5f
        val cx = r.centerX()
        val cy = r.centerY()
        iconPaint.color = tint
        iconPaint.style = Paint.Style.STROKE
        iconPaint.strokeWidth = strokeWidth
        iconPaint.strokeJoin = Paint.Join.ROUND
        iconPath.reset()
        iconPath.moveTo(cx + s * 0.5f, cy - s * 0.5f)
        iconPath.lineTo(cx + s * 0.5f, cy + s * 0.25f)
        iconPath.lineTo(cx - s * 0.5f, cy + s * 0.25f)
        iconPath.moveTo(cx - s * 0.15f, cy - s * 0.1f)
        iconPath.lineTo(cx - s * 0.55f, cy + s * 0.25f)
        iconPath.lineTo(cx - s * 0.15f, cy + s * 0.6f)
        canvas.drawPath(iconPath, iconPaint)
    }

    /** Magnified bubble above the pressed key, clamped inside the view. */
    private fun drawPreview(canvas: Canvas, touch: Touch) {
        if (!config.previewEnabled) return
        val p = touch.placed
        val key = p.key
        if (!key.isPrintable || key.code == KeyCode.SPACE) return

        val label = if (touch.handled) key.longPress ?: outputOf(key) else outputOf(key)
        val bubbleWidth = maxOf(p.draw.width() * 1.25f, dp(44f))
        val bubbleHeight = p.draw.height() * 1.1f
        var left = p.draw.centerX() - bubbleWidth / 2f
        left = left.coerceIn(dp(2f), width - bubbleWidth - dp(2f))
        var top = p.draw.top - bubbleHeight - dp(6f)
        if (top < dp(2f)) top = p.draw.bottom + dp(6f)
        scratchRect.set(left, top, left + bubbleWidth, top + bubbleHeight)

        fillPaint.color = colPreview
        canvas.drawRoundRect(scratchRect, keyRadius, keyRadius, fillPaint)

        textPaint.color = colText
        textPaint.textSize = scratchRect.height() * 0.55f
        val fm = textPaint.fontMetrics
        canvas.drawText(
            label,
            scratchRect.centerX(),
            scratchRect.centerY() - (fm.ascent + fm.descent) / 2f,
            textPaint
        )
    }

    private companion object {
        /** Repeat cadence at speed 1.0: first fire after this, accelerating down to the floor. */
        const val REPEAT_START_MS = 400f
        const val REPEAT_FLOOR_MS = 45f
    }
}
