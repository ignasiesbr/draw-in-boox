package art.boox.probe

import android.content.Context

enum class BrushPreset(val id: String, val label: String, val icon: UiIcon, val maxSize: Float = 12f,
    val booxFamily: BooxBrushFamily? = null, val notesPen: BooxNotesPen? = null) {
    INK("ink", "Ink", UiIcon.INK),
    TILT("tilt", "Tilt", UiIcon.TILT),
    // Keep the stored ID so existing Monoline size/dynamics preferences survive.
    MONOLINE("monoline", "Marker", UiIcon.MARKER, 128f),
    ERASER("eraser", "Eraser", UiIcon.ERASER, 128f),
    LAB_FOUNTAIN("lab_fountain", "Fountain Lab", UiIcon.MONOLINE, booxFamily = BooxBrushFamily.FOUNTAIN),
    LAB_BRUSH("lab_brush", "Brush", UiIcon.BRUSH, booxFamily = BooxBrushFamily.BRUSH),
    LAB_PENCIL("lab_pencil", "Pencil", UiIcon.PENCIL, 64f, BooxBrushFamily.PENCIL),
    LAB_CHARCOAL("lab_charcoal", "Charcoal", UiIcon.CHARCOAL, 128f, BooxBrushFamily.CHARCOAL),
    BOOX_CALLIGRAPHY("boox_calligraphy", "Calligraphy", UiIcon.CALLIGRAPHY, notesPen = BooxNotesPen.CALLIGRAPHY),
    BOOX_BALLPOINT("boox_ballpoint", "Ballpoint", UiIcon.BALLPOINT, notesPen = BooxNotesPen.BALLPOINT),
    BOOX_PENCIL_FINE("boox_pencil_fine", "Pencil · fine", UiIcon.PENCIL, 64f, notesPen = BooxNotesPen.PENCIL_FINE),
    BOOX_PENCIL_COARSE("boox_pencil_coarse", "Pencil · coarse", UiIcon.CHARCOAL, 64f, notesPen = BooxNotesPen.PENCIL_COARSE);

    fun defaults() = BrushSettings(id, size = when (this) { MONOLINE, ERASER -> 32f; LAB_PENCIL -> 8f; LAB_CHARCOAL -> 10f; BOOX_BALLPOINT -> 3f; BOOX_PENCIL_FINE, BOOX_PENCIL_COARSE -> 6f; BOOX_CALLIGRAPHY -> 8f; else -> 12f },
        pressure = this == INK || this == TILT || booxFamily != null, tilt = this == TILT || booxFamily?.dry == true)
    companion object {
        val notesToolbar get() = listOf(MONOLINE, BOOX_CALLIGRAPHY, LAB_FOUNTAIN, INK, LAB_BRUSH,
            BOOX_BALLPOINT, BOOX_PENCIL_FINE, BOOX_PENCIL_COARSE).filter { it in available }
        val available get() = entries.filter { it.booxFamily == null || (BuildConfig.BOOX_LAB &&
            (it.booxFamily.dry.not() || BuildConfig.RENDERER_BACKEND == "recovered")) }
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: TILT
    }
}

/** Serializable settings; no renderer resources or mutable stroke state. */
data class BrushSettings(val presetId: String = "tilt", val size: Float = 12f,
    val pressure: Boolean = true, val tilt: Boolean = true, val shade: InkShade = InkShade.BLACK,
    val nativePreviewScale: Float = if (BrushPreset.fromId(presetId).booxFamily?.dry == true) 0.5f else 1f,
    val nativePreviewTexture: NativePreviewTexture = NativePreviewTexture.FINE) {
    init {
        require(BrushPreset.entries.any { it.id == presetId })
        require(size.isFinite() && size in 1f..preset.maxSize)
        require(nativePreviewScale.isFinite() && nativePreviewScale in 0.25f..1f)
    }
    val preset get() = BrushPreset.fromId(presetId)
    fun compile() = PressureBrush(if (pressure && preset != BrushPreset.ERASER) 1f else size, size,
        if (tilt && preset != BrushPreset.ERASER) TiltWidthModifier() else NoWidthModifier, this)
}

/** Versioned defaults for legacy/new documents. Document pen state is authoritative after load. */
class BrushSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    fun selected() = BrushPreset.fromId(preferences.getString("brush_selected", null))
        .takeIf { it in BrushPreset.available } ?: BrushPreset.TILT
    fun load(preset: BrushPreset): BrushSettings {
        val defaults = preset.defaults()
        if (preferences.getInt("brush_schema", 1) !in 1..4) return defaults
        val size = preferences.getFloat("brush_${preset.id}_size", defaults.size)
        val previewScale = preferences.getFloat("brush_${preset.id}_native_scale", defaults.nativePreviewScale)
        return defaults.copy(size = if (size.isFinite() && size in 1f..preset.maxSize) size else defaults.size,
            pressure = preset != BrushPreset.ERASER && preferences.getBoolean("brush_${preset.id}_pressure", defaults.pressure),
            tilt = preset != BrushPreset.ERASER && preferences.getBoolean("brush_${preset.id}_tilt", defaults.tilt),
            shade = InkShade.fromLevel(preferences.getInt("brush_${preset.id}_gray_level",
                preferences.getInt("ink_gray_level", 0))),
            nativePreviewScale = previewScale.takeIf { it.isFinite() && it in 0.25f..1f } ?: defaults.nativePreviewScale,
            nativePreviewTexture = NativePreviewTexture.fromId(preferences.getString("brush_${preset.id}_native_texture", null)))
    }
    fun save(settings: BrushSettings) {
        val editor = preferences.edit()
        // Freeze every legacy shared shade before saving the first independent pen color.
        // Keep the legacy key unchanged so an untouched pen cannot inherit a later edit.
        val legacyShade = preferences.getInt("ink_gray_level", 0)
        BrushPreset.entries.forEach { preset ->
            val key = "brush_${preset.id}_gray_level"
            if (!preferences.contains(key)) editor.putInt(key, legacyShade)
        }
        editor.putInt("brush_schema", 4).putString("brush_selected", settings.presetId)
            .putFloat("brush_${settings.presetId}_size", settings.size)
            .putBoolean("brush_${settings.presetId}_pressure", settings.pressure)
            .putBoolean("brush_${settings.presetId}_tilt", settings.tilt)
            .putFloat("brush_${settings.presetId}_native_scale", settings.nativePreviewScale)
            .putString("brush_${settings.presetId}_native_texture", settings.nativePreviewTexture.id)
            .putInt("brush_${settings.presetId}_gray_level", settings.shade.level).apply()
    }

    fun snapshot() = PenSettings(selected().id, BrushPreset.entries.associate { it.id to load(it) })
}
