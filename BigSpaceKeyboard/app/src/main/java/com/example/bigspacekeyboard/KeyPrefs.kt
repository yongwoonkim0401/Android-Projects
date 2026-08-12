package com.example.bigspacekeyboard

import android.content.Context

/** SharedPreferences-backed [KeyboardConfig]; the IME reloads it every time it is shown. */
object KeyPrefs {

    private const val FILE = "keyboard_prefs"
    private const val KEY_HEIGHT = "key_height_ratio"
    private const val SPACE_WIDTH = "space_width_units"
    private const val SPACE_HEIGHT = "space_height_weight"
    private const val HINT_SIZE = "hint_size_ratio"
    private const val PREVIEW = "preview_enabled"
    private const val SOUND = "sound_volume"
    private const val VIBRATE = "vibrate_strength"
    private const val SWIPE = "space_cursor_swipe"
    private const val HANGUL = "text_layer_hangul"
    private const val DOUBLE_TAP = "double_tap_jamo"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): KeyboardConfig {
        val p = prefs(context)
        val defaults = KeyboardConfig()
        return KeyboardConfig(
            keyHeightRatio = p.getFloat(KEY_HEIGHT, defaults.keyHeightRatio),
            spaceWidthUnits = p.getFloat(SPACE_WIDTH, defaults.spaceWidthUnits),
            spaceHeightWeight = p.getFloat(SPACE_HEIGHT, defaults.spaceHeightWeight),
            hintSizeRatio = p.getFloat(HINT_SIZE, defaults.hintSizeRatio),
            previewEnabled = p.getBoolean(PREVIEW, defaults.previewEnabled),
            soundVolume = p.getFloat(SOUND, defaults.soundVolume),
            vibrateStrength = p.getFloat(VIBRATE, defaults.vibrateStrength),
            spaceCursorSwipe = p.getBoolean(SWIPE, defaults.spaceCursorSwipe),
            doubleTapJamo = p.getBoolean(DOUBLE_TAP, defaults.doubleTapJamo),
        )
    }

    fun save(context: Context, config: KeyboardConfig) {
        prefs(context).edit()
            .putFloat(KEY_HEIGHT, config.keyHeightRatio)
            .putFloat(SPACE_WIDTH, config.spaceWidthUnits)
            .putFloat(SPACE_HEIGHT, config.spaceHeightWeight)
            .putFloat(HINT_SIZE, config.hintSizeRatio)
            .putBoolean(PREVIEW, config.previewEnabled)
            .putFloat(SOUND, config.soundVolume)
            .putFloat(VIBRATE, config.vibrateStrength)
            .putBoolean(SWIPE, config.spaceCursorSwipe)
            .putBoolean(DOUBLE_TAP, config.doubleTapJamo)
            .apply()
    }

    /** Which of the two text layers the keyboard should come up in. */
    fun loadTextLayer(context: Context): Layer =
        if (prefs(context).getBoolean(HANGUL, false)) Layer.HANGUL else Layer.LETTERS

    fun saveTextLayer(context: Context, layer: Layer) {
        prefs(context).edit().putBoolean(HANGUL, layer == Layer.HANGUL).apply()
    }
}
