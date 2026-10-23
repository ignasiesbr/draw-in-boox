package art.boox.probe

import android.content.Context
import android.graphics.*
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewConfiguration
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.abs

/** The document owns pixels; this SurfaceView owns only display and pen interaction. */
class RasterSurface(
    context: Context,
    private val defaultSpec: CanvasSpec = CanvasSizes.presets.first().spec,
    private val strokeFinished: (PenStroke) -> Unit
) : SurfaceView(context), SurfaceHolder.Callback {
    var document: DrawingDocument? = null
        private set
    val viewport = DocumentViewport()
    var brush = BrushSettings().compile()
        private set
    private var strokeBrush = brush
    val history = EditHistory()
    var historyChanged: (() -> Unit)? = null
    var historyFeedback: ((String) -> Unit)? = null
    private val store = DocumentStore(context.filesDir)
    private val pending = mutableListOf<PenSample>()
    private var activePointer = -1
    private var rawStroke = false
    val isDrawing: Boolean get() = activePointer != -1 || rawStroke
    private var pressureRange = PressureRange.ANDROID_DEFAULT
    private var strokeTransform = ViewTransform(1f, 0f, 0f)
    private var strokeLayerId: String? = null
    val canDraw: Boolean get() = document?.canPaint == true
    var layersChanged: (() -> Unit)? = null
    private var framePending = false
    private var softwareFrameInFlight = false
    private var softwareNeedsFrame = false
    private var softwareDirty: RectF? = null
    private var softwareEpoch = 0L
    private val frame = Runnable { renderScheduledFrame() }
    private val paper = Paint().apply { color = Color.WHITE }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val surfacePaintLock = Any()
    @Volatile private var frameEpoch = 0L
    private var asyncPainter: AsyncSurfacePresentation? = null
    private var asyncCacheEpoch = -1L
    private var asyncSequence = 0L
    private var asyncDirty: Rect? = null
    private var loadFailed = false
    var inputEnabled = true
        set(value) { field = value; if (!value) { historyTaps.cancel(); gestures.cancel(); cancelStroke() } }
    var rawInput = false
        set(value) { if (field != value) cancelStroke(); field = value }
    var ready: (() -> Unit)? = null
    var unavailable: (() -> Unit)? = null
    var viewportWillChange: (() -> Unit)? = null
    var navigationStarted: (() -> Unit)? = null
    var rotationPreviewChanged: ((Float?) -> Unit)? = null
    var zoomPreviewChanged: ((ZoomLevel?) -> Unit)? = null
    var zoomPreviewLevel: ZoomLevel? = null
        private set
    var rotationPreviewAngle: Float? = null
        private set
    private var navigationStartAngle = 0f
    private var navigationStartScale = 1f
    private var zoomAnchor = PointF()
    private var rotationSnappedToZero = false
    val isNavigating: Boolean get() = gestures.active
    private val gestures = CanvasGestures(ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
        minRotationSpan = 48f * resources.displayMetrics.density,
        begin = {
            if (isDrawing || document == null) false
            else {
                navigationStartAngle = viewport.rotationDegrees
                navigationStartScale = viewport.transform.scale
                rotationSnappedToZero = false
                historyTaps.cancel()
                navigationStarted?.invoke(); true
            }
        },
        pan = { dx, dy -> viewport.panBy(dx, dy); requestFrame() },
        rotation = { delta ->
            val target = DocumentViewport.normalizeAngle(navigationStartAngle + delta)
            // Enter within 4 degrees; stay caught until 7 degrees to resist finger jitter.
            // Use the unrounded target so integer display rounding cannot move the boundary.
            rotationSnappedToZero = abs(target) <= if (rotationSnappedToZero) 7f else 4f
            val angle = if (rotationSnappedToZero) 0f
                else DocumentViewport.normalizeAngle(target.roundToInt().toFloat())
            if (angle != rotationPreviewAngle) {
                rotationPreviewAngle = angle
                rotationPreviewChanged?.invoke(angle)
            }
        },
        zoom = { ratio, x, y ->
            if (zoomPreviewLevel == null) zoomAnchor = PointF(x, y)
            val level = ZoomLevels.nearest(navigationStartScale * ratio)
            if (level != zoomPreviewLevel) {
                zoomPreviewLevel = level
                zoomPreviewChanged?.invoke(level)
            }
        },
        end = { commit ->
            val angle = rotationPreviewAngle
            val zoom = zoomPreviewLevel
            rotationPreviewAngle = null
            zoomPreviewLevel = null
            rotationSnappedToZero = false
            rotationPreviewChanged?.invoke(null)
            zoomPreviewChanged?.invoke(null)
            if (commit && angle != null) viewport.rotateTo(angle)
            if (commit && zoom != null) viewport.zoomTo(zoom.scale, zoomAnchor.x, zoomAnchor.y)
            render(); ready?.invoke()
        })
    var error: ((String) -> Unit)? = null
    private val historyTaps = HistoryTapGestures(ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
        ViewConfiguration.getDoubleTapTimeout().toLong(),
        eligible = { inputEnabled && document != null && !isDrawing && !isNavigating },
        post = { task, delay -> postDelayed(task, delay) }, remove = { removeCallbacks(it) },
        undo = { if (!undo()) historyFeedback?.invoke("Nothing to undo") },
        redo = { if (!redo()) historyFeedback?.invoke("Nothing to redo") },
        trace = { Log.d("BooxProbe", it) })

    init { tag = "Drawing surface"; holder.addCallback(this) }

    override fun surfaceCreated(holder: SurfaceHolder) = render()
    override fun surfaceDestroyed(holder: SurfaceHolder) { invalidateAsyncFrames(); historyTaps.cancel(); gestures.cancel(); cancelStroke(); unavailable?.invoke() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0 || loadFailed) return
        historyTaps.cancel()
        gestures.cancel()
        cancelStroke()
        try {
            val loaded = if (document == null) store.loadOrCreate(defaultSpec) else null
            if (loaded != null) document = loaded.document
            if (loaded != null) layersChanged?.invoke()
            viewport.resize(checkNotNull(document).spec, width, height, loaded?.viewport)
            render()
            ready?.invoke()
        } catch (failure: Exception) {
            loadFailed = true
            inputEnabled = false
            Log.e("BooxProbe", "Document load failed; existing checkpoint retained", failure)
            error?.invoke("Cannot open drawing: ${failure.message}. Existing files were retained.")
        }
    }

    fun createDocument(spec: CanvasSpec) {
        check(!isDrawing && !isNavigating) { "Finish drawing or navigation before creating a canvas." }
        document?.releaseComposite()
        val replacement = DrawingDocument.create(spec)
        try { store.save(replacement, null) } catch (failure: Throwable) { replacement.recycle(); throw failure }
        viewportWillChange?.invoke()
        historyTaps.cancel()
        cancelStroke()
        history.clear(); historyChanged?.invoke()
        document?.recycle()
        document = replacement
        layersChanged?.invoke()
        loadFailed = false
        viewport.resize(spec, viewport.viewWidth.coerceAtLeast(1), viewport.viewHeight.coerceAtLeast(1),
            ViewportState(1f, spec.width / 2f, spec.height / 2f, true))
        render()
        ready?.invoke()
    }

    fun zoom(action: ZoomAction): Boolean {
        if (isDrawing || isNavigating || document == null) return false
        viewportWillChange?.invoke()
        viewport.change(action)
        render()
        ready?.invoke()
        return true
    }

    fun rotateView(angle: Float): Boolean {
        if (isDrawing || isNavigating || document == null) return false
        viewportWillChange?.invoke()
        viewport.rotateTo(angle)
        render()
        ready?.invoke()
        return true
    }

    fun beginRawStroke() { historyTaps.cancel(); rawStroke = true }
    fun endRawStroke() { rawStroke = false }

    fun selectBrush(settings: BrushSettings): Boolean {
        if (isDrawing || isNavigating) return false
        val compiled = settings.compile()
        viewportWillChange?.invoke()
        brush = compiled
        ready?.invoke()
        return true
    }

    /** Samples committed here are always in document pixels. */
    fun commit(samples: List<PenSample>, source: String, range: PressureRange = PressureRange.ANDROID_DEFAULT,
        expectedLayerId: String? = null, capturedBrush: PressureBrush = brush,
        deferPresentation: Boolean = false): RectF? {
        val current = document ?: return null
        if (!current.canPaint || (expectedLayerId != null && current.layerId != expectedLayerId)) { render(); return null }
        val valid = samples.filter { it.x.isFinite() && it.y.isFinite() }
        if (valid.isEmpty()) return null
        val started = StrokePerformance.now()
        historyTaps.cancel()
        history.execute(current, StrokeEdit(valid, capturedBrush))
        val edited = StrokePerformance.now()
        historyChanged?.invoke()
        val notified = StrokePerformance.now()
        val dirty = StrokeDirtyBounds.document(valid, capturedBrush.maxRenderedWidth)
        if (!deferPresentation) render(dirty)
        val rendered = StrokePerformance.now()
        strokeFinished(PenStroke(valid, source, range, capturedBrush))
        StrokePerformance.log("commit source=$source samples=${valid.size} deferred=$deferPresentation historyMs=${StrokePerformance.ms(started, edited)} controlsMs=${StrokePerformance.ms(edited, notified)} renderMs=${StrokePerformance.ms(notified, rendered)} diagnosticsMs=${StrokePerformance.ms(rendered)} totalMs=${StrokePerformance.ms(started)}")
        return dirty
    }

    fun selectLayer(id: String): Boolean {
        val current = document ?: return false
        if (isDrawing || isNavigating) return false
        if (current.layerId == id) return true
        viewportWillChange?.invoke(); historyTaps.cancel(); cancelStroke()
        current.selectLayer(id)
        render(); layersChanged?.invoke(); ready?.invoke()
        return true
    }

    fun applyLayerCommand(command: LayerCommand): Boolean {
        val current = document ?: return false
        if (isDrawing || isNavigating) return false
        viewportWillChange?.invoke(); historyTaps.cancel(); cancelStroke()
        try { history.executeLayer(current, command) }
        finally {
            render(); historyChanged?.invoke(); layersChanged?.invoke(); ready?.invoke()
        }
        return true
    }

    fun preparePanelResize(): Boolean {
        if (isDrawing || isNavigating) return false
        historyTaps.cancel(); viewportWillChange?.invoke()
        return true
    }

    fun dispose() {
        invalidateAsyncFrames(); asyncPainter?.close(); asyncPainter = null
        inputEnabled = false; cancelStroke(); history.clear()
        document?.recycle(); document = null
    }

    fun cancelStroke() {
        val hadPreview = pending.isNotEmpty()
        softwareEpoch++; softwareFrameInFlight = false; softwareNeedsFrame = false; softwareDirty = null
        pending.clear(); activePointer = -1; rawStroke = false
        removeCallbacks(frame); framePending = false
        if (hadPreview) render()
    }

    fun clear() {
        if (document?.activeLayer?.alphaLocked == true) { historyFeedback?.invoke("Turn off alpha lock before clearing"); return }
        if (!canDraw) { historyFeedback?.invoke("Selected layer is locked or hidden"); return }
        historyTaps.cancel()
        cancelStroke()
        document?.let { history.execute(it, ClearLayerEdit) }
        historyChanged?.invoke()
        render()
    }

    fun undo(): Boolean = changeHistory(false)
    fun redo(): Boolean = changeHistory(true)

    private fun changeHistory(redo: Boolean): Boolean {
        val current = document ?: return false
        if (isDrawing || isNavigating || !inputEnabled) return false
        if (if (redo) !history.canRedo else !history.canUndo) return false
        historyTaps.cancel()
        viewportWillChange?.invoke()
        val label = if (redo) history.redo(current) else history.undo(current)
        render(); historyChanged?.invoke(); layersChanged?.invoke(); ready?.invoke()
        historyFeedback?.invoke("${if (redo) "Redo" else "Undo"}: $label")
        return true
    }

    fun save(file: File): Boolean = document?.exportPng(file) ?: false

    fun checkpoint(): Boolean {
        val current = document ?: return false
        return try {
            store.save(current, viewport.state())
            true
        } catch (failure: Exception) {
            Log.e("BooxProbe", "Checkpoint failed", failure)
            error?.invoke("Could not save drawing: ${failure.message}")
            false
        }
    }

    private fun invalidateAsyncFrames() {
        frameEpoch++; asyncSequence++; asyncCacheEpoch = -1L; asyncDirty = null
    }

    internal fun cancelAsyncPresentation(): Boolean {
        val pending = asyncDirty != null
        invalidateAsyncFrames()
        return pending
    }

    /** Snapshot only pixels on UI; SurfaceHolder lock/draw/post run on a worker.
     * A full snapshot seeds the screen cache after any synchronous frame/view change. */
    internal fun renderAsync(dirtyDocument: RectF, complete: (Boolean) -> Unit) {
        if (!holder.surface.isValid || width <= 0 || height <= 0) { complete(false); return }
        val started = StrokePerformance.now()
        val epoch = frameEpoch
        val dirty = if (asyncCacheEpoch != epoch) Rect(0, 0, width, height)
            else StrokeDirtyBounds.view(dirtyDocument, viewport.transform, width, height)
        if (dirty.isEmpty) { complete(true); return }
        asyncDirty?.let { dirty.union(it) }
        asyncDirty = Rect(dirty)
        val sequence = ++asyncSequence
        val current = document ?: run { complete(false); return }
        val fullSnapshot = dirty == Rect(0, 0, width, height)
        val snapshotWidth = if (fullSnapshot) current.spec.width else dirty.width()
        val snapshotHeight = if (fullSnapshot) current.spec.height else dirty.height()
        val available = Runtime.getRuntime().let { it.maxMemory() - it.totalMemory() + it.freeMemory() }
        val snapshotBytes = snapshotWidth.toLong() * snapshotHeight * 4
        if (available < snapshotBytes * 2 + width.toLong() * height * 4 + 8L * 1024 * 1024) {
            StrokePerformance.log("asyncSnapshot fallback=memory")
            render(dirtyDocument); complete(true); return
        }
        val pixels = try { Bitmap.createBitmap(snapshotWidth, snapshotHeight, Bitmap.Config.ARGB_8888) }
            catch (_: OutOfMemoryError) { render(dirtyDocument); complete(true); return }
        try {
            val target = Canvas(pixels)
            if (fullSnapshot) drawDocument(target, current)
            else {
                target.translate(-dirty.left.toFloat(), -dirty.top.toFloat())
                target.clipRect(dirty)
                drawFrame(target)
            }
        } catch (error: Throwable) { pixels.recycle(); throw error }
        StrokePerformance.log("asyncSnapshot full=$fullSnapshot pixels=${pixels.width.toLong() * pixels.height} uiMs=${StrokePerformance.ms(started)}")
        val painter = asyncPainter ?: AsyncSurfacePresentation(holder, surfacePaintLock,
            valid = { it == frameEpoch }, uiPost = { post(it) }).also { asyncPainter = it }
        painter.submit(AsyncSurfacePresentation.Frame(pixels, dirty, width, height, epoch,
            documentTransform = if (fullSnapshot) viewport.transform else null, complete = { success ->
            if (epoch != frameEpoch || sequence != asyncSequence) { complete(false) }
            else {
                if (success) { asyncCacheEpoch = epoch; asyncDirty = null }
                else { // Recover with the existing synchronous painter if the worker failed.
                    render(dirtyDocument)
                }
                complete(true)
            }
        }))
    }

    fun render(dirtyDocument: RectF? = null) {
        invalidateAsyncFrames()
        synchronized(surfacePaintLock) { renderSynchronous(dirtyDocument) }
    }

    private fun renderSynchronous(dirtyDocument: RectF? = null) {
        if (!holder.surface.isValid) return
        val requested = dirtyDocument?.let { StrokeDirtyBounds.view(it, viewport.transform, width, height) }
        if (requested?.isEmpty == true) return
        val requestedPixels = requested?.let { it.width().toLong() * it.height() } ?: width.toLong() * height
        val started = StrokePerformance.now()
        // SurfaceHolder may expand this rectangle for buffer preservation. Respect its
        // returned rectangle rather than clipping to the original request.
        val canvas = (if (requested == null) holder.lockCanvas() else holder.lockCanvas(requested)) ?: return
        val locked = StrokePerformance.now()
        var drawn = locked
        try {
            if (requested != null) canvas.clipRect(requested)
            drawFrame(canvas)
        } finally {
            drawn = StrokePerformance.now()
            holder.unlockCanvasAndPost(canvas)
            StrokePerformance.log("surface requestedPixels=$requestedPixels actualPixels=${requested?.let { it.width().toLong() * it.height() } ?: width.toLong() * height} lockMs=${StrokePerformance.ms(started, locked)} drawMs=${StrokePerformance.ms(locked, drawn)} postMs=${StrokePerformance.ms(drawn)}")
        }
    }

    /** Shared frame painter; caller owns surface locking and any dirty-region clip. */
    internal fun drawFrame(canvas: Canvas) {
            canvas.drawColor(Color.LTGRAY)
            val current = document ?: return
            val saved = canvas.save()
            try {
                val transform = viewport.transform
                canvas.concat(transform.matrix())
                canvas.drawRect(0f, 0f, current.spec.width.toFloat(), current.spec.height.toFloat(), paper)
                canvas.clipRect(0f, 0f, current.spec.width.toFloat(), current.spec.height.toFloat())
                drawDocument(canvas, current)
            } finally { canvas.restoreToCount(saved) }
    }

    private fun drawDocument(canvas: Canvas, current: DrawingDocument) {
        if (pending.isEmpty()) current.drawComposite(canvas, bitmapPaint)
        else current.layers.forEach { layer ->
            current.drawLayer(canvas, layer, bitmapPaint,
                if (layer.id == strokeLayerId) ({ target -> strokeBrush.draw(target, pending, layer.alphaLocked) }) else null,
                isolatePending = strokeBrush.isEraser)
        }
    }

    private fun renderScheduledFrame() {
        framePending = false
        if (!softwareNeedsFrame) { render(); return }
        if (softwareFrameInFlight) return
        softwareNeedsFrame = false
        if (pending.isNotEmpty()) {
            StrokeDirtyBounds.document(pending, strokeBrush.maxRenderedWidth)?.let { bounds ->
                softwareDirty?.union(bounds) ?: run { softwareDirty = bounds }
            }
        }
        val dirty = softwareDirty ?: return
        softwareDirty = null
        val epoch = softwareEpoch
        softwareFrameInFlight = true
        renderAsync(dirty) {
            if (epoch == softwareEpoch) {
                softwareFrameInFlight = false
                if (softwareNeedsFrame) requestFrame()
            }
        }
    }

    private fun requestFrame() {
        if (pending.isNotEmpty()) softwareNeedsFrame = true
        // Keep Android live ink bounded to one snapshot in flight. Moves update the
        // sample list while the worker paints; its completion snapshots the latest.
        if (softwareNeedsFrame && softwareFrameInFlight) return
        if (!framePending) { framePending = true; postOnAnimation(frame) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!inputEnabled || document == null) return true
        if (historyTaps.onTouchEvent(event)) { gestures.cancel(); return true }
        if (gestures.onTouchEvent(event)) return true
        if (rawInput || isNavigating || !canDraw) return true
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_CANCEL) { cancelStroke(); return true }
        val actionIndex = event.actionIndex
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (action == MotionEvent.ACTION_DOWN) cancelStroke()
                if (event.getToolType(actionIndex) != MotionEvent.TOOL_TYPE_STYLUS || activePointer != -1) return true
                if (!viewport.containsViewPoint(event.getX(actionIndex), event.getY(actionIndex))) return true
                activePointer = event.getPointerId(actionIndex)
                strokeTransform = viewport.transform
                strokeLayerId = document?.layerId
                strokeBrush = brush
                val axis = event.device?.getMotionRange(MotionEvent.AXIS_PRESSURE, event.source)
                pressureRange = if (axis != null && axis.min.isFinite() && axis.max.isFinite() && axis.max > axis.min)
                    PressureRange(axis.min, axis.max, "android-motion-range") else PressureRange.ANDROID_DEFAULT
            }
            MotionEvent.ACTION_MOVE -> Unit
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(actionIndex) != activePointer) return true
                if (event.flags and MotionEvent.FLAG_CANCELED != 0) { cancelStroke(); return true }
            }
            else -> return true
        }
        val index = event.findPointerIndex(activePointer)
        if (index < 0 || event.getToolType(index) != MotionEvent.TOOL_TYPE_STYLUS) return true
        fun addSample(history: Int?) {
            val sample = PenTilt.sample(event, index, history)
            pending += strokeTransform.map(sample.copy(pressure = pressureRange.normalize(sample.rawPressure)))
        }
        for (h in 0 until event.historySize) addSample(h)
        addSample(null)
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            val samples = pending.toList()
            pending.clear(); activePointer = -1
            removeCallbacks(frame); framePending = false
            commit(samples, "android", pressureRange, strokeLayerId, strokeBrush, deferPresentation = true)?.let { dirty ->
                softwareDirty?.union(dirty) ?: run { softwareDirty = dirty }
                softwareNeedsFrame = true
                requestFrame()
            }
            performClick()
        } else requestFrame()
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
