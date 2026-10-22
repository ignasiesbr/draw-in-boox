package art.boox.probe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.AtomicFile
import org.json.JSONObject
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

class DrawingDocument(
    val spec: CanvasSpec,
    val layer: Bitmap,
    val id: String = UUID.randomUUID().toString(),
    val layerId: String = UUID.randomUUID().toString()
) {
    companion object {
        fun create(spec: CanvasSpec): DrawingDocument {
            CanvasSizes.validate(spec)
            return DrawingDocument(spec, Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888))
        }
    }
    fun clear() = layer.eraseColor(Color.TRANSPARENT)
    fun exportPng(file: File): Boolean {
        val flattened = Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888)
        return try {
            Canvas(flattened).apply { drawColor(Color.WHITE); drawBitmap(layer, 0f, 0f, null) }
            file.outputStream().use { flattened.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { flattened.recycle() }
    }
    fun recycle() = layer.recycle()
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
                require(metadata.getInt("version") == 1) { "Unsupported document version" }
                val spec = CanvasSpec(metadata.getInt("width"), metadata.getInt("height"),
                    if (metadata.has("ppi")) metadata.getInt("ppi") else null)
                CanvasSizes.validate(spec)
                val entry = checkNotNull(archive.getEntry("layer.png"))
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                archive.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
                check(bounds.outWidth == spec.width && bounds.outHeight == spec.height) { "Document pixels do not match its dimensions" }
                val options = BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }
                val bitmap = checkNotNull(archive.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, options) })
                val document = DrawingDocument(spec, bitmap, metadata.getString("id"), metadata.getString("layerId"))
                val viewport = metadata.optJSONObject("viewport")?.let {
                    ViewportState(it.getDouble("scale").toFloat(), it.getDouble("centerX").toFloat(),
                        it.getDouble("centerY").toFloat(), it.getBoolean("fit"), it.optDouble("rotationDegrees", 0.0).toFloat())
                }
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
        val metadata = JSONObject().put("version", 1).put("id", document.id).put("layerId", document.layerId)
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
                archive.putNextEntry(ZipEntry("layer.png"))
                check(document.layer.compress(Bitmap.CompressFormat.PNG, 100, archive)) { "Cannot save layer pixels" }
                archive.closeEntry()
            }
            target.finishWrite(output)
        } catch (error: Throwable) { target.failWrite(output); throw error }
    }
}
