package art.boox.probe

import org.json.JSONObject

/** Immutable document tool state, separate from raster history and firmware/app preferences. */
data class PenSettings(val selectedId: String = "ink",
    val pens: Map<String, BrushSettings> = BrushPreset.entries.associate { it.id to it.defaults() }) {
    init {
        require(BrushPreset.entries.any { it.id == selectedId })
        require(pens.all { (id, settings) -> id == settings.presetId })
    }

    fun get(preset: BrushPreset) = pens[preset.id] ?: preset.defaults()
    fun with(settings: BrushSettings) = copy(selectedId = settings.presetId,
        pens = pens + (settings.presetId to settings))

    fun toJson() = JSONObject().put("version", 1).put("selectedPenId", selectedId)
        .put("pens", JSONObject().apply {
            BrushPreset.entries.forEach { preset ->
                val settings = get(preset)
                put(preset.id, JSONObject().put("size", settings.size).put("shade", settings.shade.level)
                    .put("pressure", settings.pressure).put("tilt", settings.tilt)
                    .put("nativePreviewScale", settings.nativePreviewScale)
                    .put("nativePreviewTexture", settings.nativePreviewTexture.id))
            }
        })

    companion object {
        /** Optional metadata must never prevent opening raster artwork. Unknown pens are ignored;
         * missing or invalid known fields use preset defaults, independent of device preferences. */
        fun fromJson(json: JSONObject?): PenSettings? {
            if (json == null || json.opt("version") != 1) return null
            val items = json.optJSONObject("pens")
            val pens = BrushPreset.entries.associate { preset ->
                val defaults = preset.defaults()
                val item = items?.optJSONObject(preset.id)
                fun number(key: String, default: Float, range: ClosedFloatingPointRange<Float>): Float {
                    val value = (item?.opt(key) as? Number)?.toFloat()
                    return value?.takeIf { it.isFinite() && it in range } ?: default
                }
                val shade = (item?.opt("shade") as? Number)?.toDouble()
                preset.id to defaults.copy(
                    size = number("size", defaults.size, 1f..preset.maxSize),
                    shade = InkShade.entries.firstOrNull { it.level.toDouble() == shade } ?: defaults.shade,
                    pressure = preset != BrushPreset.ERASER && (item?.opt("pressure") as? Boolean ?: defaults.pressure),
                    tilt = preset != BrushPreset.ERASER && (item?.opt("tilt") as? Boolean ?: defaults.tilt),
                    nativePreviewScale = number("nativePreviewScale", defaults.nativePreviewScale, 0.25f..1f),
                    nativePreviewTexture = NativePreviewTexture.fromId(item?.optString("nativePreviewTexture")))
            }
            val selected = json.optString("selectedPenId")
                .takeIf { id -> BrushPreset.entries.any { it.id == id } } ?: "ink"
            return PenSettings(selected, pens)
        }
    }
}
