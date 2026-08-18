package com.example.bigspacekeyboard

import android.graphics.RectF
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Key codes. Printable keys carry the code point of their (lower-case / unshifted) character;
 * everything negative is a command handled by the service.
 */
object KeyCode {
    const val SHIFT = -1
    const val BACKSPACE = -2
    const val ENTER = -3
    const val TO_SYMBOLS = -4
    const val TO_TEXT = -5
    const val LANGUAGE = -6
    const val TO_PAD = -7
    const val PAGE_PREV = -8
    const val PAGE_NEXT = -9
    const val PASTE = -10

    /** Opens the settings panel drawn on the keyboard itself. */
    const val SETTINGS = -11

    /** Opens the full settings app, leaving whatever is being typed. */
    const val SETTINGS_APP = -12

    /** Inert: a settings row's name and current value. Not a key, just text in a key's place. */
    const val LABEL = -13

    /** Filler for a half-empty symbol page. Draws nothing, does nothing. */
    const val NONE = -99

    /**
     * The settings panel's − / + keys. Every setting owns a pair: [SETTING_BASE] minus twice its
     * index turns the value down, one below that turns it up. They start past [NONE] so nothing
     * above ever collides with them however many commands get added.
     */
    const val SETTING_BASE = -100

    fun settingDown(index: Int) = SETTING_BASE - index * 2
    fun settingUp(index: Int) = SETTING_BASE - index * 2 - 1

    /** -1 when [code] is not one of the panel's adjust keys. */
    fun settingIndex(code: Int) = if (code > SETTING_BASE) -1 else (SETTING_BASE - code) / 2

    /** Which way an adjust key moves its setting. */
    fun settingSteps(code: Int) = if ((SETTING_BASE - code) % 2 == 0) -1 else 1

    const val SPACE = ' '.code
}

enum class KeyStyle { NORMAL, FUNCTION, SPACE, ACCENT, CLIP }

enum class Layer {
    LETTERS, HANGUL, SYMBOLS, SYMBOL_PAD, SETTINGS;

    val isText: Boolean get() = this == LETTERS || this == HANGUL
}

enum class ShiftState { OFF, ONE_SHOT, LOCKED }

/**
 * One key in a row. [width] is in row units (a plain letter key is 1 unit wide), [heightRatio]
 * is the fraction of the row height the key actually occupies — that is what lets the space bar
 * fill a tall row while the keys next to it stay normal-sized.
 *
 * [shiftOutput] is only needed where Shift produces a different character that upper-casing
 * cannot derive — the doubled Hangul consonants (ㅂ→ㅃ) and ㅐ→ㅒ, ㅔ→ㅖ.
 */
data class Key(
    val code: Int,
    val label: String = "",
    val width: Float = 1f,
    /**
     * Splits the row band in two where the space bar leaves vertical room beside it.
     * null fills the whole band, 0 is the upper half, 1 is the lower half — and a key marked 1
     * reuses the column of the key before it instead of starting a new one.
     */
    val subRow: Int? = null,
    val style: KeyStyle = KeyStyle.NORMAL,
    val repeatable: Boolean = false,
    val shiftOutput: String? = null,
    val respondsToAutoShift: Boolean = true,
    /** Symbol produced by holding the key down; drawn small in the key's top-right corner. */
    val longPress: String? = null,
    /**
     * Command sent by holding the key down, for function keys where the hold is not a character.
     * [longPress] still supplies the corner hint, so the hold stays discoverable either way.
     */
    val longPressCode: Int? = null,
    /** Right-aligned second half of a settings row's text: the setting's current value. */
    val trailing: String? = null,
) {
    val isPrintable: Boolean get() = code >= 0

    /** Draws, but never types: blanks on a short symbol page and the settings panel's text. */
    val isInert: Boolean get() = code == KeyCode.NONE || code == KeyCode.LABEL
}

/**
 * A row of keys. [heightWeight] is the row height relative to a normal key row, [sideGap] is
 * empty padding (in units) added to both ends — used to centre the middle "asdf" row.
 */
