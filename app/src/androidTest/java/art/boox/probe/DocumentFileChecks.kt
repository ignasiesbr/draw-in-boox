package art.boox.probe

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object DocumentFileChecks {
    fun run(context: Context) {
        val directory = File(context.cacheDir, "portable-document-checks").apply { mkdirs() }
        val file = File(directory, "drawing.boox")
        val changed = File(directory, "changed.boox")
        val source = DrawingDocument.create(CanvasSpec(32, 24, 300))
        try {
            source.penSettings = PenSettings(pens = BrushPreset.entries.mapIndexed { index, preset ->
                preset.id to preset.defaults().copy(size = minOf(index + 1f, preset.maxSize),
                    shade = InkShade.entries[index % InkShade.entries.size],
                    nativePreviewScale = 0.75f, nativePreviewTexture = NativePreviewTexture.COARSE)
            }.toMap()).with(BrushPreset.BOOX_PENCIL_FINE.defaults().copy(size = 17f, shade = InkShade.DEEP))
            source.renameLayer(source.layerId, "Paper · 底")
            for (x in 0 until 32) for (y in 0 until 24) source.layer.setPixel(x, y, Color.argb((x * 8 + y) % 256, 0, 0, 0))
            source.addLayer()
            source.layer.setPixel(31, 23, Color.GRAY)
            source.setLayerProperties(source.layerId, LayerProperties(37, true))
            source.setLayerLocked(source.layerId, true)
            source.setLayerVisible(source.layerId, false)
            source.addLayer()
            source.layer.setPixel(0, 0, Color.BLACK)
            source.setLayerOpacity(source.layerId, 0)
            source.selectLayer(source.layers[1].id)
            DocumentFile.write(file, source)
            val loaded = DocumentFile.read(file)
            try { same(source, loaded) } finally { loaded.recycle() }
            val entries = contents(file)
            val manifest = JSONObject(checkNotNull(entries["manifest.json"]).toString(Charsets.UTF_8))
            check(entries.size == source.layers.size + 1)
            check(!manifest.has("viewport") && !manifest.has("history") && !manifest.has("brush"))
            check(manifest.getJSONObject("version").getInt("major") == 1)
            check(manifest.getJSONObject("version").getInt("minor") == 1)
            check(manifest.getJSONObject("penSettings").getJSONObject("pens").length() == BrushPreset.entries.size)

            // Original 1.0 files still load without tool metadata, so the editor can seed preferences.
            val legacy = JSONObject(manifest.toString()).apply {
                getJSONObject("version").put("minor", 0)
                remove("penSettings")
            }
            rewrite(changed, entries, legacy)
            DocumentFile.read(changed).also { doc ->
                try {
                    check(doc.penSettings == null)
                    check(doc.layer.sameAs(source.layer))
                } finally { doc.recycle() }
            }
            // Optional tool metadata cannot make raster artwork unreadable on another app version.
            val futurePens = JSONObject(manifest.toString()).apply {
                getJSONObject("penSettings").apply {
                    put("selectedPenId", "future-pen")
                    getJSONObject("pens").put("future-pen", JSONObject().put("size", 999))
                    getJSONObject("pens").getJSONObject("ink").put("size", -1).put("shade", 123).put("tilt", "yes")
                    getJSONObject("pens").remove("monoline")
                }
            }
            rewrite(changed, entries, futurePens)
            DocumentFile.read(changed).also { doc ->
                try {
                    val pens = checkNotNull(doc.penSettings)
                    check(pens.selectedId == "ink")
                    check(pens.get(BrushPreset.INK).size == BrushPreset.INK.defaults().size)
                    check(pens.get(BrushPreset.INK).shade == InkShade.BLACK && !pens.get(BrushPreset.INK).tilt)
                    check(pens.get(BrushPreset.MONOLINE) == BrushPreset.MONOLINE.defaults())
                    check(doc.layer.sameAs(source.layer))
                } finally { doc.recycle() }
            }
            rewrite(changed, entries, JSONObject(manifest.toString()).put("penSettings", JSONObject().put("version", 99)))
            DocumentFile.read(changed).also { try { check(it.penSettings == null) } finally { it.recycle() } }

            // A future additive revision is readable; missing optional properties use stable defaults.
            val future = JSONObject(manifest.toString()).apply {
                getJSONObject("version").put("minor", 99)
                put("futureOptionalMetadata", JSONObject().put("note", "ignore safely"))
                remove("selectedLayerId")
                getJSONArray("layers").getJSONObject(1).apply {
                    listOf("name", "locked", "visible", "opacity", "alphaLocked", "type").forEach { remove(it) }
                }
            }
            rewrite(changed, entries, future)
            DocumentFile.read(changed).also { doc ->
                try {
                    check(doc.selectedLayerId == doc.layers.first().id)
                    val layer = doc.layers[1]
                    check(layer.name == "Layer 2" && layer.visible && !layer.locked && layer.opacity == 100 && !layer.alphaLocked)
                    check(layer.bitmap.sameAs(source.layers[1].bitmap))
                } finally { doc.recycle() }
            }

            fun rejects(edit: (JSONObject) -> Unit) {
                val invalid = JSONObject(manifest.toString()).also(edit)
                rewrite(changed, entries, invalid)
                expectFailure { DocumentFile.read(changed).recycle() }
            }
            rejects { it.put("format", "other") }
            rejects { it.getJSONObject("version").put("major", 2) }
            rejects { it.put("requiredFeatures", JSONArray().put("future-masks")) }
            rejects { it.put("requiredFeatures", "future-masks") }
            rejects { it.put("width", 1.5) }
            rejects { it.put("width", Int.MAX_VALUE) }
            rejects { it.put("ppi", 0) }
            rejects { it.put("selectedLayerId", "missing") }
            rejects { it.getJSONArray("layers").getJSONObject(1).put("id", source.layers.first().id) }
            rejects { it.getJSONArray("layers").getJSONObject(0).put("pixels", "../layer.png") }
            rejects { it.getJSONArray("layers").getJSONObject(0).put("type", "vector") }
            rejects { it.getJSONArray("layers").getJSONObject(0).put("opacity", 0.5) }
            rejects { it.getJSONArray("layers").getJSONObject(0).put("locked", "yes") }
            rejects { it.put("layers", JSONArray()) }
            val pixelPath = "layers/${source.layers.first().id}.png"
            rewrite(changed, entries - pixelPath, manifest)
            expectFailure { DocumentFile.read(changed).recycle() }
            rewrite(changed, entries + (pixelPath to byteArrayOf(1, 2, 3)), manifest)
            expectFailure { DocumentFile.read(changed).recycle() }
            rewrite(changed, entries, JSONObject(manifest.toString()).put("width", 33))
            expectFailure { DocumentFile.read(changed).recycle() }
            // A damaged ZIP CRC must fail even if the decoded JSON/image would otherwise look valid.
            val damaged = file.readBytes()
            val central = byteArrayOf(0x50, 0x4b, 0x01, 0x02)
            val header = (0 until damaged.size - 46).first { offset ->
                central.indices.all { damaged[offset + it] == central[it] }
            }
            damaged[header + 16] = (damaged[header + 16].toInt() xor 1).toByte()
            changed.writeBytes(damaged)
            expectFailure { DocumentFile.read(changed).recycle() }
            changed.writeBytes(byteArrayOf(1, 2, 3))
            expectFailure { DocumentFile.read(changed).recycle() }

            // Failure after AtomicFile.startWrite must restore the last complete save.
            val broken = DrawingDocument.create(CanvasSpec(16, 16))
            broken.layer.recycle()
            val before = file.readBytes()
            try { expectFailure { DocumentFile.write(file, broken) } } finally { broken.recycle() }
            check(before.contentEquals(file.readBytes())) { "A failed save replaced the previous file" }
            DocumentFile.read(file).also { try { same(source, it) } finally { it.recycle() } }
            expectFailure {
                byteArrayOf(1, 2, 3).inputStream().use { input ->
                    java.io.ByteArrayOutputStream().use { input.copyLimited(it, 2) }
                }
            }
        } finally { source.recycle(); file.delete(); changed.delete() }
    }

    /** Runs actual asynchronous file actions against an isolated editor, including replacement failure. */
    fun actions(instrumentation: Instrumentation) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val directory = File(activity.cacheDir, "portable-action-checks").apply { mkdirs() }
        val context = object : ContextWrapper(activity) { override fun getFilesDir() = directory }
        val checkpoint = File(directory, "document.boox")
        checkpoint.delete()
        lateinit var surface: RasterSurface
        lateinit var actions: DocumentActions
        val file = File(directory, "copy.boox")
        val deferred = File(directory, "deferred-copy.boox")
        var original: DrawingDocument? = null
        try {
            instrumentation.runOnMainSync {
                surface = RasterSurface(context, CanvasSpec(32, 24)) { }
                actions = DocumentActions(activity, surface, { false }, { surface.inputEnabled = false }, { })
                surface.ready = actions::documentReady
                // Recreated activities receive the result before their first surface/document load.
                check(actions.onResult(101, Activity.RESULT_OK, Intent().setData(Uri.fromFile(deferred))))
                check(!actions.busy && surface.document == null)
                surface.surfaceChanged(surface.holder, 0, 100, 100)
                check(actions.busy)
            }
            await(instrumentation, actions)
            DocumentFile.read(deferred).also { try { same(checkNotNull(surface.document), it) } finally { it.recycle() } }
            instrumentation.runOnMainSync {
                surface.commit(listOf(PenSample(10f, 10f, 1f, 1)), "test")
                surface.applyLayerCommand(LayerCommand.Add)
                surface.commit(listOf(PenSample(20f, 20f, 1f, 1)), "test")
                surface.rotateView(37f)
                surface.selectBrush(BrushPreset.INK.defaults().copy(size = 3f, shade = InkShade.DEEP))
                surface.selectBrush(BrushPreset.MONOLINE.defaults().copy(size = 49f, shade = InkShade.PALE))
                original = surface.document
                check(actions.onResult(101, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file))))
            }
            await(instrumentation, actions)
            check(file.exists())
            val saved = DocumentFile.read(file)
            try { same(checkNotNull(original), saved) } finally { saved.recycle() }
            instrumentation.runOnMainSync {
                surface.applyLayerCommand(LayerCommand.Add)
                surface.selectBrush(BrushPreset.INK.defaults().copy(size = 10f, shade = InkShade.BLACK))
                check(surface.history.canUndo)
                check(actions.onResult(102, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file))))
            }
            await(instrumentation, actions)
            instrumentation.runOnMainSync {
                check(surface.document !== original && checkNotNull(original).layer.isRecycled)
                check(surface.document!!.layers.size == 2)
                check(!surface.history.canUndo && !surface.history.canRedo)
                check(surface.viewport.rotationDegrees == 0f && surface.viewport.state().fit)
                check(surface.brush.settings == BrushPreset.MONOLINE.defaults().copy(size = 49f, shade = InkShade.PALE))
                check(surface.settingsFor(BrushPreset.INK) == BrushPreset.INK.defaults().copy(size = 3f, shade = InkShade.DEEP))
            }
            val restored = DocumentStore(directory).loadOrCreate(CanvasSpec(1, 1))
            try { same(checkNotNull(surface.document), restored.document) } finally { restored.document.recycle() }
            val retained = checkNotNull(surface.document)
            val before = checkpoint.readBytes()
            file.writeText("bad archive")
            instrumentation.runOnMainSync {
                actions.onResult(102, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
            }
            await(instrumentation, actions)
            check(surface.document === retained && !retained.layer.isRecycled)
            check(before.contentEquals(checkpoint.readBytes())) { "Failed import replaced the checkpoint" }
            // A failed output cannot replace the editor or create a false successful continuation.
            instrumentation.runOnMainSync {
                actions.onResult(101, Activity.RESULT_OK, Intent().setData(Uri.fromFile(directory)))
            }
            await(instrumentation, actions)
            check(surface.document === retained && !retained.layer.isRecycled)
            // Activity destruction must not recycle the frozen source under an active encoder.
            instrumentation.runOnMainSync {
                actions.onResult(101, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
                actions.destroy()
                check(!retained.layer.isRecycled)
            }
            await(instrumentation, actions)
            check(surface.document == null && retained.layer.isRecycled)
            DocumentFile.read(file).also { try { check(it.layers.size == 2) } finally { it.recycle() } }
            // Open also waits for startup, including recovery after an unreadable private checkpoint.
            for (corruptStartup in listOf(false, true)) {
                instrumentation.runOnMainSync {
                    if (corruptStartup) checkpoint.writeText("broken startup checkpoint")
                    surface = RasterSurface(context, CanvasSpec(32, 24)) { }
                    actions = DocumentActions(activity, surface, { false }, { surface.inputEnabled = false }, { })
                    surface.ready = actions::documentReady
                    surface.error = { actions.documentReady() }
                    actions.onResult(102, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
                    check(!actions.busy && !surface.initializationComplete)
                    surface.surfaceChanged(surface.holder, 0, 100, 100)
                    check(actions.busy)
                }
                await(instrumentation, actions)
                instrumentation.runOnMainSync {
                    check(surface.document!!.id == retained.id && surface.document!!.layers.size == 2)
                    check(surface.viewport.rotationDegrees == 0f && !surface.history.canUndo)
                    actions.destroy()
                }
            }
        } finally {
            instrumentation.runOnMainSync { actions.destroy(); activity.finish() }
            instrumentation.waitForIdleSync()
            file.delete(); deferred.delete(); checkpoint.delete()
        }
    }

    private fun await(instrumentation: Instrumentation, actions: DocumentActions) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (true) {
            var busy = true
            instrumentation.runOnMainSync { busy = actions.busy }
            if (!busy) break
            check(SystemClock.uptimeMillis() < deadline) { "Document I/O did not complete" }
            SystemClock.sleep(30)
        }
        instrumentation.waitForIdleSync()
    }

    private fun same(expected: DrawingDocument, actual: DrawingDocument) {
        check(actual.spec == expected.spec && actual.id == expected.id)
        check(actual.stackState() == expected.stackState())
        check(actual.penSettings == expected.penSettings) { "Pen settings changed" }
        expected.layers.zip(actual.layers).forEach { (a, b) -> check(a.bitmap.sameAs(b.bitmap)) { "Layer pixels changed" } }
    }

    private fun contents(file: File): Map<String, ByteArray> = ZipFile(file).use { zip ->
        zip.entries().asSequence().associate { entry -> entry.name to zip.getInputStream(entry).use { it.readBytes() } }
    }

    private fun rewrite(file: File, entries: Map<String, ByteArray>, manifest: JSONObject) {
        ZipOutputStream(file.outputStream()).use { zip ->
            (entries + ("manifest.json" to manifest.toString().toByteArray(Charsets.UTF_8))).forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
            }
        }
    }

    private fun expectFailure(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: Exception) { failed = true }
        check(failed) { "Invalid drawing was accepted" }
    }
}
