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
import android.text.InputType
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {
    private lateinit var surface: RasterSurface
    private lateinit var adapter: BooxPenAdapter
    private lateinit var status: TextView
    private lateinit var mode: Button
    private lateinit var documentInfo: TextView
    private lateinit var undoButton: EinkIconButton
    private lateinit var redoButton: EinkIconButton
    private lateinit var layerPanel: LayerPanel
    private lateinit var clearButton: Button
    private lateinit var settingsStore: AppSettingsStore
    private var settings = AppSettings()
    private lateinit var drawingArea: LinearLayout
    private lateinit var canvasArea: FrameLayout
    private var preferBoox = true
    private var resumed = false
    private var strokeCount = 0
    private var penConfigurationGeneration = 0
    private lateinit var brushStore: BrushSettingsStore
    private lateinit var brushLabel: TextView
    private val brushButtons = mutableMapOf<BrushPreset, EinkIconButton>()
    private val strokeDiagnostics = StrokeDiagnostics()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        settingsStore = AppSettingsStore(this)
        settings = settingsStore.load()
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
        clearButton = button(toolbar, "Clear") {
            android.app.AlertDialog.Builder(this).setMessage("Clear the selected layer?")
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
        navigation.addView(EinkIconButton(this, UiIcon.SETTINGS, "Settings").apply {
            tag = "Settings"
            setOnClickListener { showSettings() }
        }, LinearLayout.LayoutParams(dp(52), dp(60)))
        root.addView(navigation)
        val rotation = LinearLayout(this)
        button(rotation, "Rotate −15°") { rotateView(surface.viewport.rotationDegrees - 15f) }
        button(rotation, "Angle…") { chooseAngle() }
        button(rotation, "Rotate +15°") { rotateView(surface.viewport.rotationDegrees + 15f) }
        button(rotation, "Reset angle") { rotateView(0f) }
        root.addView(rotation)
        status = TextView(this).apply {
            textSize = 16f; setTextColor(Color.BLACK); setPadding(20, 6, 20, 8)
            text = "Draw with the stylus. Fingers do not draw."
            height = dp(48)
            maxLines = 2
        }
        root.addView(status)
        surface = RasterSurface(this, strokeFinished = ::recordStroke)
        surface.configureHistoryGestures(settings.undoGestureEnabled, settings.redoGestureEnabled)
        brushStore = BrushSettingsStore(this)
        val initialPreset = if (BuildConfig.BOOX_LAB && !getSharedPreferences("app_settings", 0).contains("brush_selected"))
            BrushPreset.LAB_FOUNTAIN else brushStore.selected()
        surface.selectBrush(brushStore.load(initialPreset))
        adapter = BooxPenAdapter(surface) { message ->
            status.text = message
            mode.text = if (surface.rawInput) "BOOX pen" else "Android pen"
        }
        surface.ready = { updateDocumentInfo(); schedulePenConfiguration() }
        surface.unavailable = { adapter.close() }
        surface.viewportWillChange = { surface.inputEnabled = false; adapter.close() }
        surface.navigationStarted = { penConfigurationGeneration++; adapter.close() }
        surface.historyChanged = {
            undoButton.isEnabled = surface.history.canUndo
            redoButton.isEnabled = surface.history.canRedo
            if (::layerPanel.isInitialized) layerPanel.invalidateThumbnails()
        }
        surface.layersChanged = {
            clearButton.isEnabled = surface.canDraw && surface.document?.activeLayer?.alphaLocked != true
            if (::layerPanel.isInitialized) layerPanel.render(surface.document)
        }
        surface.historyFeedback = { status.text = it; Log.i("BooxProbe", it) }
        surface.error = { message -> status.text = message; Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        val drawingTools = LinearLayout(this)
        BrushPreset.available.forEach { preset ->
            val control = EinkIconButton(this, preset.icon, preset.label, toggleable = true).apply {
                tag = "brush.${preset.id}"
                setOnClickListener {
                    if (surface.brush.settings?.preset == preset) editBrush()
                    else changeBrush(brushStore.load(preset))
                }
            }
            brushButtons[preset] = control
            drawingTools.addView(control, LinearLayout.LayoutParams(dp(52), dp(52)))
        }
        brushLabel = TextView(this).apply { textSize = 16f; setTextColor(Color.BLACK)
            gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, 0, 0) }
        drawingTools.addView(brushLabel, LinearLayout.LayoutParams(0, dp(52), 1f))
        undoButton = EinkIconButton(this, UiIcon.UNDO, "Undo").apply {
            tag = "Undo"; isEnabled = false
            setOnClickListener { if (!surface.undo()) historyBusy() }
        }
        redoButton = EinkIconButton(this, UiIcon.REDO, "Redo").apply {
            tag = "Redo"; isEnabled = false
            setOnClickListener { if (!surface.redo()) historyBusy() }
        }
        drawingTools.addView(undoButton, LinearLayout.LayoutParams(dp(52), dp(52)))
        drawingTools.addView(redoButton, LinearLayout.LayoutParams(dp(52), dp(52)))
        root.addView(drawingTools)
        updateBrushControls()
        canvasArea = FrameLayout(this)
        canvasArea.addView(surface, FrameLayout.LayoutParams(-1, -1))
        val gestureIndicator = GestureIndicator(this, adapter::setIndicatorFastRefresh)
        // A 280dp square fits the badge's diagonal at every rotation angle.
        canvasArea.addView(gestureIndicator, FrameLayout.LayoutParams(dp(280), dp(280),
            android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(12) })
        surface.rotationPreviewChanged = gestureIndicator::showAngle
        surface.zoomPreviewChanged = gestureIndicator::showZoom
        layerPanel = LayerPanel(this, getPreferences(0).getBoolean("layersExpanded", true),
            toggle = { expanded ->
                val allowed = surface.preparePanelResize()
                if (allowed) getPreferences(0).edit().putBoolean("layersExpanded", expanded).apply()
                else historyBusy()
                allowed
            },
            select = { id -> if (!surface.selectLayer(id)) historyBusy() },
            command = { command ->
                try { if (!surface.applyLayerCommand(command)) { historyBusy(); layerPanel.render(surface.document) } }
                catch (failure: Exception) { Toast.makeText(this, failure.message, Toast.LENGTH_LONG).show() }
                catch (failure: OutOfMemoryError) { Toast.makeText(this, "Not enough memory for another layer.", Toast.LENGTH_LONG).show() }
            })
        drawingArea = LinearLayout(this)
        arrangeDrawingArea()
        root.addView(drawingArea, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        Log.i("BooxProbe", "Launch ${Build.MODEL} API=${Build.VERSION.SDK_INT} firmware=${Build.DISPLAY} penPipeline=native-eraser-v1 uiRevision=history-controls-v1 renderer=${BuildConfig.RENDERER_BACKEND} lab=${BuildConfig.BOOX_LAB}")
        Log.i("BooxProbe", "Heap=${Runtime.getRuntime().maxMemory()} maxCanvasPixels=${CanvasSizes.maxPixels}")
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun arrangeDrawingArea() {
        // Move only the panel. Keeping the SurfaceView attached preserves its bitmap and
        // surface lifecycle; a side change may retain exactly the same canvas dimensions.
        if (canvasArea.parent == null) drawingArea.addView(canvasArea, LinearLayout.LayoutParams(0, -1, 1f))
        if (layerPanel.parent != null) drawingArea.removeView(layerPanel)
        val index = if (settings.layerPanelSide == LayerPanelSide.LEFT) 0 else drawingArea.childCount
        drawingArea.addView(layerPanel, index, LinearLayout.LayoutParams(layerPanel.panelWidth, -1))
    }

    private fun showSettings() {
        if (surface.isDrawing || surface.isNavigating || adapter.isBusy) {
            Toast.makeText(this, "Finish drawing or navigation before opening settings.", Toast.LENGTH_SHORT).show()
            return
        }
        SettingsDialog.show(this, settings) { updated ->
            if (updated == settings) true
            else if (surface.isDrawing || surface.isNavigating || adapter.isBusy) {
                Toast.makeText(this, "Finish drawing or navigation before changing settings.", Toast.LENGTH_SHORT).show()
                false
            } else {
                val sideChanged = updated.layerPanelSide != settings.layerPanelSide
                if (sideChanged && !surface.preparePanelResize()) return@show false
                settings = updated
                settingsStore.save(updated)
                surface.configureHistoryGestures(updated.undoGestureEnabled, updated.redoGestureEnabled)
                // Position, rather than size, can change. Explicitly reopen raw input
                // after layout so the BOOX screen-space input region follows the canvas.
                if (sideChanged) {
                    arrangeDrawingArea()
                    drawingArea.post { schedulePenConfiguration() }
                }
                true
            }
        }
    }

    private fun historyBusy() {
        Toast.makeText(this, "Finish drawing or navigation before changing history.", Toast.LENGTH_SHORT).show()
    }

    private fun brushBusy(): Boolean {
        if (!surface.isDrawing && !surface.isNavigating && !adapter.isBusy) return false
        Toast.makeText(this, "Finish drawing or navigation before changing brushes.", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun changeBrush(settings: BrushSettings): Boolean {
        if (brushBusy()) return false
        if (!surface.selectBrush(settings)) return false
        brushStore.save(settings)
        updateBrushControls()
        return true
    }

    private fun updateBrushControls() {
        val settings = checkNotNull(surface.brush.settings)
        brushButtons.forEach { (preset, button) ->
            button.isChecked = preset.id == settings.presetId
            button.tooltipText = if (button.isChecked) "${preset.label} · tap again for settings" else preset.label
        }
        brushLabel.text = "${settings.preset.label} · ${settings.size} px${if (settings.pressure) " max" else ""}"
        brushLabel.contentDescription = "${brushLabel.text}. Tap the selected tool for settings."
        if (!surface.brush.isEraser) brushLabel.text = "${brushLabel.text} · ${settings.shade.label}"
    }

    private fun editBrush() {
        if (brushBusy()) return
        penConfigurationGeneration++
        adapter.close()
        surface.inputEnabled = false
        BrushSettingsDialog.show(this, checkNotNull(surface.brush.settings), apply = ::changeBrush)
            .setOnDismissListener { schedulePenConfiguration() }
    }

    private fun schedulePenConfiguration() {
        val generation = ++penConfigurationGeneration
        // Let heading/control invalidations reach a display frame before hardware scribble
        // takes over refresh. Opening it immediately can leave the angle/zoom label stale.
        surface.postOnAnimation {
            surface.post { if (generation == penConfigurationGeneration) configurePen() }
        }
    }

    private fun configurePen() {
        if (surface.isNavigating) return
        adapter.close()
        surface.cancelStroke()
        surface.inputEnabled = resumed && hasWindowFocus()
        if (!surface.inputEnabled || surface.document == null || surface.width == 0 || !surface.holder.surface.isValid) return
        if (!surface.canDraw) {
            mode.text = if (preferBoox) "BOOX pen" else "Android pen"
            status.text = if (surface.document!!.activeLayer.locked) "Layer locked · select another layer or unlock it"
                else "Layer hidden · show it or select another layer"
            writeDocumentState()
            return
        }
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
            .put("rotationDegrees", transform.rotationDegrees)
            .put("mode", if (surface.rawInput) "boox" else "android")
            .put("nativePreviewSupported", NativePreviewPolicy.supports(surface.brush, document, surface.viewport.transform.scale))
            .put("nativePreviewColorArgb", NativePreviewPolicy.color(surface.brush, document, surface.viewport.transform.scale) ?: JSONObject.NULL)
            .put("undoCount", surface.history.undoCount).put("redoCount", surface.history.redoCount)
            .put("historyBytes", surface.history.bytesUsed)
            .put("layerCount", document.layers.size).put("maxLayers", document.maxLayers)
            .put("layers", JSONArray().apply { document.layers.forEach { layer ->
                put(JSONObject().put("id", layer.id).put("name", layer.name).put("locked", layer.locked).put("visible", layer.visible).put("opacity", layer.opacity).put("alphaLocked", layer.alphaLocked))
            } })
            .put("maxCanvasPixels", CanvasSizes.maxPixels).put("heapLimitBytes", Runtime.getRuntime().maxMemory())
            .put("wallTimeMs", System.currentTimeMillis())
        document.spec.ppi?.let { state.put("ppi", it) }
        try { File(filesDir, "document-state.json").writeText(state.toString()) }
        catch (failure: Exception) { Log.w("BooxProbe", "Document diagnostics write failed", failure) }
        Log.i("BooxProbe", "Document ${document.id} ${document.spec.width}x${document.spec.height} zoom=${transform.scale} angle=${transform.rotationDegrees} offset=${transform.x},${transform.y}")
    }

    override fun onResume() {
        super.onResume(); resumed = true
        Log.i("BooxProbe", "Activity resumed")
        schedulePenConfiguration()
    }
    override fun onPause() {
        penConfigurationGeneration++
        resumed = false; surface.inputEnabled = false; adapter.close(); surface.checkpoint()
        Log.i("BooxProbe", "Activity paused; input closed and bitmap checkpointed")
        super.onPause()
    }
    override fun onWindowFocusChanged(focused: Boolean) {
        super.onWindowFocusChanged(focused)
        if (::adapter.isInitialized) {
            Log.i("BooxProbe", "Window focus=$focused")
            if (focused) schedulePenConfiguration()
            else { penConfigurationGeneration++; surface.inputEnabled = false; adapter.close() }
        }
    }
    override fun onDestroy() { adapter.close(); surface.dispose(); strokeDiagnostics.close(); super.onDestroy() }

    private fun updateDocumentInfo() {
        val document = surface.document ?: return
        val angle = String.format(Locale.US, "%.1f", surface.viewport.rotationDegrees)
        val title = if (BuildConfig.BOOX_LAB) "BOOX Art Lab (${BuildConfig.RENDERER_BACKEND})" else "BOOX Art"
        documentInfo.text = "$title · ${document.spec.width} × ${document.spec.height} · ${(surface.viewport.transform.scale * 100).toInt()}% · $angle°"
    }

    private fun zoom(action: ZoomAction) {
        if (!surface.zoom(action)) Toast.makeText(this, "Lift the pen before changing zoom.", Toast.LENGTH_SHORT).show()
    }

    private fun rotateView(angle: Float) {
        if (!surface.rotateView(angle)) Toast.makeText(this, "Lift the pen before rotating the view.", Toast.LENGTH_SHORT).show()
    }

    private fun chooseAngle() {
        if (surface.isDrawing) { Toast.makeText(this, "Lift the pen before rotating the view.", Toast.LENGTH_SHORT).show(); return }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(String.format(Locale.US, "%.1f", surface.viewport.rotationDegrees))
            setSelectAllOnFocus(true)
            hint = "Angle in degrees"
        }
        val dialog = android.app.AlertDialog.Builder(this).setTitle("Rotate view")
            .setMessage("Enter any angle in degrees. Exported artwork keeps its document orientation.")
            .setView(input).setNegativeButton("Cancel", null).setNeutralButton("Reset") { _, _ -> rotateView(0f) }
            .setPositiveButton("Apply", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val angle = input.text.toString().replace(',', '.').toFloatOrNull()
                if (angle == null || !angle.isFinite()) input.error = "Enter a valid angle."
                else { dialog.dismiss(); rotateView(angle) }
            }
        }
        dialog.show()
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
        // Snapshot metadata on the UI thread. Worker never reads document pixels or live UI state.
        val number = strokeCount
        val document = checkNotNull(surface.document)
        val documentId = document.id
        val layerId = document.layerId
        val spec = document.spec
        val transform = surface.viewport.transform
        val viewWidth = surface.width
        val viewHeight = surface.height
        val wallTime = System.currentTimeMillis()
        val logFile = File(filesDir, "strokes.jsonl")
        strokeDiagnostics.submit {
        val points = JSONArray()
        samples.forEach { p -> points.put(JSONArray().put(p.x).put(p.y).put(p.pressure).put(p.time).put(p.rawPressure)
            .put(p.tiltRadians ?: JSONObject.NULL).put(p.viewOrientationRadians ?: JSONObject.NULL)
            .put(p.rawTiltX ?: JSONObject.NULL).put(p.rawTiltY ?: JSONObject.NULL)) }
        val range = JSONObject().put("min", stroke.pressureRange.min).put("max", stroke.pressureRange.max)
            .put("origin", stroke.pressureRange.origin)
        val captured = stroke.brush
        val brush = JSONObject().put("minWidthPx", captured.minWidth).put("maxWidthPx", captured.maxWidth)
            .put("curve", "linear").put("widthModifier", captured.widthModifier.id)
            .put("maxWidthMultiplier", captured.widthModifier.maxMultiplier)
            .put("maxRenderedWidthPx", captured.maxRenderedWidth)
            .put("engine", captured.engineId).put("engineVersion", captured.engineVersion)
            .put("presetId", captured.settings?.presetId ?: JSONObject.NULL)
            .put("settingsVersion", 2).put("preview", if (source == "boox") {
                if (captured.isEraser) "native-white-eraser" else if (captured.isBooxFountain) "native-fountain" else "native-round-width"
            } else "app-round-brush")
            .put("operation", if (captured.isEraser) "erase" else "paint")
            .put("inkColorArgb", captured.inkColor).put("grayLevel", android.graphics.Color.red(captured.inkColor))
        val entry = JSONObject().put("schemaVersion", 8).put("source", source).put("stroke", number)
            .put("pressureRange", range).put("brush", brush)
            .put("documentId", documentId).put("layerId", layerId)
            .put("coordinateSpace", "document").put("canvasWidth", spec.width).put("canvasHeight", spec.height)
            .put("viewWidth", viewWidth).put("viewHeight", viewHeight)
            .put("viewport", JSONObject().put("scale", transform.scale).put("x", transform.x).put("y", transform.y)
                .put("rotationDegrees", transform.rotationDegrees))
            .put("wallTimeMs", wallTime).put("points", points)
        try { logFile.appendText("$entry\n") }
        catch (error: Exception) { Log.e("BooxProbe", "Stroke log write failed", error) }
        val low = (samples.minOf { it.pressure } * 100).toInt()
        val high = (samples.maxOf { it.pressure } * 100).toInt()
        val message = "Stroke $number · $source · ${samples.size} points · pressure $low–$high%"
        val tilts = samples.mapNotNull { it.tiltRadians }
        val tiltInfo = if (tilts.isEmpty()) "tilt unavailable" else
            "tilt ${Math.toDegrees(tilts.min().toDouble()).toInt()}-${Math.toDegrees(tilts.max().toDouble()).toInt()} deg"
        status.post { if (!isDestroyed && resumed && number == strokeCount) status.text = "$message | $tiltInfo" }
        Log.i("BooxProbe", "$tiltInfo rawTiltX=${samples.mapNotNull { it.rawTiltX }.let { it.minOrNull() to it.maxOrNull() }} rawTiltY=${samples.mapNotNull { it.rawTiltY }.let { it.minOrNull() to it.maxOrNull() }}")
        val outside = samples.count { it.x < 0 || it.y < 0 || it.x >= spec.width || it.y >= spec.height }
        Log.i("BooxProbe", "$message raw=${samples.minOf { it.rawPressure }}–${samples.maxOf { it.rawPressure }} max=${stroke.pressureRange.max} outside=$outside")
        }
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
