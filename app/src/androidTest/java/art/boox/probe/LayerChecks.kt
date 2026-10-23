package art.boox.probe

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.view.MotionEvent
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object LayerChecks {
    private fun fill(history: EditHistory, doc: DrawingDocument, color: Int) {
        history.execute(doc, object : DocumentEdit {
            override val label = "Fill"
            override fun affectedRegions(spec: CanvasSpec) = listOf(Rect(0, 0, spec.width, spec.height))
            override fun apply(document: DrawingDocument) { Canvas(document.layer).drawColor(color, PorterDuff.Mode.SRC) }
        })
    }
    private fun composite(doc: DrawingDocument) = Bitmap.createBitmap(doc.spec.width, doc.spec.height, Bitmap.Config.ARGB_8888).also {
        Canvas(it).apply { drawColor(Color.WHITE); doc.drawComposite(this) }
    }
    private fun rejects(block: () -> Unit) {
        var rejected = false
        try { block() } catch (expected: IllegalStateException) { rejected = true }
        check(rejected) { "Unsafe layer operation was accepted" }
    }

    fun run(context: Context) {
        compositingAndHistory()
        inputProtection(context)
        checkpoint(context)
        limits()
        largeCanvas()
    }

    private fun compositingAndHistory() {
        val doc = DrawingDocument.create(CanvasSpec(260, 260))
        val history = EditHistory()
        val bottom = doc.layerId
        fill(history, doc, Color.RED)
        history.executeLayer(doc, LayerCommand.Add)
        val top = doc.layerId
        fill(history, doc, Color.argb(128, 0, 0, 255))
        val expected = composite(doc)
        val reference = Bitmap.createBitmap(260, 260, Bitmap.Config.ARGB_8888)
        Canvas(reference).apply {
            drawColor(Color.WHITE)
            val transparent = Bitmap.createBitmap(260, 260, Bitmap.Config.ARGB_8888)
            Canvas(transparent).apply { doc.layers.forEach { drawBitmap(it.bitmap, 0f, 0f, null) } }
            drawBitmap(transparent, 0f, 0f, null); transparent.recycle()
        }
        check(expected.sameAs(reference)) { "Layer compositing order/alpha is wrong" }
        doc.selectLayer(bottom)
        check(history.undo(doc) == "Fill")
        check(doc.layerId == top && doc.findLayer(bottom)!!.bitmap.getPixel(10, 10) == Color.RED) { "Undo targeted the selected rather than recorded layer" }
        history.redo(doc)
        composite(doc).also { check(it.sameAs(expected)); it.recycle() }

        history.executeLayer(doc, LayerCommand.Move(top, -1))
        composite(doc).also { check(it.getPixel(10, 10) == Color.RED); it.recycle() }
        history.undo(doc)
        check(doc.layers.last().id == top)
        composite(doc).also { check(it.sameAs(expected)); it.recycle() }
        history.executeLayer(doc, LayerCommand.Visibility(top, false))
        composite(doc).also { check(it.getPixel(10, 10) == Color.RED); it.recycle() }
        history.undo(doc)
        check(doc.findLayer(top)!!.visible)
        history.executeLayer(doc, LayerCommand.Lock(top, true))
        rejects { history.execute(doc, ClearLayerEdit) }
        rejects { history.executeLayer(doc, LayerCommand.Delete(top)) }
        rejects { history.executeLayer(doc, LayerCommand.MergeDown(top)) }
        history.undo(doc)
        check(!doc.findLayer(top)!!.locked)
        history.executeLayer(doc, LayerCommand.Lock(bottom, true))
        rejects { history.executeLayer(doc, LayerCommand.MergeDown(top)) }
        history.undo(doc)

        history.executeLayer(doc, LayerCommand.MergeDown(top))
        check(doc.layers.size == 1 && doc.layerId == bottom)
        composite(doc).also { check(it.sameAs(expected)); it.recycle() }
        history.undo(doc)
        check(doc.layers.map { it.id } == listOf(bottom, top) && doc.layerId == top)
        composite(doc).also { check(it.sameAs(expected)); it.recycle() }
        history.redo(doc)
        composite(doc).also { check(it.sameAs(expected)); it.recycle() }
        history.undo(doc)
        history.executeLayer(doc, LayerCommand.Duplicate(top))
        val copy = doc.layerId
        check(copy != top && doc.layer.sameAs(doc.findLayer(top)!!.bitmap))
        doc.layer.setPixel(1, 1, Color.GREEN)
        check(doc.findLayer(top)!!.bitmap.getPixel(1, 1) != Color.GREEN) { "Duplicate shares pixel storage" }
        history.undo(doc)
        check(doc.findLayer(copy) == null)
        history.redo(doc)
        check(doc.layerId == copy && doc.layer.sameAs(doc.findLayer(top)!!.bitmap))
        history.executeLayer(doc, LayerCommand.Delete(copy))
        check(doc.findLayer(copy) == null)
        history.undo(doc)
        check(doc.layerId == copy && doc.layer.sameAs(doc.findLayer(top)!!.bitmap))
        history.undo(doc) // undo duplication, then a new action invalidates structural redo
        history.executeLayer(doc, LayerCommand.Visibility(top, false))
        check(!history.canRedo)

        // Partial stroke invalidation must replace only its region without stale cached pixels.
        history.undo(doc)
        val before = composite(doc)
        val stroke = StrokeEdit(listOf(PenSample(130f, 130f, 1f, 1)), PressureBrush())
        history.execute(doc, stroke)
        val painted = composite(doc)
        check(painted.getPixel(130, 130) == Color.BLACK && painted.getPixel(10, 10) == before.getPixel(10, 10))
        history.undo(doc)
        composite(doc).also { check(it.sameAs(before)); it.recycle() }
        for (bitmap in listOf(expected, reference, before, painted)) bitmap.recycle()
        doc.recycle()
    }

    private fun inputProtection(context: Context) {
        val testContext = object : ContextWrapper(context) {
            override fun getFilesDir() = File(context.cacheDir, "layer-input-tests").apply { mkdirs() }
        }
        var strokes = 0
        val view = RasterSurface(testContext, CanvasSpec(128, 128)) { strokes++ }
        view.surfaceChanged(view.holder, 0, 400, 400)
        view.createDocument(CanvasSpec(128, 128))
        val base = view.document!!.layerId
        view.applyLayerCommand(LayerCommand.Add)
        val top = view.document!!.layerId
        view.commit(listOf(PenSample(64f, 64f, 1f, 1)), "test", expectedLayerId = base)
        check(strokes == 0) { "Stale layer callback painted into a new layer" }
        view.applyLayerCommand(LayerCommand.Lock(top, true))
        check(!view.canDraw && !BooxPenAdapter(view) {}.open()) { "Locked layer enabled hardware ink" }
        view.commit(listOf(PenSample(64f, 64f, 1f, 1)), "test")
        view.clear()
        check(view.document!!.layer.getPixel(64, 64) == Color.TRANSPARENT && strokes == 0)
        val point = view.viewport.transform.toView(64f, 64f)
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val prop = MotionEvent.PointerProperties().apply { id = 7; toolType = MotionEvent.TOOL_TYPE_STYLUS }
            val coord = MotionEvent.PointerCoords().apply { x = point.x; y = point.y; pressure = 1f }
            val event = MotionEvent.obtain(1, 2, action, 1, arrayOf(prop), arrayOf(coord), 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_STYLUS, 0)
            try { view.onTouchEvent(event) } finally { event.recycle() }
        }
        check(strokes == 0)
        view.undo()
        check(view.canDraw)
        view.applyLayerCommand(LayerCommand.Visibility(top, false))
        check(!view.canDraw && !BooxPenAdapter(view) {}.open())
        view.selectLayer(base)
        check(view.canDraw)
        view.beginRawStroke()
        check(!view.selectLayer(top) && !view.applyLayerCommand(LayerCommand.Add) && !view.preparePanelResize())
        view.endRawStroke()
        view.commit(listOf(PenSample(64f, 64f, 1f, 1)), "test")
        check(strokes == 1 && view.document!!.findLayer(top)!!.bitmap.getPixel(64, 64) == Color.TRANSPARENT)
        view.dispose()
    }

    private fun checkpoint(context: Context) {
        val dir = File(context.cacheDir, "layer-store-tests").apply { mkdirs() }
        val store = DocumentStore(dir)
        val original = DrawingDocument.create(CanvasSpec(97, 53, 300))
        original.layer.eraseColor(Color.RED)
        // Explicit old version-1 ZIP migration (not just the legacy canvas.png path).
        ZipOutputStream(store.file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("document.json"))
            zip.write(JSONObject().put("version", 1).put("id", original.id).put("layerId", original.layerId)
                .put("width", 97).put("height", 53).put("ppi", 300).toString().toByteArray())
            zip.closeEntry(); zip.putNextEntry(ZipEntry("layer.png"))
            original.layer.compress(Bitmap.CompressFormat.PNG, 100, zip); zip.closeEntry()
        }
        val loaded = store.loadOrCreate(CanvasSpec(1, 1)).document
        check(loaded.id == original.id && loaded.layerId == original.layerId && loaded.layer.sameAs(original.layer))
        loaded.addLayer(); val upper = loaded.layerId
        loaded.layer.eraseColor(Color.argb(128, 0, 0, 255)); loaded.activeLayer.name = "Ink"
        loaded.setLayerLocked(original.layerId, true)
        loaded.setLayerVisible(upper, false)
        val state = ViewportState(1.5f, 45f, 25f, false, 37.25f)
        store.save(loaded, state)
        val reopened = store.loadOrCreate(CanvasSpec(1, 1))
        check(reopened.viewport == state && reopened.document.stackState() == loaded.stackState())
        reopened.document.layers.zip(loaded.layers).forEach { (a, b) -> check(a.bitmap.sameAs(b.bitmap)) }
        val png = File(dir, "export.png")
        reopened.document.exportPng(png)
        android.graphics.BitmapFactory.decodeFile(png.path).also {
            check(it.width == 97 && it.height == 53 && it.getPixel(10, 10) == Color.RED); it.recycle()
        }
        // Invalid metadata is rejected before trying to decode/allocate layer images.
        for (duplicateIds in listOf(false, true)) {
            val metadata = JSONObject().put("version", 2).put("id", original.id)
                .put("width", 97).put("height", 53).put("selectedLayerId", original.layerId)
                .put("layers", JSONArray().apply {
                    put(JSONObject().put("id", original.layerId))
                    if (duplicateIds) put(JSONObject().put("id", original.layerId))
                })
            if (!duplicateIds) metadata.put("viewport", JSONObject().put("scale", "NaN")
                .put("centerX", 0).put("centerY", 0).put("fit", false))
            ZipOutputStream(store.file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("document.json")); zip.write(metadata.toString().toByteArray()); zip.closeEntry()
            }
            val bytes = store.file.readBytes()
            var message = ""
            try { store.loadOrCreate(CanvasSpec(1, 1)) } catch (error: IllegalArgumentException) { message = error.message.orEmpty() }
            check(message.contains(if (duplicateIds) "Invalid layer IDs" else "Invalid viewport"))
            check(bytes.contentEquals(store.file.readBytes())) { "Corrupt checkpoint was overwritten" }
        }
        for (doc in listOf(original, loaded, reopened.document)) doc.recycle()
        png.delete(); store.file.delete()
    }

    private fun limits() {
        val doc = DrawingDocument.create(CanvasSpec(16, 16))
        repeat(doc.maxLayers - 1) { doc.addLayer() }
        val ids = doc.layers.map { it.id }
        rejects { doc.addLayer() }
        rejects { doc.duplicateLayer(doc.layerId) }
        check(doc.layers.map { it.id } == ids)
        doc.recycle()
        for (preset in CanvasSizes.presets) check(LayerLimits.maxLayers(preset.spec) in 1..64)
    }

    private fun largeCanvas() {
        val doc = DrawingDocument.create(CanvasSizes.presets.first().spec)
        var target: Bitmap? = null
        try {
            repeat(doc.maxLayers - 1) { doc.addLayer() }
            val history = EditHistory()
            history.execute(doc, StrokeEdit(listOf(PenSample(930f, 1240f, 1f, 1)), PressureBrush()))
            target = Bitmap.createBitmap(doc.spec.width, doc.spec.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(target)
            val start = android.os.SystemClock.elapsedRealtime()
            doc.drawComposite(canvas)
            val cold = android.os.SystemClock.elapsedRealtime() - start
            val cachedStart = android.os.SystemClock.elapsedRealtime()
            repeat(5) { doc.drawComposite(canvas) }
            val cached = (android.os.SystemClock.elapsedRealtime() - cachedStart) / 5f
            check(target.getPixel(930, 1240) == Color.BLACK)
            history.undo(doc)
            target.eraseColor(Color.TRANSPARENT); doc.drawComposite(canvas)
            check(target.getPixel(930, 1240) == Color.TRANSPARENT)
            rejects { history.executeLayer(doc, LayerCommand.Add) }
            android.util.Log.i("BooxProbeTest", "Layer budget=${doc.maxLayers} layerBytes=${doc.spec.bitmapBytes} coldMs=$cold cachedMs=$cached nativeHeap=${android.os.Debug.getNativeHeapAllocatedSize()}")
        } finally { target?.recycle(); doc.recycle() }
    }
}
