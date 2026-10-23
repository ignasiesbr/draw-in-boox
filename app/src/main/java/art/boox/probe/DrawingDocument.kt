package art.boox.probe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Region
import android.util.AtomicFile
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class CanvasSpec(val width: Int, val height: Int, val ppi: Int? = null) {
    val pixels: Long get() = width.toLong() * height
    val bitmapBytes: Long get() = pixels * 4
    fun swapped() = copy(width = height, height = width)
}

data class CanvasPreset(val name: String, val spec: CanvasSpec)

object CanvasSizes {
    val presets = listOf(
        CanvasPreset("BOOX screen", CanvasSpec(1860, 2480)),
        CanvasPreset("Square", CanvasSpec(2048, 2048)),
        CanvasPreset("A5 · 300 ppi", CanvasSpec(1748, 2480, 300)),
        CanvasPreset("A4 · 300 ppi", CanvasSpec(2480, 3508, 300)),
        CanvasPreset("Landscape 16:9", CanvasSpec(2560, 1440))
    )
    // Allow roughly five raster allocations within the Java heap, bounded for the first version.
    // 256 MiB Go103 heap => 12 million pixels, ~46 MiB per layer, ~229 MiB working estimate.
    val maxPixels: Long get() = minOf(12_000_000L, Runtime.getRuntime().maxMemory() / 20)
    const val MAX_SIDE = 8192
    fun error(spec: CanvasSpec): String? = when {
        spec.width <= 0 || spec.height <= 0 -> "Enter a positive width and height in pixels."
        spec.width > MAX_SIDE || spec.height > MAX_SIDE -> "Each side must be at most $MAX_SIDE pixels."
        spec.pixels > maxPixels -> "This canvas is too large. Limit: ${maxPixels / 1_000_000f} million pixels."
        else -> null
    }
    fun validate(spec: CanvasSpec) { require(error(spec) == null) { error(spec)!! } }
}

data class LayerInfo(val id: String, val name: String, val locked: Boolean, val visible: Boolean, val opacity: Int = 100, val alphaLocked: Boolean = false)
data class LayerStackState(val layers: List<LayerInfo>, val selectedId: String)
class RasterLayer(val bitmap: Bitmap, val id: String = UUID.randomUUID().toString(),
    var name: String = "Layer 1", var locked: Boolean = false, var visible: Boolean = true, var opacity: Int = 100, var alphaLocked: Boolean = false) {
    fun info() = LayerInfo(id, name, locked, visible, opacity, alphaLocked)
}

object LayerLimits {
    fun maxLayers(spec: CanvasSpec): Int {
        val heap = Runtime.getRuntime().maxMemory()
        val history = minOf(32L * 1024 * 1024, heap / 8)
        val budget = minOf(heap / 2, heap - 2 * spec.bitmapBytes - history - 40L * 1024 * 1024)
        return (budget / spec.bitmapBytes.coerceAtLeast(1)).coerceIn(1, 64).toInt()
    }
}

