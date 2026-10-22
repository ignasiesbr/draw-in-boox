package art.boox.probe

import android.graphics.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class ViewportState(
    val scale: Float, val centerX: Float, val centerY: Float, val fit: Boolean,
    val rotationDegrees: Float = 0f
)

data class ViewTransform(val scale: Float, val x: Float, val y: Float, val rotationDegrees: Float = 0f) {
    private val radians = Math.toRadians(rotationDegrees.toDouble())
    private val cosine = cos(radians).toFloat().let { if (abs(it) < 0.000001f) 0f else it }
    private val sine = sin(radians).toFloat().let { if (abs(it) < 0.000001f) 0f else it }
    fun toDocument(x: Float, y: Float): PointF {
        val u = (x - this.x) / scale
        val v = (y - this.y) / scale
        return PointF(u * cosine + v * sine, -u * sine + v * cosine)
    }
    fun toView(x: Float, y: Float) = PointF(scale * (x * cosine - y * sine) + this.x,
        scale * (x * sine + y * cosine) + this.y)
    fun map(sample: PenSample): PenSample = toDocument(sample.x, sample.y).let { sample.copy(x = it.x, y = it.y) }
    fun matrix() = Matrix().apply {
        setValues(floatArrayOf(scale * cosine, -scale * sine, x, scale * sine, scale * cosine, y, 0f, 0f, 1f))
    }
}

enum class ZoomAction { IN, OUT, FIT, ACTUAL }
data class PreviewClip(val limit: Rect, val exclusions: List<Rect>)

class DocumentViewport {
    var transform = ViewTransform(1f, 0f, 0f)
        private set
    var viewWidth = 0
        private set
    var viewHeight = 0
        private set
    var rotationDegrees = 0f
        private set
    private var document = CanvasSpec(1, 1)
    private var fitted = true
    private val rotatedBounds: RectF get() = bounds(ViewTransform(1f, 0f, 0f, rotationDegrees))
    private val fitScale: Float get() = rotatedBounds.let {
        minOf(4f, (viewWidth - 32f).coerceAtLeast(1f) / it.width(), (viewHeight - 32f).coerceAtLeast(1f) / it.height())
    }
    private val minScale: Float get() = minOf(0.1f, fitScale)

    fun resize(spec: CanvasSpec, width: Int, height: Int, restored: ViewportState? = null) {
        require(width > 0 && height > 0)
        val state = restored ?: if (viewWidth > 0) state() else null
        document = spec; viewWidth = width; viewHeight = height
        rotationDegrees = normalizeAngle(state?.rotationDegrees ?: 0f)
        if (state == null || state.fit) fit()
        else { fitted = false; setCenter(state.scale, state.centerX, state.centerY) }
    }

    fun state(): ViewportState {
        val center = transform.toDocument(viewWidth / 2f, viewHeight / 2f)
        return ViewportState(transform.scale, center.x, center.y, fitted, rotationDegrees)
    }

    fun rotateTo(angle: Float) {
        val current = state()
        rotationDegrees = normalizeAngle(angle)
        if (fitted) fit() else setCenter(current.scale, current.centerX, current.centerY)
    }

    fun change(action: ZoomAction) {
        when (action) {
            ZoomAction.FIT -> fit()
            else -> {
                val current = state()
                val scale = when (action) {
                    ZoomAction.IN -> transform.scale * 1.25f
                    ZoomAction.OUT -> transform.scale / 1.25f
                    else -> 1f
                }
                fitted = false
                setCenter(scale, current.centerX, current.centerY)
            }
        }
    }

    private fun fit() { fitted = true; setCenter(fitScale, document.width / 2f, document.height / 2f) }

    private fun setCenter(scale: Float, centerX: Float, centerY: Float) {
        require(scale.isFinite() && scale > 0 && centerX.isFinite() && centerY.isFinite())
        val zoom = scale.coerceIn(minScale, 4f)
        val bounds = rotatedBounds
        val center = ViewTransform(zoom, 0f, 0f, rotationDegrees).toView(centerX, centerY)
        val proposedLeft = viewWidth / 2f - center.x + bounds.left * zoom
        val proposedTop = viewHeight / 2f - center.y + bounds.top * zoom
        val scaledWidth = bounds.width() * zoom
        val scaledHeight = bounds.height() * zoom
        val left = if (scaledWidth <= viewWidth) (viewWidth - scaledWidth) / 2f else proposedLeft.coerceIn(viewWidth - scaledWidth, 0f)
        val top = if (scaledHeight <= viewHeight) (viewHeight - scaledHeight) / 2f else proposedTop.coerceIn(viewHeight - scaledHeight, 0f)
        transform = ViewTransform(zoom, left - bounds.left * zoom, top - bounds.top * zoom, rotationDegrees)
    }

    private fun corners(mapping: ViewTransform) = listOf(mapping.toView(0f, 0f), mapping.toView(document.width.toFloat(), 0f),
        mapping.toView(document.width.toFloat(), document.height.toFloat()), mapping.toView(0f, document.height.toFloat()))
    private fun bounds(mapping: ViewTransform): RectF = corners(mapping).let { points ->
        RectF(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
    }
    fun documentRect() = bounds(transform)
    fun drawingRect(): RectF = documentRect().apply { intersect(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat()) }
    fun containsViewPoint(x: Float, y: Float): Boolean = transform.toDocument(x, y).let {
        x >= 0 && y >= 0 && x < viewWidth && y < viewHeight && it.x >= 0 && it.y >= 0 && it.x < document.width && it.y < document.height
    }
    fun paperPath() = Path().apply {
        val points = corners(transform)
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
        close()
    }

    /** The SDK accepts rectangular exclusions, not a polygon. Rasterize the angled paper once
     * per viewport change, then exclude all pixel rectangles outside it from hardware ink. */
    fun previewClip(): PreviewClip {
        val paper = Region().apply { setPath(paperPath(), Region(0, 0, viewWidth, viewHeight)) }
        val limit = paper.bounds
        val outside = Region(limit).apply { op(paper, Region.Op.DIFFERENCE) }
        val iterator = RegionIterator(outside)
        val rect = Rect()
        val exclusions = mutableListOf<Rect>()
        while (iterator.next(rect)) exclusions += Rect(rect)
        return PreviewClip(limit, exclusions)
    }

    companion object {
        fun normalizeAngle(angle: Float): Float {
            require(angle.isFinite()) { "Enter a finite angle." }
            val normalized = ((angle % 360f) + 360f) % 360f
            return if (normalized >= 180f) normalized - 360f else normalized
        }
    }
}
