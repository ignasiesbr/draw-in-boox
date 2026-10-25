package art.boox.probe

import android.util.Log

/** Read once when compiling a selected brush, never from input callbacks. The
 * installed Notes app uses these firmware parameters for square nibs and tilt. */
internal data class BooxNotesConfig(val tiltEnabled: Boolean = false, val tiltScale: Float = 3f,
    val ratio: Float = 10f, val angle: Float = 45f, val smoothing: Float = 0.6f) {
    companion object {
        fun read(family: BooxNotesPen): BooxNotesConfig {
            if (family == BooxNotesPen.BALLPOINT) return BooxNotesConfig()
            val parameters = try {
                Class.forName("android.onyx.ViewUpdateHelper")
                    .getDeclaredMethod("getStrokeParameters", Int::class.javaPrimitiveType)
                    .invoke(null, family.style) as? FloatArray
            } catch (error: ReflectiveOperationException) {
                Log.w("BooxProbe", "BOOX brush parameters unavailable", error); null
            } catch (error: LinkageError) {
                Log.w("BooxProbe", "BOOX brush parameters unavailable", error); null
            } catch (error: SecurityException) {
                Log.w("BooxProbe", "BOOX brush parameters unavailable", error); null
            }
            return fromParameters(family, parameters).also {
                Log.i("BooxProbe", "BOOX notes config family=$family raw=${parameters?.joinToString()} config=$it")
            }
        }

        internal fun fromParameters(family: BooxNotesPen, parameters: FloatArray?): BooxNotesConfig {
            fun value(index: Int, fallback: Float, range: ClosedFloatingPointRange<Float>) =
                parameters?.getOrNull(index)?.takeIf { it.isFinite() && it in range } ?: fallback
            return if (family.textured) BooxNotesConfig(
                tiltEnabled = parameters?.getOrNull(0)?.let { it.isFinite() && it != 0f } ?: false,
                tiltScale = value(1, 3f, 1f..10f))
            else BooxNotesConfig(ratio = value(1, 10f, 1f..100f), angle = value(2, 45f, -180f..180f),
                smoothing = value(3, 0.6f, 0f..1f))
        }
    }
}