class DrawingDocument(val spec: CanvasSpec, initialLayers: List<RasterLayer>,
    val id: String = UUID.randomUUID().toString(), selectedId: String = initialLayers.first().id) {
    private val stack = initialLayers.toMutableList()
    val layers: List<RasterLayer> get() = stack
    var selectedLayerId = selectedId
        private set
    val activeLayer: RasterLayer get() = requireNotNull(findLayer(selectedLayerId))
    // Active-layer aliases keep raster tools independent of layer panel implementation.
    val layer: Bitmap get() = activeLayer.bitmap
    val layerId: String get() = selectedLayerId
    val canPaint: Boolean get() = !activeLayer.locked && activeLayer.visible
    val maxLayers: Int get() = LayerLimits.maxLayers(spec)
    private var composite: Bitmap? = null
    private val dirty = Region(0, 0, spec.width, spec.height)

    constructor(spec: CanvasSpec, layer: Bitmap, id: String = UUID.randomUUID().toString(),
        layerId: String = UUID.randomUUID().toString()) : this(spec, listOf(RasterLayer(layer, layerId)), id, layerId)

    init {
        require(stack.isNotEmpty() && stack.size <= maxLayers && stack.map { it.id }.distinct().size == stack.size)
        require(stack.any { it.id == selectedId })
        require(stack.all { it.bitmap.width == spec.width && it.bitmap.height == spec.height && it.opacity in 0..100 })
    }
    companion object {
        const val MAX_LAYER_NAME_LENGTH = 80
        fun create(spec: CanvasSpec): DrawingDocument {
            CanvasSizes.validate(spec)
            return DrawingDocument(spec, Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888))
        }
    }
    fun findLayer(id: String): RasterLayer? = stack.firstOrNull { it.id == id }
    fun selectLayer(id: String) { require(findLayer(id) != null); selectedLayerId = id }
    fun stackState() = LayerStackState(stack.map { it.info() }, selectedLayerId)
    fun invalidateComposite(regions: List<Rect>? = null) {
        if (stack.size == 1 && composite != null) { composite?.recycle(); composite = null }
        if (regions == null) dirty.set(0, 0, spec.width, spec.height)
        else regions.forEach { dirty.op(it, Region.Op.UNION) }
    }
    fun releaseComposite() { composite?.recycle(); composite = null; invalidateComposite() }
    fun drawComposite(canvas: Canvas, paint: Paint? = null) {
        if (stack.size == 1) {
            drawLayer(canvas, stack[0], paint)
            return
        }
        val cache = composite ?: Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888).also {
            composite = it; invalidateComposite()
        }
        if (!dirty.isEmpty) {
            val target = Canvas(cache)
            val saved = target.save()
            target.clipPath(dirty.boundaryPath)
            target.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            stack.forEach { drawLayer(target, it) }
            target.restoreToCount(saved); dirty.setEmpty()
        }
        canvas.drawBitmap(cache, 0f, 0f, paint)
    }
    /** Group opacity: an in-progress stroke and its existing layer fade together. */
    fun drawLayer(canvas: Canvas, layer: RasterLayer, paint: Paint? = null, pending: ((Canvas) -> Unit)? = null) {
        if (!layer.visible || layer.opacity == 0) return
        val alpha = (layer.opacity * 255 + 50) / 100
        if (pending != null && (alpha < 255 || layer.alphaLocked)) {
            val saved = canvas.saveLayerAlpha(0f, 0f, spec.width.toFloat(), spec.height.toFloat(), alpha)
            try { canvas.drawBitmap(layer.bitmap, 0f, 0f, paint); pending(canvas) }
            finally { canvas.restoreToCount(saved) }
        } else {
            val effective = if (alpha == 255) paint else Paint(paint ?: Paint()).apply {
                this.alpha = (this.alpha * alpha + 127) / 255
            }
            canvas.drawBitmap(layer.bitmap, 0f, 0f, effective)
            pending?.invoke(canvas)
        }
    }
    fun clear() { check(!activeLayer.alphaLocked) { "Turn off alpha lock before clearing" }; check(canPaint) { "Selected layer is locked or hidden" }; layer.eraseColor(Color.TRANSPARENT); invalidateComposite() }
    private fun requireRoom() { check(stack.size < maxLayers) { "Layer memory limit reached ($maxLayers for this canvas)." } }
    private fun nextName(): String = "Layer ${stack.mapNotNull { it.name.removePrefix("Layer ").toIntOrNull() }.maxOrNull()?.plus(1) ?: 1}"
    fun addLayer() {
        requireRoom()
        val added = RasterLayer(Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888), name = nextName())
        stack.add(stack.indexOf(activeLayer) + 1, added); selectedLayerId = added.id; invalidateComposite()
    }
    fun duplicateLayer(id: String) {
        requireRoom()
        val source = requireNotNull(findLayer(id))
        val added = RasterLayer(checkNotNull(source.bitmap.copy(Bitmap.Config.ARGB_8888, true)),
            name = "${source.name} copy", visible = source.visible, opacity = source.opacity, alphaLocked = source.alphaLocked)
        stack.add(stack.indexOf(source) + 1, added); selectedLayerId = added.id; invalidateComposite()
    }
    fun deleteLayer(id: String) {
        check(stack.size > 1) { "Keep at least one layer" }
        val source = requireNotNull(findLayer(id)); check(!source.locked) { "Unlock the layer first" }
        val index = stack.indexOf(source)
        stack.removeAt(index)
        if (selectedLayerId == id) selectedLayerId = stack[(index - 1).coerceAtLeast(0)].id
        source.bitmap.recycle(); invalidateComposite()
    }
    fun moveLayer(id: String, delta: Int) {
        val source = requireNotNull(findLayer(id)); val index = stack.indexOf(source)
        val target = (index + delta).coerceIn(0, stack.lastIndex)
        stack.removeAt(index); stack.add(target, source); selectedLayerId = id; invalidateComposite()
    }
    fun setLayerLocked(id: String, locked: Boolean) { requireNotNull(findLayer(id)).locked = locked }
    fun renameLayer(id: String, name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Enter a layer name." }
        require(trimmed.length <= MAX_LAYER_NAME_LENGTH) { "Layer names can have up to $MAX_LAYER_NAME_LENGTH characters." }
        require(trimmed.none { it.isISOControl() }) { "Enter a single-line layer name." }
        requireNotNull(findLayer(id)).name = trimmed
    }
    fun setLayerProperties(id: String, properties: LayerProperties) {
        require(properties.opacity in 0..100) { "Opacity must be between 0 and 100 percent" }
        val layer = requireNotNull(findLayer(id))
        layer.opacity = properties.opacity; layer.alphaLocked = properties.alphaLocked; invalidateComposite()
    }
    fun setLayerOpacity(id: String, opacity: Int) {
        require(opacity in 0..100) { "Opacity must be between 0 and 100 percent" }
        requireNotNull(findLayer(id)).opacity = opacity; invalidateComposite()
    }
    fun setLayerVisible(id: String, visible: Boolean) { requireNotNull(findLayer(id)).visible = visible; invalidateComposite() }
    fun mergeDown(id: String) {
        val source = requireNotNull(findLayer(id)); val index = stack.indexOf(source)
        check(index > 0) { "No layer below to merge with" }
        val below = stack[index - 1]
        check(!source.locked && !below.locked) { "Unlock both layers before merging" }
        check(source.visible && below.visible) { "Show both layers before merging" }
        if (source.opacity == 100 && below.opacity == 100) {
            Canvas(below.bitmap).drawBitmap(source.bitmap, 0f, 0f, null)
        } else {
            // Bake both opacities into pixels, then reset the retained layer to 100%.
            // Allocate before touching either original so allocation failure is harmless.
            val merged = Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888)
            try {
                Canvas(merged).also { drawLayer(it, below); drawLayer(it, source) }
                below.bitmap.eraseColor(Color.TRANSPARENT)
                Canvas(below.bitmap).drawBitmap(merged, 0f, 0f, null)
                below.opacity = 100
            } finally { merged.recycle() }
        }
        stack.removeAt(index); selectedLayerId = below.id; source.bitmap.recycle(); invalidateComposite()
    }

    /** Allocate missing layers before changing the stack, preserving existing state on failure. */
    fun restoreStack(state: LayerStackState) {
        require(state.layers.isNotEmpty() && state.layers.size <= maxLayers)
        val fresh = mutableListOf<RasterLayer>()
        try {
            state.layers.filter { findLayer(it.id) == null }.forEach {
                fresh += RasterLayer(Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888), it.id, it.name)
            }
        } catch (error: Throwable) { fresh.forEach { it.bitmap.recycle() }; throw error }
        val all = stack + fresh
        val restored = state.layers.map { info -> all.first { it.id == info.id }.apply {
            name = info.name; locked = info.locked; visible = info.visible; opacity = info.opacity; alphaLocked = info.alphaLocked
        } }
        stack.filter { old -> restored.none { it.id == old.id } }.forEach { it.bitmap.recycle() }
        stack.clear(); stack.addAll(restored); selectedLayerId = state.selectedId; invalidateComposite()
        if (stack.size == 1) releaseComposite()
    }
    fun exportPng(file: File): Boolean {
        val flattened = Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888)
        return try {
            Canvas(flattened).apply { drawColor(Color.WHITE); drawComposite(this) }
            file.outputStream().use { flattened.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { flattened.recycle() }
    }
    fun recycle() { stack.forEach { it.bitmap.recycle() }; releaseComposite() }
}

