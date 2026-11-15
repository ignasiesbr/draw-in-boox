package art.boox.probe

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Attached overlay checks, without painting or replacing the user's drawing. */
object ToolbarChecks {
    private data class Drag(val toolbar: FloatingToolbar, val grip: String, val tool: Int, val hover: Boolean)
    fun run(instrumentation: Instrumentation) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun onMain(block: () -> Unit) {
            var error: Throwable? = null
            instrumentation.runOnMainSync { try { block() } catch (failure: Throwable) { error = failure } }
            error?.let { throw it }
        }
        fun await(checkReady: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 8000
            while (true) {
                var ready = false
                onMain { ready = checkReady() }
                if (ready) return
                check(SystemClock.uptimeMillis() < deadline) { "Toolbar/input layout timed out" }
                Thread.sleep(25)
            }
        }
        fun dwell(view: View) {
            val elapsed = CountDownLatch(1)
            onMain { view.postDelayed({ elapsed.countDown() }, ViewConfiguration.getLongPressTimeout() + 100L) }
            check(elapsed.await(3, TimeUnit.SECONDS))
        }
        fun send(grip: View, action: Int, tool: Int, dx: Float = 0f, dy: Float = 0f) {
            val location = IntArray(2).also { grip.getLocationOnScreen(it) }
            val prop = MotionEvent.PointerProperties().apply { id = 0; toolType = tool }
            val coords = MotionEvent.PointerCoords().apply {
                x = location[0] + grip.width / 2f + dx; y = location[1] + grip.height / 2f + dy
            }
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, action, 1, arrayOf(prop), arrayOf(coords),
                0, 0, 1f, 1f, 0, 0,
                if (tool == MotionEvent.TOOL_TYPE_STYLUS) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN, 0)
            event.offsetLocation(-location[0].toFloat(), -location[1].toFloat())
            try {
                if (action in MotionEvent.ACTION_HOVER_MOVE..MotionEvent.ACTION_HOVER_EXIT) grip.dispatchGenericMotionEvent(event)
                else grip.dispatchTouchEvent(event)
            } finally { event.recycle() }
        }
        val root = activity.window.decorView
        val surface = root.findViewWithTag<RasterSurface>("Drawing surface")
        val drawing = root.findViewWithTag<FloatingToolbar>("Floating toolbar")
        val navigation = root.findViewWithTag<FloatingToolbar>("Navigation toolbar")
        val prefs = activity.getPreferences(0)
        val saved = prefs.all.filterKeys { it.startsWith("drawing_toolbar_") || it.startsWith("navigation_toolbar_") }
        val initialPositions = mutableMapOf<FloatingToolbar, Pair<Float, Float>>()
        var originalViewport: ViewportState? = null
        var nativeWasOpen = false
        try {
            await { surface.document != null && surface.inputEnabled && activity.hasWindowFocus() }
            onMain {
                originalViewport = surface.viewport.state()
                check(navigation.controls.orientation == android.widget.LinearLayout.VERTICAL)
                for (tag in listOf("Fit screen", "Zoom in", "Zoom out", "Rotate left", "Rotate right"))
                    check(root.findViewWithTag<View>(tag).parent === navigation.controls)
                check(navigation.width < navigation.height)
                check(drawing.findViewWithTag<View>("Floating toolbar top drag handle") == null)
                check(navigation.findViewWithTag<View>("Navigation toolbar left drag handle") == null)
                val document = surface.document!!
                val historyCount = surface.history.undoCount
                root.findViewWithTag<View>("Rotate left").performClick()
                root.findViewWithTag<View>("Rotate right").performClick()
                check(surface.viewport.rotationDegrees == originalViewport!!.rotationDegrees)
                surface.zoom(ZoomAction.ACTUAL)
                root.findViewWithTag<View>("Zoom in").performClick()
                val zoomed = surface.viewport.transform.scale
                root.findViewWithTag<View>("Zoom out").performClick()
                check(surface.viewport.transform.scale < zoomed)
                root.findViewWithTag<View>("Fit screen").performClick()
                check(surface.viewport.state().fit)
                check(surface.document === document && surface.history.undoCount == historyCount)
                for (toolbar in listOf(drawing, navigation)) {
                    val parent = toolbar.parent as View
                    initialPositions[toolbar] =
                        ((toolbar.left + toolbar.translationX) / (parent.width - toolbar.width).coerceAtLeast(1)) to
                        ((toolbar.top + toolbar.translationY) / (parent.height - toolbar.height).coerceAtLeast(1))
                }
            }
            await { surface.inputEnabled }
            onMain { nativeWasOpen = surface.rawInput }
            for ((bar, gripName, pointer, hovering) in listOf(
                Drag(drawing, "left", MotionEvent.TOOL_TYPE_FINGER, false),
                Drag(navigation, "top", MotionEvent.TOOL_TYPE_STYLUS, false),
                Drag(navigation, "top", MotionEvent.TOOL_TYPE_STYLUS, true))) {
                val grip = bar.findViewWithTag<View>("${bar.tag} $gripName drag handle")
                var beforeX = 0f
                var beforeY = 0f
                onMain {
                    check(surface.rawInput == nativeWasOpen) { "Native reader did not reopen after movement" }
                    check(grip.width > 0 && grip.height > 0)
                    beforeX = bar.translationX; beforeY = bar.translationY
                    send(grip, if (hovering) MotionEvent.ACTION_HOVER_ENTER else MotionEvent.ACTION_DOWN, pointer)
                    check(!bar.isDragging) { "Grip moved without dwelling" }
                }
                dwell(grip)
                onMain {
                    check(bar.isDragging && !surface.inputEnabled && !surface.rawInput) { "Drag did not close pen input" }
                    // A negative movement stays inside from the initial bottom drawing position;
                    // the navigation toolbar starts left, so move that one right instead.
                    send(grip, if (hovering) MotionEvent.ACTION_HOVER_MOVE else MotionEvent.ACTION_MOVE,
                        pointer, if (bar === drawing) -30f else 50f, -40f)
                    check(abs(bar.translationX - beforeX) > 1f || abs(bar.translationY - beforeY) > 1f)
                    send(grip, if (hovering) MotionEvent.ACTION_HOVER_EXIT else MotionEvent.ACTION_UP, pointer)
                    check(!bar.isDragging)
                }
                await { surface.inputEnabled }
                onMain {
                    check(surface.rawInput == nativeWasOpen) { "Native reader did not reopen after movement" }
                    val location = IntArray(2).also { bar.getLocationOnScreen(it) }
                    val origin = IntArray(2).also { surface.getLocationOnScreen(it) }
                    val bounds = Rect(location[0] - origin[0], location[1] - origin[1],
                        location[0] - origin[0] + bar.width, location[1] - origin[1] + bar.height)
                    check(bounds in activity.drawingInputExclusions()) { "Moved toolbar exclusion is stale" }
                    check(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= surface.width && bounds.bottom <= surface.height)
                    val key = if (bar === drawing) "drawing_toolbar" else "navigation_toolbar"
                    check(prefs.contains("${key}_x") && prefs.contains("${key}_y"))
                }
            }
            onMain { check(activity.setDrawingMode(true)) }
            await { surface.inputEnabled }
            onMain {
                check(drawing.visibility == View.GONE && navigation.visibility == View.GONE)
                check(activity.drawingInputExclusions().size == 1)
                check(activity.setDrawingMode(false))
            }
            await { surface.inputEnabled }
        } finally {
            onMain {
                drawing.cancelDrag(); navigation.cancelDrag()
                originalViewport?.let { surface.viewport.resize(surface.document!!.spec, surface.width, surface.height, it) }
                for ((toolbar, position) in initialPositions) toolbar.restorePosition(position.first, position.second)
                prefs.edit().also { edit ->
                    for (prefix in listOf("drawing_toolbar", "navigation_toolbar")) {
                        edit.remove("${prefix}_x"); edit.remove("${prefix}_y")
                    }
                    saved.forEach { (key, value) -> edit.putFloat(key, value as Float) }
                }.commit()
                activity.setDrawingMode(false)
                activity.finish()
            }
            instrumentation.waitForIdleSync()
        }
    }
}
