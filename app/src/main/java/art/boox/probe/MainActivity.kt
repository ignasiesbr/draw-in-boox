package art.boox.probe

import android.app.Activity
import android.content.ContentValues
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {
    private lateinit var surface: RasterSurface
    private lateinit var adapter: BooxPenAdapter
    private lateinit var status: TextView
    private lateinit var mode: Button
    private lateinit var documentInfo: TextView
    private var preferBoox = true
    private var resumed = false
    private var strokeCount = 0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        preferBoox = getPreferences(0).getBoolean("boox", true)
        File(filesDir, "strokes.jsonl").createNewFile()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        documentInfo = TextView(this).apply {
            text = "BOOX Art · Pen probe"
            textSize = 22f; setTextColor(Color.BLACK); setPadding(20, 12, 20, 4)
        }
        root.addView(documentInfo)
        val toolbar = LinearLayout(this)
        fun button(row: LinearLayout, title: String, action: () -> Unit): Button = Button(this).apply {
            text = title; textSize = 16f; isAllCaps = false; minimumHeight = dp(56)
            tag = title
            setOnClickListener { action() }
            row.addView(this, LinearLayout.LayoutParams(0, dp(60), 1f))
        }
        mode = button(toolbar, "BOOX pen") {
            preferBoox = !preferBoox
            getPreferences(0).edit().putBoolean("boox", preferBoox).apply()
            configurePen()
        }
        button(toolbar, "Refresh") { surface.render(); adapter.refresh() }
        button(toolbar, "Export PNG") { export() }
        button(toolbar, "Clear") {
            android.app.AlertDialog.Builder(this).setMessage("Clear this drawing?")
                .setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ ->
                    adapter.close(); surface.clear(); strokeCount = 0; configurePen(); adapter.refresh()
                }.show()
        }
        root.addView(toolbar)
        val navigation = LinearLayout(this)
        button(navigation, "New canvas") { newCanvas() }
        button(navigation, "−") { zoom(ZoomAction.OUT) }
        button(navigation, "+") { zoom(ZoomAction.IN) }
        button(navigation, "Fit") { zoom(ZoomAction.FIT) }
        button(navigation, "100%") { zoom(ZoomAction.ACTUAL) }
        root.addView(navigation)
        status = TextView(this).apply {
            textSize = 16f; setTextColor(Color.BLACK); setPadding(20, 6, 20, 8)
            text = "Draw with the stylus. Fingers do not draw."
            height = dp(48)
            maxLines = 2
        }
        root.addView(status)
        surface = RasterSurface(this, strokeFinished = ::recordStroke)
        adapter = BooxPenAdapter(surface) { message ->
            status.text = message
            mode.text = if (surface.rawInput) "BOOX pen" else "Android pen"
        }
        surface.ready = { updateDocumentInfo(); configurePen() }
        surface.unavailable = { adapter.close() }
        surface.viewportWillChange = { adapter.close() }
        surface.error = { message -> status.text = message; Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        root.addView(surface, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        Log.i("BooxProbe", "Launch ${Build.MODEL} API=${Build.VERSION.SDK_INT} firmware=${Build.DISPLAY}")
        Log.i("BooxProbe", "Heap=${Runtime.getRuntime().maxMemory()} maxCanvasPixels=${CanvasSizes.maxPixels}")
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun configurePen() {
        adapter.close()
        surface.cancelStroke()
        surface.inputEnabled = resumed && hasWindowFocus()
        if (!surface.inputEnabled || surface.document == null || surface.width == 0 || !surface.holder.surface.isValid) return
        if (preferBoox) adapter.open()
        else { status.text = "Android pen ready · pressure controls width"; mode.text = "Android pen" }
        writeDocumentState()
    }

    private fun writeDocumentState() {
        val document = surface.document ?: return
        val transform = surface.viewport.transform
        val state = JSONObject().put("documentId", document.id).put("layerId", document.layerId)
            .put("width", document.spec.width).put("height", document.spec.height)
            .put("viewWidth", surface.width).put("viewHeight", surface.height)
            .put("scale", transform.scale).put("x", transform.x).put("y", transform.y)
            .put("mode", if (surface.rawInput) "boox" else "android")
            .put("maxCanvasPixels", CanvasSizes.maxPixels).put("heapLimitBytes", Runtime.getRuntime().maxMemory())
            .put("wallTimeMs", System.currentTimeMillis())
        document.spec.ppi?.let { state.put("ppi", it) }
        try { File(filesDir, "document-state.json").writeText(state.toString()) }
        catch (failure: Exception) { Log.w("BooxProbe", "Document diagnostics write failed", failure) }
        Log.i("BooxProbe", "Document ${document.id} ${document.spec.width}x${document.spec.height} zoom=${transform.scale} offset=${transform.x},${transform.y}")
    }

    override fun onResume() {
        super.onResume(); resumed = true
        Log.i("BooxProbe", "Activity resumed")
        surface.post { configurePen() }
    }
    override fun onPause() {
        resumed = false; surface.inputEnabled = false; adapter.close(); surface.checkpoint()
        Log.i("BooxProbe", "Activity paused; input closed and bitmap checkpointed")
        super.onPause()
    }
    override fun onWindowFocusChanged(focused: Boolean) {
        super.onWindowFocusChanged(focused)
        if (::adapter.isInitialized) {
            Log.i("BooxProbe", "Window focus=$focused")
            if (focused) surface.post { configurePen() }
            else { surface.inputEnabled = false; adapter.close() }
        }
    }
    override fun onDestroy() { adapter.close(); super.onDestroy() }

    private fun updateDocumentInfo() {
        val document = surface.document ?: return
        documentInfo.text = "BOOX Art · ${document.spec.width} × ${document.spec.height} · ${(surface.viewport.transform.scale * 100).toInt()}%"
    }

    private fun zoom(action: ZoomAction) {
        if (!surface.zoom(action)) Toast.makeText(this, "Lift the pen before changing zoom.", Toast.LENGTH_SHORT).show()
    }

    private fun newCanvas() {
        if (surface.isDrawing) { Toast.makeText(this, "Lift the pen before creating a canvas.", Toast.LENGTH_SHORT).show(); return }
        CanvasSizeDialog.show(this) { spec ->
            android.app.AlertDialog.Builder(this).setTitle("Replace current canvas?")
                .setMessage("Create ${spec.width} × ${spec.height} pixels. Export the current drawing first to keep a PNG copy.")
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Export & create") { _, _ -> if (export()) createCanvas(spec) }
                .setPositiveButton("Discard & create") { _, _ -> createCanvas(spec) }.show()
        }
    }

    private fun createCanvas(spec: CanvasSpec) {
        try { surface.createDocument(spec); strokeCount = 0; surface.checkpoint() }
        catch (failure: Exception) { Toast.makeText(this, "Could not create canvas: ${failure.message}", Toast.LENGTH_LONG).show() }
        catch (failure: OutOfMemoryError) { Toast.makeText(this, "Not enough memory. Choose a smaller canvas.", Toast.LENGTH_LONG).show() }
    }

    private fun recordStroke(stroke: PenStroke) {
        val samples = stroke.samples
        val source = stroke.source
        strokeCount++
        val points = JSONArray()
        samples.forEach { p -> points.put(JSONArray().put(p.x).put(p.y).put(p.pressure).put(p.time).put(p.rawPressure)) }
        val range = JSONObject().put("min", stroke.pressureRange.min).put("max", stroke.pressureRange.max)
            .put("origin", stroke.pressureRange.origin)
        val brush = JSONObject().put("minWidthPx", surface.brush.minWidth).put("maxWidthPx", surface.brush.maxWidth)
            .put("curve", "linear")
        val document = checkNotNull(surface.document)
        val transform = surface.viewport.transform
        val entry = JSONObject().put("schemaVersion", 3).put("source", source).put("stroke", strokeCount)
            .put("pressureRange", range).put("brush", brush)
            .put("documentId", document.id).put("layerId", document.layerId)
            .put("coordinateSpace", "document").put("canvasWidth", document.spec.width).put("canvasHeight", document.spec.height)
            .put("viewWidth", surface.width).put("viewHeight", surface.height)
            .put("viewport", JSONObject().put("scale", transform.scale).put("x", transform.x).put("y", transform.y))
            .put("wallTimeMs", System.currentTimeMillis()).put("points", points)
        try { File(filesDir, "strokes.jsonl").appendText("$entry\n") }
        catch (error: Exception) { Log.e("BooxProbe", "Stroke log write failed", error) }
        val low = (samples.minOf { it.pressure } * 100).toInt()
        val high = (samples.maxOf { it.pressure } * 100).toInt()
        val message = "Stroke $strokeCount · $source · ${samples.size} points · pressure $low–$high%"
        status.text = message
        val outside = samples.count { it.x < 0 || it.y < 0 || it.x >= document.spec.width || it.y >= document.spec.height }
        Log.i("BooxProbe", "$message raw=${samples.minOf { it.rawPressure }}–${samples.maxOf { it.rawPressure }} max=${stroke.pressureRange.max} outside=$outside")
    }

    private fun export(): Boolean {
        adapter.close()
        try {
            val name = "boox-art-${System.currentTimeMillis()}.png"
            val file = File(filesDir, "export.png")
            check(surface.save(file)) { "Canvas is not ready" }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/BOOX Art")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = checkNotNull(contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            try {
                checkNotNull(contentResolver.openOutputStream(uri)).use { out -> file.inputStream().use { it.copyTo(out) } }
                values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            } catch (error: Exception) { contentResolver.delete(uri, null, null); throw error }
            Toast.makeText(this, "Saved to Pictures/BOOX Art/$name", Toast.LENGTH_LONG).show()
            Log.i("BooxProbe", "Exported $uri")
            return true
        } catch (error: Exception) {
            Log.e("BooxProbe", "PNG export failed", error)
            Toast.makeText(this, "Export failed: ${error.message}", Toast.LENGTH_LONG).show()
            return false
        } finally { configurePen() }
    }
}
