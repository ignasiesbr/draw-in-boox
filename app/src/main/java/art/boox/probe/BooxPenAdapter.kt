package art.boox.probe

import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList

/** SDK linkage and firmware failures leave the standard Android input path available. */
class BooxPenAdapter(private val view: RasterSurface, private val status: (String) -> Unit) {
    private var helper: TouchHelper? = null
    private var generation = 0
    private fun failure(error: Throwable) {
        Log.e("BooxProbe", "BOOX SDK failure", error)
        close()
        status("BOOX unavailable: ${error.javaClass.simpleName}. Android pen mode.")
    }

    fun open(): Boolean {
        close()
        if (!view.canDraw) return false
        return try {
            val maximum = EpdController.getMaxTouchPressure()
            check(maximum.isFinite() && maximum > 0f) { "Firmware pressure maximum is invalid: $maximum" }
            val range = PressureRange(0f, maximum, "boox-firmware")
            val session = generation
            val transform = view.viewport.transform
            val layerId = checkNotNull(view.document).layerId
            val created = TouchHelper.create(view, TouchHelper.FEATURE_SF_TOUCH_RENDER, callback(session, range, transform, layerId))
            helper = created
            // Use raw digitizer/SF rendering, excluding the SDK's AppTouchRender finger path.
            created.enableFingerTouch(false)
            val clipStarted = android.os.SystemClock.elapsedRealtime()
            val limit = Rect().also { view.viewport.drawingRect().roundOut(it) }
            check(!limit.isEmpty) { "Canvas is outside the drawing area" }
            // Large stepped polygon masks break raw callbacks when sent through
            // TouchHelper, and suppress live ink when sent to EpdController directly.
            // Use the known-working rectangular region for both paths. Bitmap clipping
            // remains exact; hardware ink can briefly cross an angled paper edge.
            val noExclusions = listOf(Rect(-1, -1, 0, 0))
            created.setLimitRect(limit, noExclusions)
                .openRawDrawing()
            Log.i("BooxProbe", "Preview rectangular clip angle=${transform.rotationDegrees} setupMs=${android.os.SystemClock.elapsedRealtime() - clipStarted}")
            resumePreview(created)
            view.rawInput = created.isRawDrawingCreated && created.isRawDrawingInputEnabled
            val location = IntArray(2).also { view.getLocationOnScreen(it) }
            Log.i("BooxProbe", "rawCreated=${created.isRawDrawingCreated} input=${created.isRawDrawingInputEnabled} render=${created.isRawDrawingRenderEnabled} pressureMax=$maximum region=${view.width}x${view.height} origin=${location.joinToString()}")
            if (!view.rawInput) { close(); status("BOOX did not enable input. Android pen mode.") }
            else status("BOOX pen ready · pressure controls width")
            view.rawInput
        } catch (error: LinkageError) { failure(error); false }
        catch (error: Exception) { failure(error); false }
    }

    fun close() {
        generation++
        val previous = helper
        helper = null
        view.rawInput = false
        try { previous?.setRawDrawingEnabled(false); previous?.closeRawDrawing() }
        catch (error: LinkageError) { Log.w("BooxProbe", "Closing raw input", error) }
        catch (error: Exception) { Log.w("BooxProbe", "Closing raw input", error) }
    }

    fun refresh() {
        try { EpdController.invalidate(view, com.onyx.android.sdk.api.device.epd.UpdateMode.GC) }
        catch (error: LinkageError) { failure(error) }
        catch (error: Exception) { failure(error) }
    }

    /** Best-effort fast black/white refresh scoped to the angle badge, never app-wide. */
    fun setIndicatorFastRefresh(indicator: android.view.View, enabled: Boolean) {
        try {
            if (enabled) EpdController.setViewDefaultUpdateMode(indicator, com.onyx.android.sdk.api.device.epd.UpdateMode.DU)
            else EpdController.resetViewUpdateMode(indicator)
        } catch (error: LinkageError) { Log.w("BooxProbe", "Angle indicator uses normal refresh", error) }
        catch (error: Exception) { Log.w("BooxProbe", "Angle indicator uses normal refresh", error) }
    }

    private fun resumePreview(target: TouchHelper) {
        target.setRawDrawingEnabled(true)
        // SFTouchRender.openDrawing() resets the firmware style to PENCIL. Apply the brush
        // after opening/resuming, as OnyxPenDemo does, including every bitmap handoff.
        val width = view.brush.maxWidth * view.viewport.transform.scale
        target.setStrokeColor(Color.BLACK)
            .setStrokeWidth(width)
            .setStrokeStyle(TouchHelper.STROKE_STYLE_FOUNTAIN)
        Log.d("BooxProbe", "Hardware preview: fountain viewWidth=$width zoom=${view.viewport.transform.scale}")
    }

    private fun callback(session: Int, range: PressureRange, transform: ViewTransform, layerId: String) = object : RawInputCallback() {
        override fun onBeginRawDrawing(b: Boolean, p: TouchPoint) {
            view.post { if (session == generation && view.inputEnabled && view.rawInput) view.beginRawStroke() }
            Log.d("BooxProbe", "raw begin x=${p.x} y=${p.y} pressure=${p.pressure}")
        }
        override fun onEndRawDrawing(b: Boolean, p: TouchPoint) {
            view.post { if (session == generation) view.endRawStroke() }
            Log.d("BooxProbe", "raw end x=${p.x} y=${p.y} pressure=${p.pressure}")
        }
        override fun onRawDrawingTouchPointMoveReceived(p: TouchPoint) = Unit
        override fun onRawDrawingTouchPointListReceived(points: TouchPointList) {
            val samples = points.points.map {
                transform.map(PenSample(it.x, it.y, range.normalize(it.pressure), it.timestamp, it.pressure))
            }
            view.post {
                // Do not commit a callback queued by an old mode, surface, or paused activity.
                if (session == generation && view.inputEnabled && view.rawInput) {
                    // Raw input uses the bounding rectangle; strokes starting in a gray
                    // corner must not become artwork. The bitmap clips crossings at its edge.
                    val first = samples.firstOrNull()
                    val spec = view.document?.spec
                    if (first != null && spec != null && first.x >= 0 && first.y >= 0 &&
                        first.x < spec.width && first.y < spec.height) {
                        view.commit(samples, "boox", range, layerId)
                    } else view.render()
                    try {
                        helper?.let { active -> active.setRawDrawingEnabled(false); resumePreview(active) }
                    }
                    catch (error: Exception) { failure(error) }
                    catch (error: LinkageError) { failure(error) }
                }
            }
        }
        override fun onBeginRawErasing(b: Boolean, p: TouchPoint) = Unit
        override fun onEndRawErasing(b: Boolean, p: TouchPoint) = Unit
        override fun onRawErasingTouchPointMoveReceived(p: TouchPoint) = Unit
        override fun onRawErasingTouchPointListReceived(points: TouchPointList) = Unit
    }
}
