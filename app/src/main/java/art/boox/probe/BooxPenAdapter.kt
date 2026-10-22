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
        return try {
            val maximum = EpdController.getMaxTouchPressure()
            check(maximum.isFinite() && maximum > 0f) { "Firmware pressure maximum is invalid: $maximum" }
            val range = PressureRange(0f, maximum, "boox-firmware")
            val session = generation
            val transform = view.viewport.transform
            val created = TouchHelper.create(view, TouchHelper.FEATURE_SF_TOUCH_RENDER, callback(session, range, transform))
            helper = created
            // Use raw digitizer/SF rendering, excluding the SDK's AppTouchRender finger path.
            created.enableFingerTouch(false)
            val region = view.viewport.drawingRect()
            val limit = Rect(kotlin.math.ceil(region.left).toInt(), kotlin.math.ceil(region.top).toInt(),
                kotlin.math.floor(region.right).toInt(), kotlin.math.floor(region.bottom).toInt())
            check(!limit.isEmpty) { "Canvas is outside the drawing area" }
            created.setLimitRect(limit, emptyList())
                .openRawDrawing()
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

    private fun callback(session: Int, range: PressureRange, transform: ViewTransform) = object : RawInputCallback() {
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
                    view.commit(samples, "boox", range)
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
