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
    val brush = PressureBrush()
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
    private val frame = Runnable { framePending = false; render() }
    private val paper = Paint().apply { color = Color.WHITE }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
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
    override fun surfaceDestroyed(holder: SurfaceHolder) { historyTaps.cancel(); gestures.cancel(); cancelStroke(); unavailable?.invoke() }
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

    /** Samples committed here are always in document pixels. */
    fun commit(samples: List<PenSample>, source: String, range: PressureRange = PressureRange.ANDROID_DEFAULT,
        expectedLayerId: String? = null) {
        val current = document ?: return
        if (!current.canPaint || (expectedLayerId != null && current.layerId != expectedLayerId)) { render(); return }
        val valid = samples.filter { it.x.isFinite() && it.y.isFinite() }
        if (valid.isEmpty()) return
        historyTaps.cancel()
        history.execute(current, StrokeEdit(valid, brush))
        historyChanged?.invoke()
        render()
        strokeFinished(PenStroke(valid, source, range))
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
        inputEnabled = false; cancelStroke(); history.clear()
        document?.recycle(); document = null
    }

    fun cancelStroke() {
        val hadPreview = pending.isNotEmpty()
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

    fun render() {
        if (!holder.surface.isValid) return
        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawColor(Color.LTGRAY)
            val current = document ?: return
            val saved = canvas.save()
            try {
                val transform = viewport.transform
                canvas.concat(transform.matrix())
                canvas.drawRect(0f, 0f, current.spec.width.toFloat(), current.spec.height.toFloat(), paper)
                canvas.clipRect(0f, 0f, current.spec.width.toFloat(), current.spec.height.toFloat())
                if (pending.isEmpty()) current.drawComposite(canvas, bitmapPaint)
                else current.layers.forEach { layer ->
                    current.drawLayer(canvas, layer, bitmapPaint,
                        if (layer.id == strokeLayerId) ({ target -> brush.draw(target, pending, layer.alphaLocked) }) else null)
                }
            } finally { canvas.restoreToCount(saved) }
        } finally { holder.unlockCanvasAndPost(canvas) }
    }

    private fun requestFrame() {
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
        for (h in 0 until event.historySize) {
            val raw = event.getHistoricalPressure(index, h)
            pending += strokeTransform.map(PenSample(event.getHistoricalX(index, h), event.getHistoricalY(index, h),
                pressureRange.normalize(raw), event.getHistoricalEventTime(h), raw))
        }
        val raw = event.getPressure(index)
        pending += strokeTransform.map(PenSample(event.getX(index), event.getY(index), pressureRange.normalize(raw), event.eventTime, raw))
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            val samples = pending.toList()
            pending.clear(); activePointer = -1
            removeCallbacks(frame); framePending = false
            commit(samples, "android", pressureRange, strokeLayerId)
            performClick()
        } else requestFrame()
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
