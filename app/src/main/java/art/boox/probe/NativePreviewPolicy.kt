package art.boox.probe

import android.graphics.Color

/** The firmware's gray/wide shader path crashed SurfaceFlinger on this tablet.
 * Keep the physically accepted black/narrow envelope; other brushes use app pixels.
 * This is an app compatibility policy, not a published hardware maximum. */
object NativePreviewPolicy {
    const val MAX_DOCUMENT_WIDTH = 30f
    fun supports(brush: PressureBrush) = !brush.isEraser && brush.inkColor == Color.BLACK &&
        brush.maxRenderedWidth <= MAX_DOCUMENT_WIDTH
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