data class KeyRow(
    val keys: List<Key>,
    val heightWeight: Float = 1f,
    val sideGap: Float = 0f,
) {
    /** Stacked keys share their column's width, so they must not be counted twice. */
    val units: Float
        get() = sideGap * 2f + keys.filter { it.subRow != 1 }
            .sumOf { it.width.toDouble() }.toFloat()
}

/** A key after layout: [hit] is the touch target (fills its whole cell), [draw] is what is painted. */
class PlacedKey(val key: Key, val hit: RectF, val draw: RectF)

/**
 * User-tunable geometry. [spaceWidthUnits] and [spaceHeightWeight] are the whole point of this
 * keyboard: the space bar is both wider and taller than a normal key so it is hard to miss.
 *
 * [keyHeightRatio] is measured against a *square* key — key height is derived from key width
 * ([KeyboardLayouts.STANDARD_UNITS] keys per row) rather than being a fixed dp value, so 1.0
 * means perfectly square and the keyboard stays as short as it can be.
 */
data class KeyboardConfig(
    val keyHeightRatio: Float = 1f,
    val spaceWidthUnits: Float = 6f,
    val spaceHeightWeight: Float = 1.6f,
    /**
     * Corner long-press hint height, as a fraction of the key. 0 hides the hints; anything the
     * settings screen offers below that stays large enough to actually read.
     */
    val hintSizeRatio: Float = 0.25f,
    val previewEnabled: Boolean = true,
    /** Key click volume, 0..1. 0 is silent. */
    val soundVolume: Float = 0.6f,
    /** Haptic strength, 0..1. 0 is off. */
    val vibrateStrength: Float = 0.5f,
    val spaceCursorSwipe: Boolean = true,
    val doubleTapJamo: Boolean = true,
    /**
     * Whether the editor's auto-capitalisation hint may arm Shift by itself. Off by default: the
     * English layer coming up in capitals means un-shifting before nearly every word, and a
     * keyboard guessing at case is a worse trade than typing the one capital by hand.
     */
    val autoCapitalize: Boolean = false,
    /** Multiplies how fast a held backspace (or page key) repeats. 1 = the default cadence. */
    val repeatSpeed: Float = 1f,
    /** How long a key must be held before it types its corner symbol. */
    val longPressMs: Int = 300,
) {
    /** How much of the bottom row the space bar takes, as a percentage. */
    val spaceWidthPercent: Int
        get() = (spaceWidthUnits / (KeyboardLayouts.SIDE_UNITS + spaceWidthUnits) * 100f).roundToInt()

    /** Height of the tallest layer, counted in key heights. */
    val totalWeight: Float get() = KeyboardLayouts.TEXT_ROWS + spaceHeightWeight
}

/**
 * One setting the on-keyboard panel can change.
 *
 * A tap moves the value to the next [stops] entry rather than adding a fixed amount, for two
 * reasons: the stops are the same ones the settings app's sliders offer, so a value set in one
 * place is never stranded between the other's notches, and repeated taps can't drift a float.
 */
class SettingSpec(
    val name: String,
    val stops: List<Float>,
    private val format: (Float) -> String,
    private val read: (KeyboardConfig) -> Float,
    private val write: (KeyboardConfig, Float) -> KeyboardConfig,
) {
    fun valueOf(config: KeyboardConfig): String = format(read(config))

    /** Nearest stop to the current value, moved [steps] along it. Stops at either end. */
    fun stepped(config: KeyboardConfig, steps: Int): KeyboardConfig {
        val current = read(config)
        val index = stops.indices.minByOrNull { abs(stops[it] - current) } ?: return config
        return write(config, stops[(index + steps).coerceIn(stops.indices)])
    }
}

/**
 * What the on-keyboard settings panel offers, in the order it shows them: the things worth
 * changing while looking at the keyboard rather than at a settings screen. Sizes come first
 * because this keyboard exists to get the space bar right, and the panel keeps the real space
 * bar on screen while it is being resized.
 *
 * The full list — including the switches that have no meaningful "more" or "less" — stays in the
 * settings app, one long-press further away.
 */
object KeyboardSettings {

    /** Settings per panel page. Matches [KeyboardLayouts.TEXT_ROWS]: one setting per row. */
    const val ROWS = 4

