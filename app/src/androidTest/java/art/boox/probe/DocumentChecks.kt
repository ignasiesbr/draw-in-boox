package art.boox.probe

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import java.io.File
import kotlin.math.abs

object DocumentChecks {
    private fun directory(context: Context, name: String) = object : ContextWrapper(context) {
        override fun getFilesDir(): File = File(context.cacheDir, "document-tests/$name").apply { mkdirs() }
    }

    fun run(context: Context) {
        transforms()
        presetsAndLimits(context)
        migration(context)
        fixedDocumentAndRestore(context)
        corruptCheckpoint(context)
    }

    private fun transforms() {
        val viewport = DocumentViewport()
        viewport.resize(CanvasSpec(800, 600), 400, 300)
        for (action in listOf(ZoomAction.FIT, ZoomAction.ACTUAL, ZoomAction.IN, ZoomAction.IN, ZoomAction.OUT)) {
            viewport.change(action)
            for ((x, y) in listOf(0f to 0f, 400f to 300f, 799f to 599f, 123.45f to 456.78f)) {
                val display = viewport.transform.toView(x, y)
                val restored = viewport.transform.toDocument(display.x, display.y)
                check(abs(restored.x - x) < 0.001f && abs(restored.y - y) < 0.001f) { "Viewport transform does not round-trip" }
            }
            val before = viewport.state()
            viewport.change(ZoomAction.IN)
            val after = viewport.state()
            check(abs(before.centerX - after.centerX) < 0.001f && abs(before.centerY - after.centerY) < 0.001f)
        }
        viewport.change(ZoomAction.ACTUAL)
        check(viewport.transform.scale == 1f)
        viewport.resize(CanvasSpec(800, 600), 300, 400)
        check(viewport.transform.scale == 1f) { "Rotation changed actual-size zoom" }
        repeat(50) { viewport.change(ZoomAction.OUT) }
        check(viewport.transform.scale > 0f && !viewport.drawingRect().isEmpty)
        repeat(50) { viewport.change(ZoomAction.IN) }
        check(viewport.transform.scale == 4f)
        viewport.resize(CanvasSpec(1, 1), 2480, 1294)
        viewport.change(ZoomAction.FIT)
        check(viewport.transform.scale == 4f) { "Fit exceeded the hardware preview zoom bound for a tiny canvas" }
    }

    private fun presetsAndLimits(context: Context) {
        check(CanvasSizes.error(CanvasSpec(0, 1)) != null)
        check(CanvasSizes.error(CanvasSpec(-1, 10)) != null)
        check(CanvasSizes.error(CanvasSpec(Int.MAX_VALUE, Int.MAX_VALUE)) != null)
        check(CanvasSizes.error(CanvasSpec(CanvasSizes.MAX_SIDE, CanvasSizes.MAX_SIDE)) != null)
        val file = File(directory(context, "presets").filesDir, "export.png")
        for (preset in CanvasSizes.presets) {
            check(CanvasSizes.error(preset.spec) == null) { "Preset is not usable on this tablet: ${preset.name}" }
            check(preset.spec.swapped().swapped() == preset.spec)
            val document = DrawingDocument.create(preset.spec)
            try {
                check(document.layer.getPixel(0, 0) == Color.TRANSPARENT)
                check(document.exportPng(file))
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                check(bounds.outWidth == preset.spec.width && bounds.outHeight == preset.spec.height)
                Log.i("BooxProbeTest", "Preset ${preset.name}: ${preset.spec} export OK, nativeHeap=${android.os.Debug.getNativeHeapAllocatedSize()}")
            } finally { document.recycle(); file.delete() }
        }
    }

    private fun migration(context: Context) {
        val testContext = directory(context, "migration")
        val store = DocumentStore(testContext.filesDir)
        store.file.delete()
        val legacy = File(testContext.filesDir, "canvas.png")
        val old = Bitmap.createBitmap(97, 53, Bitmap.Config.ARGB_8888)
        old.eraseColor(Color.WHITE)
        old.setPixel(96, 52, Color.BLACK)
        legacy.outputStream().use { old.compress(Bitmap.CompressFormat.PNG, 100, it) }
        old.recycle()
        val before = legacy.readBytes()
        val loaded = store.loadOrCreate(CanvasSpec(100, 100))
        val state = ViewportState(2f, 48f, 26f, false)
        try {
            check(loaded.document.spec == CanvasSpec(97, 53)) { "Legacy dimensions were not preserved" }
            check(loaded.document.layer.getPixel(96, 52) == Color.BLACK) { "Legacy edge pixel was lost" }
            store.save(loaded.document, state)
            val reopened = store.loadOrCreate(CanvasSpec(100, 100))
            try {
                check(reopened.document.id == loaded.document.id && reopened.document.layerId == loaded.document.layerId)
                check(reopened.viewport == state)
                check(reopened.document.layer.getPixel(96, 52) == Color.BLACK)
            } finally { reopened.document.recycle() }
            check(before.contentEquals(legacy.readBytes())) { "Legacy backup was modified" }
        } finally { loaded.document.recycle(); legacy.delete(); store.file.delete() }
    }

    private fun fixedDocumentAndRestore(context: Context) {
        val testContext = directory(context, "resize")
        val file = File(testContext.filesDir, "export.png")
        val store = DocumentStore(testContext.filesDir)
        store.file.delete()
        val view = RasterSurface(testContext, CanvasSpec(64, 96)) { }
        view.surfaceChanged(view.holder, 0, 200, 200)
        view.createDocument(CanvasSpec(64, 96, 300))
        view.commit(listOf(PenSample(63f, 95f, 1f, 1)), "test")
        for ((width, height) in listOf(50 to 400, 400 to 50, 200 to 200)) {
            view.surfaceChanged(view.holder, 0, width, height)
            check(view.document?.spec == CanvasSpec(64, 96, 300)) { "View resize changed document dimensions" }
            check(view.document!!.layer.getPixel(63, 95) == Color.BLACK) { "View resize cropped document pixels" }
        }
        view.zoom(ZoomAction.ACTUAL)
        val documentId = view.document!!.id
        check(view.checkpoint())
        check(view.save(file))
        BitmapFactory.decodeFile(file.path).also {
            check(it.width == 64 && it.height == 96 && it.getPixel(63, 95) == Color.BLACK)
            check(it.getPixel(0, 0) == Color.WHITE)
            it.recycle()
        }
        val loaded = store.loadOrCreate(CanvasSpec(1, 1))
        try {
            check(loaded.document.id == documentId && loaded.document.spec.ppi == 300)
            check(loaded.viewport?.scale == 1f)
            check(loaded.document.layer.getPixel(0, 0) == Color.TRANSPARENT)
        } finally { loaded.document.recycle() }
        view.document!!.recycle(); store.file.delete(); file.delete()
    }

    private fun corruptCheckpoint(context: Context) {
        val store = DocumentStore(directory(context, "corrupt").filesDir)
        val corrupt = "broken document".toByteArray()
        store.file.writeBytes(corrupt)
        var failed = false
        try { store.loadOrCreate(CanvasSpec(64, 64)) } catch (expected: Exception) { failed = true }
        check(failed && corrupt.contentEquals(store.file.readBytes())) { "Corrupt checkpoint was silently replaced" }
        store.file.delete()
    }
}
