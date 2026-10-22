package art.boox.probe

import android.content.Context
import android.graphics.*
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.io.File

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
    private val store = DocumentStore(context.filesDir)
    private val pending = mutableListOf<PenSample>()
    private var activePointer = -1
    private var rawStroke = false
    val isDrawing: Boolean get() = activePointer != -1 || rawStroke
    private var pressureRange = PressureRange.ANDROID_DEFAULT
    private var strokeTransform = ViewTransform(1f, 0f, 0f)
    private var framePending = false
    private val frame = Runnable { framePending = false; render() }
    private val paper = Paint().apply { color = Color.WHITE }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var loadFailed = false
    var inputEnabled = true
        set(value) { field = value; if (!value) cancelStroke() }
    var rawInput = false
        set(value) { if (field != value) cancelStroke(); field = value }
    var ready: (() -> Unit)? = null
    var unavailable: (() -> Unit)? = null
    var viewportWillChange: (() -> Unit)? = null
    var error: ((String) -> Unit)? = null

    init { holder.addCallback(this) }

    override fun surfaceCreated(holder: SurfaceHolder) = render()
    override fun surfaceDestroyed(holder: SurfaceHolder) { cancelStroke(); unavailable?.invoke() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0 || loadFailed) return
        cancelStroke()
        try {
            val loaded = if (document == null) store.loadOrCreate(defaultSpec) else null
            if (loaded != null) document = loaded.document
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
        check(!isDrawing) { "Lift the pen before creating a canvas." }
        val replacement = DrawingDocument.create(spec)
        try { store.save(replacement, null) } catch (failure: Throwable) { replacement.recycle(); throw failure }
        viewportWillChange?.invoke()
        cancelStroke()
        document?.recycle()
        document = replacement
        loadFailed = false
        viewport.resize(spec, viewport.viewWidth.coerceAtLeast(1), viewport.viewHeight.coerceAtLeast(1),
            ViewportState(1f, spec.width / 2f, spec.height / 2f, true))
        render()
        ready?.invoke()
    }

    fun zoom(action: ZoomAction): Boolean {
        if (isDrawing || document == null) return false
        viewportWillChange?.invoke()
        viewport.change(action)
        render()
        ready?.invoke()
        return true
    }

    fun beginRawStroke() { rawStroke = true }
    fun endRawStroke() { rawStroke = false }

    /** Samples committed here are always in document pixels. */
    fun commit(samples: List<PenSample>, source: String, range: PressureRange = PressureRange.ANDROID_DEFAULT) {
        val current = document ?: return
        val valid = samples.filter { it.x.isFinite() && it.y.isFinite() }
        if (valid.isEmpty()) return
        brush.draw(Canvas(current.layer), valid)
        render()
        strokeFinished(PenStroke(valid, source, range))
    }

    fun cancelStroke() {
        val hadPreview = pending.isNotEmpty()
        pending.clear(); activePointer = -1; rawStroke = false
        removeCallbacks(frame); framePending = false
        if (hadPreview) render()
    }

    fun clear() {
        cancelStroke()
        document?.clear()
        render()
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
            val rect = viewport.documentRect()
            canvas.drawRect(rect, paper)
            val saved = canvas.save()
            try {
                canvas.clipRect(rect)
                val transform = viewport.transform
                canvas.translate(transform.x, transform.y)
                canvas.scale(transform.scale, transform.scale)
                canvas.drawBitmap(current.layer, 0f, 0f, bitmapPaint)
                brush.draw(canvas, pending)
            } finally { canvas.restoreToCount(saved) }
        } finally { holder.unlockCanvasAndPost(canvas) }
    }

    private fun requestFrame() {
        if (!framePending) { framePending = true; postOnAnimation(frame) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!inputEnabled || rawInput || document == null) return true
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_CANCEL) { cancelStroke(); return true }
        val actionIndex = event.actionIndex
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (action == MotionEvent.ACTION_DOWN) cancelStroke()
                if (event.getToolType(actionIndex) != MotionEvent.TOOL_TYPE_STYLUS || activePointer != -1) return true
                if (!viewport.drawingRect().contains(event.getX(actionIndex), event.getY(actionIndex))) return true
                activePointer = event.getPointerId(actionIndex)
                strokeTransform = viewport.transform
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
            commit(samples, "android", pressureRange)
            performClick()
        } else requestFrame()
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
