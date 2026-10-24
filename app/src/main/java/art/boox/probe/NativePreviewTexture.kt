package art.boox.probe

import com.onyx.android.sdk.pen.TouchHelper

/** Preview-only firmware texture; never changes the committed brush. */
enum class NativePreviewTexture(val id: String, val label: String, val style: Int) {
    FINE("fine", "Fine grain", TouchHelper.STROKE_STYLE_CHARCOAL_V2),
    COARSE("coarse", "Coarse grain", TouchHelper.STROKE_STYLE_CHARCOAL);

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: FINE
    }
}
