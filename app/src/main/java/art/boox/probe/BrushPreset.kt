package art.boox.probe

import android.content.Context

enum class BrushPreset(val id: String, val label: String, val icon: UiIcon, val maxSize: Float = 12f) {
    INK("ink", "Ink", UiIcon.INK),
    TILT("tilt", "Tilt", UiIcon.TILT),
    // Keep the stored ID so existing Monoline size/dynamics preferences survive.
    MONOLINE("monoline", "Marker", UiIcon.MARKER, 128f),
    ERASER("eraser", "Eraser", UiIcon.ERASER, 128f),
    LAB_FOUNTAIN("lab_fountain", "Fountain Lab", UiIcon.MONOLINE);

    fun defaults() = BrushSettings(id, size = if (this == MONOLINE || this == ERASER) 32f else 12f,
        pressure = this == INK || this == TILT || this == LAB_FOUNTAIN, tilt = this == TILT)
    companion object {
        val available get() = entries.filter { it != LAB_FOUNTAIN || BuildConfig.BOOX_LAB }
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: TILT
    }
}

/** Serializable settings; no renderer resources or mutable stroke state. */
data class BrushSettings(val presetId: String = "tilt", val size: Float = 12f,
    val pressure: Boolean = true, val tilt: Boolean = true, val shade: InkShade = InkShade.BLACK) {
    init {
        require(BrushPreset.entries.any { it.id == presetId })
        require(size.isFinite() && size in 1f..preset.maxSize)
    }
    val preset get() = BrushPreset.fromId(presetId)
    fun compile() = PressureBrush(if (pressure && preset != BrushPreset.ERASER) 1f else size, size,
        if (tilt && preset != BrushPreset.ERASER) TiltWidthModifier() else NoWidthModifier, this)
}

/** Versioned app preferences, independent of artwork and undo. */
class BrushSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    fun selected() = BrushPreset.fromId(preferences.getString("brush_selected", null))
    fun load(preset: BrushPreset): BrushSettings {
        val defaults = preset.defaults()
        if (preferences.getInt("brush_schema", 1) !in 1..2) return defaults
        val size = preferences.getFloat("brush_${preset.id}_size", defaults.size)
        return defaults.copy(size = if (size.isFinite() && size in 1f..preset.maxSize) size else defaults.size,
            pressure = preset != BrushPreset.ERASER && preferences.getBoolean("brush_${preset.id}_pressure", defaults.pressure),
            tilt = preset != BrushPreset.ERASER && preferences.getBoolean("brush_${preset.id}_tilt", defaults.tilt),
            shade = InkShade.fromLevel(preferences.getInt("ink_gray_level", 0)))
    }
    fun save(settings: BrushSettings) {
        preferences.edit().putInt("brush_schema", 2).putString("brush_selected", settings.presetId)
            .putFloat("brush_${settings.presetId}_size", settings.size)
            .putBoolean("brush_${settings.presetId}_pressure", settings.pressure)
            .putBoolean("brush_${settings.presetId}_tilt", settings.tilt)
            .putInt("ink_gray_level", settings.shade.level).apply()
    }
}
