package art.boox.probe

import com.onyx.android.sdk.pen.TouchHelper

/** Firmware styles are not the same numeric IDs as the bitmap renderer's pen types. */
enum class BooxNotesPen(val style: Int, val textured: Boolean = false) {
    CALLIGRAPHY(TouchHelper.STROKE_STYLE_SQUARE_PEN),
    BALLPOINT(TouchHelper.STROKE_STYLE_PENCIL),
    PENCIL_FINE(TouchHelper.STROKE_STYLE_CHARCOAL_V2, true),
    PENCIL_COARSE(TouchHelper.STROKE_STYLE_CHARCOAL, true);

    fun maximumWidth(size: Float) = if (textured) size * 3f else size * 2f + 3f
}
