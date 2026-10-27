package art.boox.probe

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import java.io.File
import kotlin.math.abs

object ImageImportChecks {
    fun run(context: Context) {
        val directory = File(context.cacheDir, "image-checks-${System.nanoTime()}").apply { mkdirs() }
        val source = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.argb(128, 0, 0, 0))
        }
        val png = File(directory, "Reference.PNG")
        val jpg = File(directory, "Photo.jpg")
        val rotated = File(directory, "Rotated.jpg")
        val bad = File(directory, "Broken.png").apply { writeText("not an image") }
        val gif = File(directory, "Wrong.png").apply {
            writeBytes(android.util.Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", 0))
        }
        val document = DrawingDocument.create(CanvasSpec(80, 80))
        try {
            png.outputStream().use { check(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            source.eraseColor(Color.RED)
            Bitmap.createScaledBitmap(source, 400, 200, true).also { large ->
                try { jpg.outputStream().use { check(large.compress(Bitmap.CompressFormat.JPEG, 100, it)) } }
                finally { large.recycle() }
            }
            jpg.copyTo(rotated)
            android.media.ExifInterface(rotated.path).apply {
                setAttribute(android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            for (file in listOf(jpg, rotated)) {
                val decoded = ImageImport.read(context, Uri.fromFile(file), document.spec)
                try {
                    val expected = if (file == rotated) 40 to 80 else 80 to 40
                    check(decoded.bitmap.width == expected.first && decoded.bitmap.height == expected.second) {
                        "JPEG aspect ratio or EXIF orientation was lost"
                    }
                    check(Color.red(decoded.bitmap.getPixel(10, 10)) >= 250)
                } finally { decoded.bitmap.recycle() }
            }
            val prepared = ImageImport.read(context, Uri.fromFile(png), document.spec)
            val originalId = document.layerId
            document.layer.eraseColor(Color.WHITE)
            val original = document.layer.copy(Bitmap.Config.ARGB_8888, false)
            val history = EditHistory()
            try {
                check(prepared.name == "Reference")
                check(history.executeLayer(document, LayerCommand.ImportImage(prepared.bitmap, prepared.name)))
            } finally { prepared.bitmap.recycle() }
            val imported = document.activeLayer
            check(document.layers.size == 2 && imported.id != originalId && imported.name == "Reference")
            check(document.layers.first().bitmap.sameAs(original)) { "Import changed existing artwork" }
            original.recycle()
            check(Color.alpha(imported.bitmap.getPixel(10, 10)) == 0) { "Letterbox padding is opaque" }
            check(Color.alpha(imported.bitmap.getPixel(40, 40)) == 128) { "PNG transparency was lost" }
            val pixels = imported.bitmap.copy(Bitmap.Config.ARGB_8888, false)
            try {
                history.executeLayer(document, LayerCommand.Opacity(imported.id, 50))
                check(imported.bitmap.sameAs(pixels)) { "Opacity was baked into imported pixels" }
                val composite = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
                try {
                    document.drawComposite(Canvas(composite))
                    check(abs(Color.red(composite.getPixel(40, 40)) - 191) <= 1) { "Layer opacity did not fade the image" }
                    check(composite.getPixel(10, 10) == Color.WHITE)
                } finally { composite.recycle() }
                check(history.undo(document) == "Layer opacity")
                check(history.undo(document) == "Import image" && document.layers.size == 1 && document.layerId == originalId)
                check(history.redo(document) == "Import image" && document.layerId == imported.id)
                check(document.layer.sameAs(pixels)) { "Redo needs the original decoded bitmap" }
                check(history.redo(document) == "Layer opacity" && document.activeLayer.opacity == 50)
                val portable = File(directory, "copy.boox")
                DocumentFile.write(portable, document)
                DocumentFile.read(portable).also {
                    try { check(it.activeLayer.opacity == 50 && it.layer.sameAs(pixels)) }
                    finally { it.recycle() }
                }
                val store = DocumentStore(directory)
                store.save(document, null)
                store.loadOrCreate(CanvasSpec(1, 1)).document.also {
                    try { check(it.activeLayer.opacity == 50 && it.layer.sameAs(pixels)) }
                    finally { it.recycle() }
                }
            } finally { pixels.recycle() }
            for (file in listOf(bad, gif)) {
                val before = document.stackState()
                fails { ImageImport.read(context, Uri.fromFile(file), document.spec).bitmap.recycle() }
                check(document.stackState() == before && history.undoCount == 2)
            }
            val before = document.stackState()
            fails { history.executeLayer(document, LayerCommand.ImportImage(source, "")) }
            check(document.stackState() == before && history.undoCount == 2)
            while (document.layers.size < document.maxLayers) document.addLayer()
            val full = document.stackState()
            fails { document.importImage(source, "Too many") }
            check(document.stackState() == full)
        } finally { source.recycle(); document.recycle(); directory.deleteRecursively() }
    }

    /** Real result handling uses an isolated editor; never replace the user's checkpoint. */
    fun actions(instrumentation: Instrumentation) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val directory = File(activity.cacheDir, "image-action-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(activity) { override fun getFilesDir() = directory }
        val file = File(directory, "Imported.png")
        Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888).also { bitmap ->
            try {
                bitmap.eraseColor(Color.BLACK)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
        lateinit var surface: RasterSurface
        lateinit var actions: DocumentActions
        var resumes = 0
        try {
            instrumentation.runOnMainSync {
                surface = RasterSurface(context, CanvasSpec(80, 80)) { }
                actions = DocumentActions(activity, surface, { false }, { surface.inputEnabled = false },
                    { resumes++; surface.inputEnabled = true })
                surface.ready = actions::documentReady
                check(actions.onResult(103, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file))))
                check(!actions.busy && surface.document == null)
                surface.surfaceChanged(surface.holder, 0, 100, 100)
                check(actions.busy && !surface.inputEnabled)
            }
            await(instrumentation, actions)
            lateinit var before: LayerStackState
            lateinit var pixels: Bitmap
            instrumentation.runOnMainSync {
                check(surface.document!!.layers.size == 2 && surface.document!!.activeLayer.name == "Imported")
                check(surface.history.undoCount == 1 && surface.inputEnabled && resumes > 0)
                surface.applyLayerCommand(LayerCommand.Opacity(surface.document!!.layerId, 25))
                pixels = surface.document!!.layer.copy(Bitmap.Config.ARGB_8888, false)
                check(surface.undo() && surface.undo() && surface.document!!.layers.size == 1)
                check(surface.redo() && surface.redo() && surface.document!!.activeLayer.opacity == 25)
                check(surface.document!!.layer.sameAs(pixels))
                pixels.recycle()
                before = surface.document!!.stackState()
                check(actions.onResult(103, Activity.RESULT_CANCELED, null))
                check(surface.document!!.stackState() == before && !actions.busy && surface.inputEnabled)
                file.writeText("corrupt image")
                check(actions.onResult(103, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file))))
            }
            await(instrumentation, actions)
            instrumentation.runOnMainSync {
                check(surface.document!!.stackState() == before && surface.history.undoCount == 2 && surface.inputEnabled)
                // Destruction during decode disposes only after the worker returns.
                actions.onResult(103, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
                actions.destroy()
                check(surface.document != null)
            }
            await(instrumentation, actions)
            check(surface.document == null)
        } finally {
            instrumentation.runOnMainSync { actions.destroy(); activity.finish() }
            instrumentation.waitForIdleSync()
            directory.deleteRecursively()
        }
    }

    private fun await(instrumentation: Instrumentation, actions: DocumentActions) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (true) {
            var busy = true
            instrumentation.runOnMainSync { busy = actions.busy }
            if (!busy) break
            check(SystemClock.uptimeMillis() < deadline) { "Image import did not finish" }
            SystemClock.sleep(30)
        }
        instrumentation.waitForIdleSync()
    }

    private fun fails(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: Exception) { failed = true }
        check(failed) { "Invalid image operation succeeded" }
    }
}
