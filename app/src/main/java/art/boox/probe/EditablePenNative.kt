package art.boox.probe

import com.onyx.android.sdk.pennative.NeoPenNative
import com.onyx.android.sdk.pennative.PenConfig
import com.onyx.android.sdk.pennative.PenInkResult

/** The existing tools retain their selected backend; vendor pens always use BOOX's library. */
internal object EditablePenNative {
    private val recovered get() = BuildConfig.RENDERER_BACKEND == "recovered"
    fun createPen(type: Int, config: PenConfig) = if (recovered) RecoveredPenNative.nativeCreatePen(type, config) else NeoPenNative.createPen(type, config)
    fun destroyPen(handle: Long) = if (recovered) RecoveredPenNative.nativeDestroyPen(handle) else NeoPenNative.destroyPen(handle)
    fun onPenDown(handle: Long, points: DoubleArray, repaint: Boolean): PenInkResult? =
        if (recovered) RecoveredPenNative.nativeOnPenDown(handle, points, repaint) else NeoPenNative.onPenDown(handle, points, repaint)
    fun onPenMove(handle: Long, points: DoubleArray, prediction: DoubleArray?, repaint: Boolean): PenInkResult? =
        if (recovered) RecoveredPenNative.nativeOnPenMove(handle, points, prediction, repaint) else NeoPenNative.onPenMove(handle, points, prediction, repaint)
    fun onPenUp(handle: Long, points: DoubleArray, repaint: Boolean): PenInkResult? =
        if (recovered) RecoveredPenNative.nativeOnPenUp(handle, points, repaint) else NeoPenNative.onPenUp(handle, points, repaint)
}

internal object RecoveredPenNative {
    init { System.loadLibrary("boox_art_engine") }
    external fun nativeCreatePen(type: Int, config: PenConfig): Long
    external fun nativeDestroyPen(handle: Long)
    external fun nativeOnPenDown(handle: Long, points: DoubleArray, repaint: Boolean): PenInkResult?
    external fun nativeOnPenMove(handle: Long, points: DoubleArray, prediction: DoubleArray?, repaint: Boolean): PenInkResult?
    external fun nativeOnPenUp(handle: Long, points: DoubleArray, repaint: Boolean): PenInkResult?
}
