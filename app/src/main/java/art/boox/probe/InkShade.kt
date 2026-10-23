package art.boox.probe

import android.graphics.Color

/** Opaque ink tones. White/paper and brush opacity are separate tool concepts. */
enum class InkShade(val level: Int, val label: String) {
    VERY_LIGHT(238, "Very light gray"), LIGHT(204, "Light gray"),
    PALE(170, "Pale gray"), MEDIUM(136, "Medium gray"),
    DARK(102, "Dark gray"), DEEP(68, "Deep gray"),
    NEAR_BLACK(34, "Near black"), BLACK(0, "Black");

    val argb: Int get() = Color.rgb(level, level, level)
    companion object {
        fun fromLevel(level: Int) = entries.firstOrNull { it.level == level } ?: BLACK
    }
}