data class LoadedDocument(val document: DrawingDocument, val viewport: ViewportState?)

/** One atomic archive keeps IDs, dimensions, viewport, and layer pixels consistent. */
class DocumentStore(private val directory: File) {
    val file = File(directory, "document.boox")

    fun loadOrCreate(default: CanvasSpec): LoadedDocument {
        val atomic = AtomicFile(file)
        if (file.exists() || File(file.path + ".bak").exists()) {
            atomic.openRead().close() // Recover AtomicFile's backup if an earlier write was interrupted.
            ZipFile(file).use { archive ->
                val metadata = JSONObject(archive.getInputStream(checkNotNull(archive.getEntry("document.json")))
                    .bufferedReader().use { it.readText() })
                val version = metadata.getInt("version")
                require(version in 1..2) { "Unsupported document version" }
                val spec = CanvasSpec(metadata.getInt("width"), metadata.getInt("height"),
                    if (metadata.has("ppi")) metadata.getInt("ppi") else null)
                CanvasSizes.validate(spec)
                val items = if (version == 1) listOf(JSONObject().put("id", metadata.getString("layerId")).put("name", "Layer 1"))
                    else metadata.getJSONArray("layers").let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
                require(items.isNotEmpty() && items.size <= LayerLimits.maxLayers(spec)) { "Layer memory limit exceeded" }
                val ids = items.map { it.getString("id") }
                require(ids.distinct().size == ids.size && ids.all { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }) { "Invalid layer IDs" }
                val selected = if (version == 1) ids.first() else metadata.getString("selectedLayerId")
                require(selected in ids) { "Selected layer is missing" }
                // Parse/validate viewport before allocating any layer pixels.
                val viewport = metadata.optJSONObject("viewport")?.let {
                    ViewportState(it.getDouble("scale").toFloat(), it.getDouble("centerX").toFloat(),
                        it.getDouble("centerY").toFloat(), it.getBoolean("fit"), it.optDouble("rotationDegrees", 0.0).toFloat())
                }
                require(viewport == null || (viewport.scale.isFinite() && viewport.scale > 0f &&
                    viewport.centerX.isFinite() && viewport.centerY.isFinite() && viewport.rotationDegrees.isFinite())) { "Invalid viewport" }
                val opacities = items.map {
                    val value = if (it.has("opacity")) it.getDouble("opacity") else 100.0
                    require(value.isFinite() && value in 0.0..100.0 && value % 1.0 == 0.0) { "Invalid layer opacity" }
                    value.toInt()
                }
                val layers = mutableListOf<RasterLayer>()
                val document = try {
                    items.forEachIndexed { index, item ->
                        val layerId = item.getString("id")
                        val entry = checkNotNull(archive.getEntry(if (version == 1) "layer.png" else "layers/$layerId.png"))
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        archive.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
                        check(bounds.outWidth == spec.width && bounds.outHeight == spec.height) { "Layer pixels do not match document dimensions" }
                        val options = BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }
                        val bitmap = checkNotNull(archive.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, options) })
                        layers += RasterLayer(bitmap, layerId, item.optString("name", "Layer ${layers.size + 1}"),
                            item.optBoolean("locked", false), item.optBoolean("visible", true), opacities[index], item.optBoolean("alphaLocked", false))
                    }
                    DrawingDocument(spec, layers, metadata.getString("id"), selected)
                } catch (error: Throwable) { layers.forEach { it.bitmap.recycle() }; throw error }
                return LoadedDocument(document, viewport)
            }
        }
        val legacy = File(directory, "canvas.png")
        val document = if (legacy.exists()) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(legacy.path, bounds)
            val spec = CanvasSpec(bounds.outWidth, bounds.outHeight)
            CanvasSizes.validate(spec)
            val options = BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            DrawingDocument(spec, checkNotNull(BitmapFactory.decodeFile(legacy.path, options)))
        } else DrawingDocument.create(default)
        // Legacy canvas.png remains untouched; the new archive becomes authoritative.
        try { save(document, null) } catch (error: Exception) { document.recycle(); throw error }
        return LoadedDocument(document, null)
    }

    fun save(document: DrawingDocument, viewport: ViewportState?) {
        directory.mkdirs()
        val metadata = JSONObject().put("version", 2).put("id", document.id)
            .put("selectedLayerId", document.selectedLayerId)
            .put("layers", JSONArray().apply { document.layers.forEach { layer ->
                put(JSONObject().put("id", layer.id).put("name", layer.name).put("locked", layer.locked).put("visible", layer.visible).put("opacity", layer.opacity).put("alphaLocked", layer.alphaLocked))
            } })
            .put("width", document.spec.width).put("height", document.spec.height)
        document.spec.ppi?.let { metadata.put("ppi", it) }
        viewport?.let { metadata.put("viewport", JSONObject().put("scale", it.scale)
            .put("centerX", it.centerX).put("centerY", it.centerY).put("fit", it.fit).put("rotationDegrees", it.rotationDegrees)) }
        val target = AtomicFile(file)
        val output = target.startWrite()
        try {
            val nonClosing = object : java.io.FilterOutputStream(output) {
                override fun close() = flush()
                override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
            }
            ZipOutputStream(nonClosing).use { archive ->
                archive.putNextEntry(ZipEntry("document.json"))
                archive.write(metadata.toString().toByteArray(Charsets.UTF_8))
                archive.closeEntry()
                document.layers.forEach { layer ->
                    archive.putNextEntry(ZipEntry("layers/${layer.id}.png"))
                    check(layer.bitmap.compress(Bitmap.CompressFormat.PNG, 100, archive)) { "Cannot save layer pixels" }
                    archive.closeEntry()
                }
            }
            target.finishWrite(output)
        } catch (error: Throwable) { target.failWrite(output); throw error }
    }
}