    /** Corner-hint stops, in percent of key height. The first one hides the hint. */
    val HINT_PERCENTS = intArrayOf(0, 20, 25, 30, 35, 40, 45, 50, 55, 60)

    private fun stops(from: Float, to: Float, step: Float): List<Float> {
        val count = ((to - from) / step).roundToInt()
        return (0..count).map { from + it * step }
    }

    private fun percent(value: Float) =
        if (value <= 0f) "끔" else "${(value * 100f).roundToInt()}%"

    private fun decimals(digits: Int, unit: String): (Float) -> String =
        { String.format(Locale.US, "%.${digits}f$unit", it) }

    val SPECS: List<SettingSpec> = listOf(
        SettingSpec(
            "스페이스바 폭", stops(4f, 8f, 0.5f), decimals(1, "칸"),
            { it.spaceWidthUnits }, { c, v -> c.copy(spaceWidthUnits = v) },
        ),
        SettingSpec(
            "스페이스바 높이", stops(1f, 2.2f, 0.1f), decimals(1, "배"),
            { it.spaceHeightWeight }, { c, v -> c.copy(spaceHeightWeight = v) },
        ),
        SettingSpec(
            "키 높이", stops(0.8f, 1.4f, 0.05f), decimals(2, "배"),
            { it.keyHeightRatio }, { c, v -> c.copy(keyHeightRatio = v) },
        ),
        SettingSpec(
            "기호 힌트", HINT_PERCENTS.map { it / 100f },
            { if (it <= 0f) "숨김" else percent(it) },
            { it.hintSizeRatio }, { c, v -> c.copy(hintSizeRatio = v) },
        ),
        SettingSpec(
            "키 소리", stops(0f, 1f, 0.1f), ::percent,
            { it.soundVolume }, { c, v -> c.copy(soundVolume = v) },
        ),
        SettingSpec(
            "진동 세기", stops(0f, 1f, 0.1f), ::percent,
            { it.vibrateStrength }, { c, v -> c.copy(vibrateStrength = v) },
        ),
        SettingSpec(
            "길게 누르기", stops(150f, 600f, 50f), { "${it.roundToInt()}ms" },
            { it.longPressMs.toFloat() }, { c, v -> c.copy(longPressMs = v.roundToInt()) },
        ),
        SettingSpec(
            "백스페이스 속도", stops(0.5f, 2f, 0.25f), { "${(it * 100f).roundToInt()}%" },
            { it.repeatSpeed }, { c, v -> c.copy(repeatSpeed = v) },
        ),
    )

    /** Page names, shown on the space bar the way the symbol pad shows its category. */
    val PAGE_NAMES = listOf("키 크기", "반응")

    val pageCount: Int get() = (SPECS.size + ROWS - 1) / ROWS

    fun pageName(page: Int) = PAGE_NAMES.getOrElse(page) { "설정" }
}

object KeyboardLayouts {

    /** Units taken by the keys flanking the space bar: 3.5 on the left, 2.5 on the right. */
    const val LEFT_UNITS = 3.5f
    const val RIGHT_UNITS = 2.5f
    const val SIDE_UNITS = LEFT_UNITS + RIGHT_UNITS

    /** Keys per ordinary row. One unit wide = one unit tall = a square key. */
    const val STANDARD_UNITS = 10f

    /** The Hangul layer fits 8 keys where the Latin one fits 10, so its keys are 25% wider. */
    const val HANGUL_KEY_WIDTH = 1.25f

    /**
     * Square rows above the space bar row. Every layer has exactly this many, so the keyboard
     * never changes height and the space bar never moves vertically either.
     */
    const val TEXT_ROWS = 4f

    /** The keyboard never takes more than this much of the display height. */
    const val MAX_SCREEN_FRACTION = 0.52f

    private fun letters(row: String, longPress: String = "") = row.mapIndexed { index, c ->
        Key(c.code, c.toString(), longPress = symbolAt(longPress, index))
    }

    private fun symbolAt(row: String, index: Int) =
        row.getOrNull(index)?.takeIf { it != ' ' }?.toString()

