package art.boox.probe

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.InputDevice
import android.view.MotionEvent
import java.io.File

object NativePreviewSafetyChecks {
    fun run(context: Context) {
        NativePreviewGuardChecks.run()
        var enabled = true
        val calls = mutableListOf<String>()
        NativePreviewConfiguration.apply(50.4f, Color.BLACK,
            rendering = { enabled = it; calls += "render:$it" },
            setColor = { check(!enabled); calls += "color" },
            setWidth = { check(!enabled && it == 50.4f); calls += "width" },
            setPencil = { check(!enabled); calls += "pencil" },
            enableInput = { check(!enabled); calls += "input:true" })
        check(calls == listOf("render:false", "color", "width", "pencil", "input:true", "render:true"))
        check(enabled)
        calls.clear()
        check(runCatching {
            NativePreviewConfiguration.apply(12f, Color.BLACK,
                rendering = { enabled = it; calls += "render:$it" },
                setColor = { check(!enabled) }, setWidth = { error("Setter failed") },
                setPencil = { error("Must not continue") }, enableInput = { error("Failed setup must not enable input") })
        }.isFailure)
        check(!enabled && calls == listOf("render:false")) { "Failed setup re-enabled unsafe rendering" }

        check(NativePreviewGuardChecks.guardedSupports(BrushPreset.INK.defaults().compile()))
        check(NativePreviewGuardChecks.guardedSupports(BrushPreset.TILT.defaults().compile()))
        val marker = BrushPreset.MONOLINE.defaults()
        for (shade in InkShade.entries.filter { it != InkShade.BLACK }) {
            check(!NativePreviewGuardChecks.guardedSupports(marker.copy(size = 12f, shade = shade).compile()))
            check(!NativePreviewGuardChecks.guardedSupports(BrushPreset.TILT.defaults().copy(shade = shade).compile()))
        }
        check(!NativePreviewGuardChecks.guardedSupports(marker.copy(size = 128f).compile()))
        val fixed = marker.copy(size = 12f).compile()
        check(NativePreviewGuardChecks.guardedSupports(fixed) && !fixed.hasDynamicWidth)
        check(LivePreviewWidth.forBrush(fixed, 0.5f,
            post = { _, _ -> error("Constant brush queued a native shader update") },
            remove = {}, apply = { error("Constant brush changed native width at contact") }) == null)
        var sent = 0f
        val dynamic = BrushPreset.TILT.defaults().compile()
        val controller = checkNotNull(LivePreviewWidth.forBrush(dynamic, 2f,
            post = { task, _ -> task.run() }, remove = {}, apply = { sent = it }))
        val sample = PenSample(0f, 0f, 0.5f, 1, tiltRadians = 0.7f)
        controller.begin(sample); controller.finish()
        check(sent == dynamic.width(sample) * 2f) { "Accepted dynamic width response changed" }

        // This attached-view test specifically requires guarded Android fallback.
        if (!NativePreviewPolicy.firmwareCrashGuardEnabled) return
        val directory = File(context.cacheDir, "native-safety-${System.nanoTime()}")
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = directory.apply { mkdirs() } }
        val completed = mutableListOf<PenStroke>()
        val view = RasterSurface(isolated, CanvasSpec(256, 256)) { completed += it }
        val live = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val final = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        try {
            view.surfaceChanged(view.holder, 0, 512, 512)
            view.createDocument(CanvasSpec(256, 256))
            view.rotateView(-22f); view.viewport.zoomTo(0.5f, 256f, 256f)
            check(view.selectBrush(marker.copy(size = 100.8f, shade = InkShade.LIGHT)))
            var message = ""
            val adapter = BooxPenAdapter(view) { message = it }
            check(!adapter.open() && !view.rawInput && message.contains("App preview"))
            fun send(action: Int, x: Float) {
                val point = view.viewport.transform.toView(x, 90f)
                val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 7; toolType = MotionEvent.TOOL_TYPE_STYLUS })
                val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = point.x; y = point.y; pressure = 0.5f; size = 1f })
                val event = MotionEvent.obtain(1, 2, action, 1, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
                try { view.onTouchEvent(event) } finally { event.recycle() }
            }
            send(MotionEvent.ACTION_DOWN, 70f); send(MotionEvent.ACTION_MOVE, 180f)
            check(view.isDrawing && completed.isEmpty() && view.history.undoCount == 0)
            check(view.document!!.layer.getPixel(120, 90) == Color.TRANSPARENT)
            view.drawFrame(Canvas(live))
            val middle = view.viewport.transform.toView(120f, 90f)
            check(live.getPixel(middle.x.toInt(), middle.y.toInt()) == InkShade.LIGHT.argb) { "Gray live ink missing before commit" }
            send(MotionEvent.ACTION_UP, 180f)
            check(!view.isDrawing && completed.size == 1 && view.history.undoCount == 1)
            check(view.document!!.layer.getPixel(120, 90) == InkShade.LIGHT.argb)
            view.drawFrame(Canvas(final))
            // Direct view-space vectors and document-raster display have different
            // edge antialiasing at this rotation/zoom. Ink color/position must persist.
            check(live.getPixel(middle.x.toInt(), middle.y.toInt()) == final.getPixel(middle.x.toInt(), middle.y.toInt()))
            adapter.close()
        } finally { live.recycle(); final.recycle(); view.dispose(); directory.deleteRecursively() }
    }
}
