package art.boox.probe

import android.graphics.PointF
import android.graphics.RectF

data class ViewportState(val scale: Float, val centerX: Float, val centerY: Float, val fit: Boolean)

data class ViewTransform(val scale: Float, val x: Float, val y: Float) {
    fun toDocument(x: Float, y: Float) = PointF((x - this.x) / scale, (y - this.y) / scale)
    fun toView(x: Float, y: Float) = PointF(x * scale + this.x, y * scale + this.y)
    fun map(sample: PenSample): PenSample = toDocument(sample.x, sample.y).let { sample.copy(x = it.x, y = it.y) }
}

enum class ZoomAction { IN, OUT, FIT, ACTUAL }

class DocumentViewport {
    var transform = ViewTransform(1f, 0f, 0f)
        private set
    var viewWidth = 0
        private set
    var viewHeight = 0
        private set
    private var document = CanvasSpec(1, 1)
    private var fitted = true
    private val fitScale: Float get() = minOf(4f, (viewWidth - 32f).coerceAtLeast(1f) / document.width,
        (viewHeight - 32f).coerceAtLeast(1f) / document.height)
    private val minScale: Float get() = minOf(0.1f, fitScale)

    fun resize(spec: CanvasSpec, width: Int, height: Int, restored: ViewportState? = null) {
        require(width > 0 && height > 0)
        val state = restored ?: if (viewWidth > 0) state() else null
        document = spec; viewWidth = width; viewHeight = height
        if (state == null || state.fit) fit()
        else {
            fitted = false
            setCenter(state.scale, state.centerX, state.centerY)
        }
    }

    fun state(): ViewportState {
        val center = transform.toDocument(viewWidth / 2f, viewHeight / 2f)
        return ViewportState(transform.scale, center.x, center.y, fitted)
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

    private fun fit() {
        fitted = true
        setCenter(fitScale, document.width / 2f, document.height / 2f)
    }

    private fun setCenter(scale: Float, centerX: Float, centerY: Float) {
        require(scale.isFinite() && scale > 0 && centerX.isFinite() && centerY.isFinite())
        val zoom = scale.coerceIn(minScale, 4f)
        val proposedX = viewWidth / 2f - centerX * zoom
        val proposedY = viewHeight / 2f - centerY * zoom
        val scaledWidth = document.width * zoom
        val scaledHeight = document.height * zoom
        val x = if (scaledWidth <= viewWidth) (viewWidth - scaledWidth) / 2f else proposedX.coerceIn(viewWidth - scaledWidth, 0f)
        val y = if (scaledHeight <= viewHeight) (viewHeight - scaledHeight) / 2f else proposedY.coerceIn(viewHeight - scaledHeight, 0f)
        transform = ViewTransform(zoom, x, y)
    }

    fun documentRect() = RectF(transform.x, transform.y,
        transform.x + document.width * transform.scale, transform.y + document.height * transform.scale)

    fun drawingRect(): RectF = documentRect().apply { intersect(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat()) }
}