    /**
     * Long-press symbols for the three letter rows. The same physical position gives the same
     * symbol on the Latin and the Hangul layer, so the positions only have to be learned once.
     * Grouped by kind: operators and brackets, then punctuation, then currency and marks.
     */
    private const val ROW1_SYMBOLS = "-_=+[]{}\\|"
    private const val ROW2_SYMBOLS = ";:'\"~/<>`"
    private const val ROW3_SYMBOLS = "₩€£·…※°"

    /**
     * Digits sit above the text layers, with the usual QWERTY symbols on Shift.
     *
     * They ignore *automatic* Shift though: auto-capitalisation arms Shift at the start of a
     * sentence, and getting "!" when typing "1. " would be a nasty surprise. Only a Shift the
     * user pressed themselves reaches this row.
     */
    private fun numberRow(): KeyRow {
        val shifted = "!@#\$%^&*()"
        return KeyRow(
            "1234567890".mapIndexed { index, digit ->
                Key(
                    digit.code,
                    digit.toString(),
                    shiftOutput = shifted[index].toString(),
                    respondsToAutoShift = false,
                    longPress = shifted[index].toString(),
                )
            }
        )
    }

    /** Hangul row where Shift swaps in a different jamo; ' ' in [shifted] means "no change". */
    private fun jamo(
        base: String,
        shifted: String = "",
        longPress: String = "",
        width: Float = 1f,
    ) = base.mapIndexed { index, c ->
        Key(
            c.code,
            c.toString(),
            width = width,
            shiftOutput = symbolAt(shifted, index),
            longPress = symbolAt(longPress, index),
        )
    }

    private fun shiftKey(width: Float = 1.5f) =
        Key(KeyCode.SHIFT, width = width, style = KeyStyle.FUNCTION)

    private fun backspaceKey(width: Float = 1.5f) =
        Key(KeyCode.BACKSPACE, width = width, style = KeyStyle.FUNCTION, repeatable = true)

    private fun textLayerKey(
        textLayer: Layer,
        width: Float,
        hold: Int = KeyCode.SETTINGS,
    ) = Key(
        KeyCode.TO_TEXT,
        if (textLayer == Layer.HANGUL) "한글" else "ABC",
        width = width,
        style = KeyStyle.FUNCTION,
        longPress = SETTINGS_HINT,
        longPressCode = hold,
    )

    /**
     * Holding the bottom-left key opens the settings panel. It goes there because that corner is
     * the one function key every layer has in the same place, so the way in never moves — and
     * because a key of its own would have to come out of the space bar's width, which is the one
     * thing this keyboard will not trade.
     */
    const val SETTINGS_HINT = "⚙"

    /**
     * Bottom row. Three things happen here.
     *
     * The row is [KeyboardConfig.spaceHeightWeight] times a normal row tall and the space bar
     * fills all of it, so it is the one obviously over-sized target.
     *
     * The tall space bar leaves vertical room on either side, which would otherwise be padding.
     * Each flanking column is split into two: [TOP_LEFT]/[TOP_RIGHT] punctuation above, the
     * layer's own keys below.
     *
     * The flanking columns always add up to [LEFT_UNITS] and [RIGHT_UNITS], whatever layer is
     * showing, so the space bar never moves or changes size when the layer changes. Muscle memory
     * for the one key this keyboard exists to protect stays intact.
     */
    private fun bottomRow(config: KeyboardConfig, left: List<Key>, right: List<Key>) = KeyRow(
        heightWeight = config.spaceHeightWeight,
        keys = stackColumns(TOP_LEFT, left) +
            Key(KeyCode.SPACE, width = config.spaceWidthUnits, style = KeyStyle.SPACE) +
            stackColumns(TOP_RIGHT, right)
    )

    /** Interleaves the two sub-rows column by column; each column's width comes from [bottom]. */
    private fun stackColumns(top: List<Key>, bottom: List<Key>) = bottom.flatMapIndexed { i, key ->
        listOf(top[i].copy(width = key.width, subRow = 0), key.copy(subRow = 1))
    }

