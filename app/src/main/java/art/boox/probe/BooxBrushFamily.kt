package art.boox.probe

import com.onyx.android.sdk.pen.TouchHelper

/** App geometry type and firmware preview style have different numeric IDs. */
enum class BooxBrushFamily(val rendererType: Int, val previewStyle: Int, val label: String) {
    FOUNTAIN(2, TouchHelper.STROKE_STYLE_FOUNTAIN, "Fountain"),
    BRUSH(1, TouchHelper.STROKE_STYLE_NEO_BRUSH, "Brush");

    fun maximumWidth(size: Float): Float = when (this) {
        FOUNTAIN -> maxOf(size * 2f, size + 3f)
        BRUSH -> size * 2f + 3f
    }
}
