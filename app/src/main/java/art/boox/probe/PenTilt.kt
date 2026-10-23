package art.boox.probe

import android.view.MotionEvent
import kotlin.math.acos
import kotlin.math.cos

/** Canonical inclination is radians: 0 upright, PI/2 flat. Missing is not upright. */
object PenTilt {
    /** Azimuth of the projected pen axis in screen coordinates. */
    fun azimuth(sample: PenSample): Float? {
        sample.documentAzimuthRadians?.takeIf { it.isFinite() }?.let { return it }
        sample.viewOrientationRadians?.takeIf { it.isFinite() }?.let { return it - (Math.PI / 2).toFloat() }
        val x = sample.rawTiltX ?: return null
        val y = sample.rawTiltY ?: return null
        if (x !in -90..90 || y !in -90..90 || (x == 0 && y == 0)) return null
        return kotlin.math.atan2(kotlin.math.tan(Math.toRadians(y.coerceIn(-89, 89).toDouble())),
            kotlin.math.tan(Math.toRadians(x.coerceIn(-89, 89).toDouble()))).toFloat()
    }
    fun android(tilt: Float, supported: Boolean): Float? =
        if (supported && tilt.isFinite() && tilt >= 0f) tilt.coerceAtMost((Math.PI / 2).toFloat()) else null

    /** BOOX/Wacom axis tilts are signed degrees from the surface normal. */
    fun boox(x: Int, y: Int): Float? {
        if (x !in -90..90 || y !in -90..90) return null
        if (kotlin.math.abs(x) == 90 || kotlin.math.abs(y) == 90) return (Math.PI / 2).toFloat()
        return acos((cos(Math.toRadians(x.toDouble())) * cos(Math.toRadians(y.toDouble()))).coerceIn(0.0, 1.0)).toFloat()
    }

    fun sample(event: MotionEvent, index: Int, history: Int? = null): PenSample {
        val supported = event.device?.getMotionRange(MotionEvent.AXIS_TILT, event.source)?.let {
            it.min.isFinite() && it.max.isFinite() && it.max > it.min
        } == true
        fun axis(value: Int) = if (history == null) event.getAxisValue(value, index)
            else event.getHistoricalAxisValue(value, index, history)
        val tilt = android(axis(MotionEvent.AXIS_TILT), supported)
        val orientation = if (tilt != null) axis(MotionEvent.AXIS_ORIENTATION).takeIf { it.isFinite() } else null
        val raw = if (history == null) event.getPressure(index) else event.getHistoricalPressure(index, history)
        return PenSample(if (history == null) event.getX(index) else event.getHistoricalX(index, history),
            if (history == null) event.getY(index) else event.getHistoricalY(index, history), raw,
            if (history == null) event.eventTime else event.getHistoricalEventTime(history), raw,
            tilt, orientation)
    }
}