    private val TOP_LEFT = listOf(
        Key('@'.code, "@"),
        Key(':'.code, ":"),
        Key('/'.code, "/"),
    )

    private val TOP_RIGHT = listOf(
        Key('?'.code, "?"),
        Key('!'.code, "!"),
    )

    // ? and ! have their own keys above, so these two stay exactly what they say they are.
    private fun punctuationRight() = listOf(
        Key('.'.code, ".", width = 1f),
        Key(KeyCode.ENTER, width = 1.5f, style = KeyStyle.ACCENT),
    )

    /** Short strip above everything else offering the clipboard; absent when there is nothing to paste. */
    const val CLIP_ROW_WEIGHT = 0.55f

    private fun clipRow(text: CharSequence) = KeyRow(
        heightWeight = CLIP_ROW_WEIGHT,
        keys = listOf(
            Key(
                KeyCode.PASTE,
                text.toString().replace(Regex("\\s+"), " ").trim(),
                style = KeyStyle.CLIP,
            )
        ),
    )

    fun rowsFor(
        layer: Layer,
        config: KeyboardConfig,
        textLayer: Layer,
        page: SymbolCatalog.Page?,
        clipboard: CharSequence? = null,
        scrollRow: Int = 0,
        settingsPage: Int = 0,
    ): List<KeyRow> =
        (if (clipboard != null) listOf(clipRow(clipboard)) else emptyList()) +
            keyRowsFor(layer, config, textLayer, page, scrollRow, settingsPage)

    private fun keyRowsFor(
        layer: Layer,
        config: KeyboardConfig,
        textLayer: Layer,
        page: SymbolCatalog.Page?,
        scrollRow: Int,
        settingsPage: Int,
    ): List<KeyRow> = when (layer) {

        Layer.LETTERS -> listOf(
            numberRow(),
            KeyRow(letters("qwertyuiop", ROW1_SYMBOLS)),
            KeyRow(letters("asdfghjkl", ROW2_SYMBOLS), sideGap = 0.5f),
            KeyRow(listOf(shiftKey()) + letters("zxcvbnm", ROW3_SYMBOLS) + backspaceKey()),
            bottomRow(config, textBottomLeft(), punctuationRight()),
        )

        // 8 keys a row instead of 10, so every key is wider than on the Latin layer. ㅑㅕㅛㅠㅒㅖ
        // have no key: they come from tapping ㅏㅓㅗㅜㅐㅔ twice (or Shift). The vowels line up in
        // three columns — ㅗ above ㅓ above ㅜ.
        Layer.HANGUL -> listOf(
            numberRow(),
            KeyRow(jamo("ㅂㅈㄷㄱㅅㅗㅐㅔ", "ㅃㅉㄸㄲㅆㅛㅒㅖ", ROW1_SYMBOLS, HANGUL_KEY_WIDTH)),
            KeyRow(jamo("ㅁㄴㅇㄹㅎㅓㅏㅣ", "     ㅕㅑ", ROW2_SYMBOLS, HANGUL_KEY_WIDTH)),
            KeyRow(
                listOf(shiftKey(HANGUL_KEY_WIDTH)) +
                    jamo("ㅋㅌㅊㅍㅜㅡ", "    ㅠ", ROW3_SYMBOLS, HANGUL_KEY_WIDTH) +
                    backspaceKey(HANGUL_KEY_WIDTH)
            ),
            bottomRow(config, textBottomLeft(), punctuationRight()),
        )

        Layer.SYMBOLS -> listOf(
            KeyRow(letters("1234567890")),
            KeyRow(letters("@#\$₩%&-+()")),
            KeyRow(letters("=_[]<>{}\\|")),
            KeyRow(
                listOf(Key(KeyCode.TO_PAD, "기호", width = 1.5f, style = KeyStyle.FUNCTION)) +
                    letters("*\"':;!?") + backspaceKey()
            ),
            bottomRow(
                config,
                listOf(
                    textLayerKey(textLayer, 1.25f),
                    Key(KeyCode.LANGUAGE, "한/영", width = 1.25f, style = KeyStyle.FUNCTION),
                    Key(','.code, ",", width = 1f),
                ),
                punctuationRight(),
            ),
        )

        Layer.SYMBOL_PAD -> symbolPadRows(config, textLayer, page, scrollRow)

        Layer.SETTINGS -> settingsRows(config, textLayer, settingsPage)
    }

