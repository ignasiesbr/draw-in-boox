package art.boox.probe

import com.onyx.android.sdk.pen.TouchHelper

/** App geometry type and firmware preview style have different numeric IDs. */
enum class BooxBrushFamily(val rendererType: Int, val previewStyle: Int, val label: String,
    val dry: Boolean = false, val appPreviewWidth: Boolean = false) {
    FOUNTAIN(2, TouchHelper.STROKE_STYLE_FOUNTAIN, "Fountain"),
    BRUSH(1, TouchHelper.STROKE_STYLE_NEO_BRUSH, "Brush"),
    // Solid PENCIL could share width but could not show graphite grain/opacity.
    // Trial the vendor's textured response; it must own its pressure curve.
    PENCIL(11, TouchHelper.STROKE_STYLE_CHARCOAL_V2, "Pencil", dry = true),
    CHARCOAL(12, TouchHelper.STROKE_STYLE_CHARCOAL, "Charcoal", dry = true);

    fun maximumWidth(size: Float): Float = when (this) {
        FOUNTAIN -> maxOf(size * 2f, size + 3f)
        BRUSH -> size * 2f + 3f
        PENCIL, CHARCOAL -> size * 2.5f
    }
}
