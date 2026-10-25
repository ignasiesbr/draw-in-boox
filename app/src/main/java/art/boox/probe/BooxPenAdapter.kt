package art.boox.probe

import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.os.Looper
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList

/** SDK linkage and firmware failures leave the standard Android input path available. */
class BooxPenAdapter(private val view: RasterSurface, private val status: (String) -> Unit) {
    /** Simple overlay rectangles in surface-local coordinates, captured only at session open. */
    var inputExclusions: () -> List<Rect> = { emptyList() }
    private var helper: TouchHelper? = null
    @Volatile private var generation = 0
    private var liveWidth: LivePreviewWidth? = null
    private var presentation: StrokePresentation? = null
    @Volatile private var awaitingStrokeCommit = false
    val isBusy: Boolean get() = awaitingStrokeCommit
    private fun failure(error: Throwable) {
        Log.e("BooxProbe", "BOOX SDK failure", error)
        close()
        status("BOOX unavailable: ${error.javaClass.simpleName}. Android pen mode.")
    }

    fun open(): Boolean {
        close()
        if (!view.canDraw) return false
        if (!NativePreviewPolicy.supports(view.brush, view.document, view.viewport.transform.scale)) {
            Log.i("BooxProbe", "App brush preview: firmware shader workaround colorArgb=${Integer.toHexString(view.brush.inkColor)} finalMaxWidth=${view.brush.maxRenderedWidth} nativeMaxWidth=${view.brush.nativePreviewMaxWidth}")
            status(if (view.brush.isEraser) {
                if (NativePreviewPolicy.firmwareCrashGuardEnabled) "App eraser · needs ≤30px at this zoom, one full-opacity layer, alpha lock off"
                else "App eraser · needs one full-opacity layer, alpha lock off"
            }
                else "App preview · gray or wide brush")
            return false
        }
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
            val exclusions = inputExclusions().mapNotNull { rect ->
                Rect(rect).takeIf { it.intersect(limit) }
            }.ifEmpty { listOf(Rect(-1, -1, 0, 0)) }
            created.setLimitRect(limit, exclusions)
                .openRawDrawing()
            Log.i("BooxProbe", "Preview rectangular clip angle=${transform.rotationDegrees} exclusions=$exclusions setupMs=${android.os.SystemClock.elapsedRealtime() - clipStarted}")
            resumePreview(created)
            view.rawInput = created.isRawDrawingCreated && created.isRawDrawingInputEnabled
            val location = IntArray(2).also { view.getLocationOnScreen(it) }
            Log.i("BooxProbe", "rawCreated=${created.isRawDrawingCreated} input=${created.isRawDrawingInputEnabled} render=${created.isRawDrawingRenderEnabled} pressureMax=$maximum region=${view.width}x${view.height} origin=${location.joinToString()}")
            if (!view.rawInput) { close(); status("BOOX did not enable input. Android pen mode.") }
            else status(when {
                view.brush.isEraser -> "BOOX eraser · experimental white preview"
                view.brush.notesPen != null -> "BOOX ${view.brush.settings!!.preset.label} · original SDK ink"
                view.brush.usesBooxEngine -> "BOOX ${view.brush.booxFamily!!.label} · compare live and final stroke"
                view.document?.activeLayer?.alphaLocked == true -> "BOOX pen · alpha lock applied to committed ink"
                else -> "BOOX pen ready · pressure controls width"
            })
            view.rawInput
        } catch (error: LinkageError) { failure(error); false }
        catch (error: Exception) { failure(error); false }
    }

    fun close() {
        generation++
        val pendingPixels = presentation?.cancel()
        val pendingFrame = view.cancelAsyncPresentation()
        presentation = null
        awaitingStrokeCommit = false
        liveWidth?.finish(); liveWidth = null
        val previous = helper
        helper = null
        view.rawInput = false
        try { previous?.setRawDrawingEnabled(false); previous?.closeRawDrawing() }
        catch (error: LinkageError) { Log.w("BooxProbe", "Closing raw input", error) }
        catch (error: Exception) { Log.w("BooxProbe", "Closing raw input", error) }
        // Selection/layout/lifecycle can end a burst before the quiet timer fires.
        // Pixels are already committed; publish them after native drawing is closed.
        if (pendingFrame) view.render() else if (pendingPixels != null) view.render(pendingPixels)
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

    private fun resumePreview(target: TouchHelper, handoff: Boolean = false) {
        val color = checkNotNull(NativePreviewPolicy.color(view.brush, view.document, view.viewport.transform.scale)) {
            "Brush is outside the native preview policy"
        }
        val started = StrokePerformance.now()
        val width = view.brush.nativePreviewBaseWidth * view.viewport.transform.scale
        // Explicitly enable the reader on first open; the SDK call is a no-op when
        // already enabled at handoff. Shader parameters are set with rendering paused.
        NativePreviewConfiguration.apply(width, color,
            rendering = { target.setRawDrawingRenderEnabled(it) },
            setColor = { target.setStrokeColor(it) }, setWidth = { target.setStrokeWidth(it) },
            setPencil = { target.setStrokeStyle(view.brush.nativePreviewStyle) },
            enableInput = { target.setRawInputReaderEnable(true) })
        StrokePerformance.log("nativeResume handoff=$handoff configuredWhilePaused=true totalMs=${StrokePerformance.ms(started)}")
        Log.d("BooxProbe", "Hardware preview: style=${view.brush.booxFamily?.label ?: "pencil"} styleId=${view.brush.nativePreviewStyle} appWidth=${view.brush.hasDynamicWidth} baseViewWidth=$width finalBaseWidth=${view.brush.maxWidth} nativeMaxWidth=${view.brush.nativePreviewMaxWidth} zoom=${view.viewport.transform.scale} requestedColorArgb=${Integer.toHexString(color)} operation=${if (view.brush.isEraser) "erase" else "paint"}")
    }

    private fun callback(session: Int, range: PressureRange, transform: ViewTransform, layerId: String): RawInputCallback {
        val capturedBrush = view.brush
        var presentationTicket = 0L
        lateinit var presenter: StrokePresentation
        presenter = StrokePresentation(post = { task, delay -> view.postDelayed(task, delay) },
            remove = { view.removeCallbacks(it) }, present = { bounds, strokes ->
                if (session == generation && view.inputEnabled && view.rawInput) {
                    val ticket = ++presentationTicket
                    val started = StrokePerformance.now()
                    view.renderAsync(bounds) { success ->
                        if (session == generation && ticket == presentationTicket && view.inputEnabled && view.rawInput) {
                            if (!success || presenter.isBusy || awaitingStrokeCommit) {
                                presenter.restore(bounds, strokes)
                                StrokePerformance.log("presentation deferredAgain=true strokes=$strokes reason=writing-or-stale-frame")
                            } else {
                                val resumeStarted = StrokePerformance.now()
                                try { helper?.let { resumePreview(it, handoff = true) } }
                                catch (error: Exception) { failure(error) }
                                catch (error: LinkageError) { failure(error) }
                                StrokePerformance.log("presentation strokes=$strokes quietMs=${StrokePresentation.QUIET_MS} asyncElapsedMs=${StrokePerformance.ms(started, resumeStarted)} resumeMs=${StrokePerformance.ms(resumeStarted)}")
                            }
                        }
                    }
                }
            })
        presentation = presenter
        fun onUi(block: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) block() else view.post { block() }
        }
        val width = LivePreviewWidth.forBrush(capturedBrush, transform.scale,
            post = { task, delay ->
                // The pinned SFTouchRender already posts raw callbacks onto the UI thread.
                // Reposting begin let queued move/end callbacks cancel it before any width
                // was sent for short strokes. Apply begin immediately when already on UI.
                if (delay == 0L && Looper.myLooper() == Looper.getMainLooper()) task.run()
                else view.postDelayed(task, delay)
            }, remove = { view.removeCallbacks(it) },
            apply = { pixels ->
                if (session == generation && view.inputEnabled && view.rawInput) {
                    try { helper?.setStrokeWidth(pixels) }
                    catch (error: Exception) { failure(error) }
                    catch (error: LinkageError) { failure(error) }
                }
            })
        liveWidth = width
        fun sample(p: TouchPoint) = PenSample(p.x, p.y, range.normalize(p.pressure), p.timestamp,
            p.pressure, PenTilt.boox(p.tiltX, p.tiltY), rawTiltX = p.tiltX, rawTiltY = p.tiltY)
        return object : RawInputCallback() {
            private var moves = 0
            override fun onBeginRawDrawing(b: Boolean, p: TouchPoint) {
                if (session != generation) return
                awaitingStrokeCommit = true
                moves = 0
                width?.begin(sample(p))
                onUi { if (session == generation && view.inputEnabled && view.rawInput) {
                    presenter.begin(); view.beginRawStroke()
                } }
                Log.d("BooxProbe", "raw begin x=${p.x} y=${p.y} pressure=${p.pressure} pointTime=${p.timestamp} callbackWallTime=${System.currentTimeMillis()}")
            }
            override fun onEndRawDrawing(b: Boolean, p: TouchPoint) {
                if (session != generation) return
                width?.finish()
                onUi { if (session == generation) {
                    view.endRawStroke(); presenter.end()
                } }
                Log.d("BooxProbe", "raw end x=${p.x} y=${p.y} pressure=${p.pressure}")
            }
            override fun onRawDrawingTouchPointMoveReceived(p: TouchPoint) {
                if (session != generation) return
                moves++; width?.update(sample(p))
            }
            override fun onRawDrawingTouchPointListReceived(points: TouchPointList) {
                if (session != generation) return
                val received = StrokePerformance.now()
                width?.finish()
                Log.d("BooxProbe", "Live width preview moves=$moves widthUpdates=${width?.appliedCount ?: 0} fixedWidth=${width == null} sentWidthPx=${width?.appliedRange}")
                StrokePerformance.log("liveWidth moves=$moves updates=${width?.appliedCount ?: 0} fixedWidth=${width == null} maxScheduleLatenessMs=${width?.maxScheduleLatenessMs ?: 0.0} maxSetterMs=${width?.maxApplyMs ?: 0.0}")
                val samples = points.points.map {
                    transform.map(PenSample(it.x, it.y, range.normalize(it.pressure), it.timestamp, it.pressure,
                        PenTilt.boox(it.tiltX, it.tiltY), rawTiltX = it.tiltX, rawTiltY = it.tiltY))
                }
                val mapped = StrokePerformance.now()
                onUi {
                    val dequeued = StrokePerformance.now()
                    // Do not commit a callback queued by an old mode, surface, or paused activity.
                    if (session == generation && view.inputEnabled && view.rawInput) {
                        // Raw input uses the bounding rectangle; strokes starting in a gray
                        // corner must not become artwork. The bitmap clips crossings at its edge.
                        val first = samples.firstOrNull()
                        val spec = view.document?.spec
                        if (first != null && spec != null && first.x >= 0 && first.y >= 0 &&
                            first.x < spec.width && first.y < spec.height) {
                            view.commit(samples, "boox", range, layerId, capturedBrush,
                                deferPresentation = true)?.let { presenter.committed(it) }
                        } else if (spec != null) presenter.committed(RectF(0f, 0f, spec.width.toFloat(), spec.height.toFloat()))
                        awaitingStrokeCommit = false
                        StrokePerformance.log("handoffQueued samples=${samples.size} mapMs=${StrokePerformance.ms(received, mapped)} queueMs=${StrokePerformance.ms(mapped, dequeued)} commitMs=${StrokePerformance.ms(dequeued)} totalMs=${StrokePerformance.ms(received)}")
                    }
                }
            }
            override fun onBeginRawErasing(b: Boolean, p: TouchPoint) = Unit
            override fun onEndRawErasing(b: Boolean, p: TouchPoint) = Unit
            override fun onRawErasingTouchPointMoveReceived(p: TouchPoint) = Unit
            override fun onRawErasingTouchPointListReceived(points: TouchPointList) = Unit
        }
    }
}