    /**
     * The settings panel. One setting per row — name and value on the left, − and + on the
     * right — over an untouched bottom row, so the space bar being resized is the real one,
     * at its real size, right where it always is.
     */
    private fun settingsRows(
        config: KeyboardConfig,
        textLayer: Layer,
        settingsPage: Int,
    ): List<KeyRow> {
        val first = settingsPage * KeyboardSettings.ROWS
        val rows = (0 until KeyboardSettings.ROWS).map { row ->
            val index = first + row
            val spec = KeyboardSettings.SPECS.getOrNull(index)
                ?: return@map KeyRow(listOf(Key(KeyCode.NONE, width = STANDARD_UNITS)))
            KeyRow(
                listOf(
                    Key(
                        KeyCode.LABEL, spec.name, width = 7f,
                        trailing = spec.valueOf(config),
                    ),
                    Key(
                        KeyCode.settingDown(index), "−", width = 1.5f,
                        style = KeyStyle.FUNCTION, repeatable = true,
                    ),
                    Key(
                        KeyCode.settingUp(index), "+", width = 1.5f,
                        style = KeyStyle.FUNCTION, repeatable = true,
                    ),
                )
            )
        }
        return rows + bottomRow(
            config,
            listOf(
                // Already in the panel, so holding this key goes one step further out.
                textLayerKey(textLayer, 1.25f, hold = KeyCode.SETTINGS_APP),
                Key(KeyCode.PAGE_PREV, "◀", width = 1.125f, style = KeyStyle.FUNCTION),
                Key(KeyCode.PAGE_NEXT, "▶", width = 1.125f, style = KeyStyle.FUNCTION),
            ),
            listOf(
                backspaceKey(width = 1f),
                Key(KeyCode.ENTER, width = 1.5f, style = KeyStyle.ACCENT),
            ),
        )
    }

    private fun textBottomLeft() = listOf(
        Key(
            KeyCode.TO_SYMBOLS, "?123", width = 1.25f, style = KeyStyle.FUNCTION,
            longPress = SETTINGS_HINT, longPressCode = KeyCode.SETTINGS,
        ),
        Key(KeyCode.LANGUAGE, "한/영", width = 1.25f, style = KeyStyle.FUNCTION),
        Key(','.code, ",", width = 1f),
    )

    /**
     * The window of the category currently on screen: 10 columns by [SymbolCatalog.ROWS] rows,
     * starting [scrollRow] rows into it. Slots past the end are blanks so a short category keeps
     * the same key width as a full one instead of stretching.
     */
    private fun symbolPadRows(
        config: KeyboardConfig,
        textLayer: Layer,
        page: SymbolCatalog.Page?,
        scrollRow: Int,
    ): List<KeyRow> {
        val symbols = page?.symbols.orEmpty()
        val first = scrollRow * SymbolCatalog.COLUMNS
        val gridRows = (0 until SymbolCatalog.ROWS).map { row ->
            KeyRow((0 until SymbolCatalog.COLUMNS).map { column ->
                val symbol = symbols.getOrNull(first + row * SymbolCatalog.COLUMNS + column)
                if (symbol == null) Key(KeyCode.NONE) else Key(symbol.codePointAt(0), symbol)
            })
        }
        return gridRows + bottomRow(
            config,
            listOf(
                textLayerKey(textLayer, 1.25f),
                // One tap per category. Not repeatable: a category is a destination, and there
                // are few enough of them that holding would just overshoot.
                Key(KeyCode.PAGE_PREV, "◀", width = 1.125f, style = KeyStyle.FUNCTION),
                Key(KeyCode.PAGE_NEXT, "▶", width = 1.125f, style = KeyStyle.FUNCTION),
            ),
            listOf(
                backspaceKey(width = 1f),
                Key(KeyCode.ENTER, width = 1.5f, style = KeyStyle.ACCENT),
            ),
        )
    }
}
