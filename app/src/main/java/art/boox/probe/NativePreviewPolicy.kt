package art.boox.probe

import android.graphics.Color

/** Keep the accepted black/narrow path. User-authorized white eraser experiment
 * is restricted to cases where transparency reveals only white paper. */
object NativePreviewPolicy {
    /** Cached process-wide preference; never read preferences in the input path. */
    @Volatile var firmwareCrashGuardEnabled = true
        private set
    fun configure(settings: AppSettings) { firmwareCrashGuardEnabled = settings.firmwareCrashGuardEnabled }
    const val MAX_DOCUMENT_WIDTH = 30f
    fun color(brush: PressureBrush, document: DrawingDocument? = null, scale: Float = 1f): Int? =
        colorWithGuard(firmwareCrashGuardEnabled, brush, document, scale)

    /** Explicit policy modes let tests cover guard-off without opening unsafe SDK sessions. */
    internal fun colorWithGuard(enforceCrashGuard: Boolean, brush: PressureBrush,
        document: DrawingDocument? = null, scale: Float = 1f): Int? {
        if (brush.isEraser) {
            if (!scale.isFinite() || scale <= 0f) return null
            if (enforceCrashGuard && (brush.maxRenderedWidth > MAX_DOCUMENT_WIDTH ||
                brush.maxRenderedWidth * scale > MAX_DOCUMENT_WIDTH)) return null
            // These restrictions protect erasing semantics in either crash-guard mode.
            val layer = document?.layers?.singleOrNull() ?: return null
            return if (document.canPaint && !layer.alphaLocked && layer.opacity == 100) Color.WHITE else null
        }
        return if (!enforceCrashGuard || (brush.inkColor == Color.BLACK && brush.nativePreviewMaxWidth <= MAX_DOCUMENT_WIDTH))
            brush.inkColor else null
    }
    fun supports(brush: PressureBrush, document: DrawingDocument? = null, scale: Float = 1f) =
        color(brush, document, scale) != null
}

/** SDK parameters can rebuild native shaders. Configure only with drawing paused,
 * then enable once all parameters are ready. Never mutate color/style at pen-down. */
object NativePreviewConfiguration {
    fun apply(width: Float, color: Int, rendering: (Boolean) -> Unit,
        setColor: (Int) -> Unit, setWidth: (Float) -> Unit, setPencil: () -> Unit,
        enableInput: () -> Unit) {
        require(width.isFinite() && width > 0f)
        rendering(false)
        setColor(color)
        setWidth(width)
        setPencil()
        // openRawDrawing starts native resources but does not set TouchHelper's
        // input-enabled flag. Rendering and input are separate SDK state machines.
        enableInput()
        rendering(true)
    }
}
