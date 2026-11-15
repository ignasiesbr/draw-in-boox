package art.boox.probe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FilterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32

/** Portable artwork and optional pen settings, independent of view state, history and renderer versions.
 * See docs/SAVE_FORMAT.md before extending the manifest. Callers freeze edits while writing.
 */
object DocumentFile {
    const val MIME_TYPE = "application/vnd.boox-art.document"
    const val MAX_FILE_BYTES = 512L * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 1024 * 1024
    private val supportedFeatures = setOf("raster-layers-v1")
    private val identifier = Regex("[A-Za-z0-9_-]{1,128}")

    fun write(file: File, document: DrawingDocument) {
        val manifest = JSONObject().put("format", "art.boox.document")
            .put("version", JSONObject().put("major", 1).put("minor", 1))
            .put("requiredFeatures", JSONArray().put("raster-layers-v1"))
            .put("id", document.id).put("width", document.spec.width).put("height", document.spec.height)
            .put("selectedLayerId", document.selectedLayerId)
            .put("layers", JSONArray().apply {
                document.layers.forEach { layer ->
                    require(identifier.matches(layer.id)) { "Invalid layer ID" }
                    put(JSONObject().put("id", layer.id).put("type", "raster")
                        .put("name", layer.name).put("visible", layer.visible).put("locked", layer.locked)
                        .put("opacity", layer.opacity).put("alphaLocked", layer.alphaLocked)
                        .put("pixels", "layers/${layer.id}.png"))
                }
            })
        document.spec.ppi?.let { manifest.put("ppi", it) }
        document.penSettings?.let { manifest.put("penSettings", it.toJson()) }
        val target = AtomicFile(file)
        val output = target.startWrite()
        try {
            val nonClosing = object : FilterOutputStream(output) {
                override fun close() = flush()
                override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
            }
            ZipOutputStream(nonClosing).use { zip ->
                // PNG already compresses its pixels; avoid a second expensive compression pass.
                zip.setLevel(0)
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString().toByteArray(Charsets.UTF_8)); zip.closeEntry()
                document.layers.forEach { layer ->
                    zip.putNextEntry(ZipEntry("layers/${layer.id}.png"))
                    check(layer.bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)) { "Cannot save layer pixels" }
                    zip.closeEntry()
                }
            }
            require(output.channel.size() <= MAX_FILE_BYTES) { "Drawing file is too large" }
            target.finishWrite(output)
        } catch (error: Throwable) { target.failWrite(output); throw error }
    }

    fun read(file: File): DrawingDocument {
        require(file.length() in 1..MAX_FILE_BYTES) { "Drawing file is empty or too large" }
        return ZipFile(file).use { zip ->
            val names = HashSet<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                require(names.size < 1024 && names.add(entries.nextElement().name)) {
                    "Invalid or duplicate archive entries"
                }
            }
            val entry = requireNotNull(zip.getEntry("manifest.json")) { "This is not a Draw in Boox drawing" }
            require(entry.size in 1..MAX_MANIFEST_BYTES.toLong()) { "Invalid drawing manifest size" }
            val bytes = zip.getInputStream(entry).use { it.readBytesLimited(MAX_MANIFEST_BYTES.toLong()) }
            require(CRC32().apply { update(bytes) }.value == entry.crc) { "Drawing manifest checksum failed" }
            val manifest = JSONObject(bytes.toString(Charsets.UTF_8))
            require(manifest.getString("format") == "art.boox.document") { "Unknown drawing format" }
            val version = manifest.getJSONObject("version")
            require(integer(version, "major") == 1) { "This drawing needs a newer version of Draw in Boox" }
            require(integer(version, "minor") >= 0) { "Invalid drawing version" }
            val features = if (manifest.has("requiredFeatures")) manifest.getJSONArray("requiredFeatures") else JSONArray()
            for (i in 0 until features.length()) require(features.getString(i) in supportedFeatures) {
                "This drawing needs an unsupported feature: ${features.getString(i)}"
            }
            val spec = CanvasSpec(integer(manifest, "width"), integer(manifest, "height"),
                if (manifest.has("ppi")) integer(manifest, "ppi").also { require(it > 0) { "Invalid PPI" } } else null)
            CanvasSizes.validate(spec)
            val documentId = manifest.getString("id")
            require(identifier.matches(documentId)) { "Invalid document ID" }
            val items = manifest.getJSONArray("layers")
            require(items.length() in 1..LayerLimits.maxLayers(spec)) {
                "This device does not have enough memory for all layers in this drawing"
            }
            data class Item(val info: LayerInfo, val pixels: ZipEntry)
            val parsed = (0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                val id = item.getString("id")
                require(identifier.matches(id)) { "Invalid layer ID" }
                require(item.optString("type", "raster") == "raster") { "Unsupported layer type" }
                val name = item.optString("name", "Layer ${index + 1}")
                require(name.length <= 4096) { "Layer name is too long" }
                val opacity = if (item.has("opacity")) integer(item, "opacity") else 100
                require(opacity in 0..100) { "Invalid layer opacity" }
                val path = item.getString("pixels")
                // No extraction or arbitrary file paths; IDs bind pixels to exactly one layer.
                require(path == "layers/$id.png") { "Invalid layer pixel path" }
                val pixels = requireNotNull(zip.getEntry(path)) { "Layer pixels are missing" }
                require(pixels.size in 1..(spec.bitmapBytes * 2 + MAX_MANIFEST_BYTES)) { "Invalid layer pixel size" }
                Item(LayerInfo(id, name, boolean(item, "locked", false), boolean(item, "visible", true),
                    opacity, boolean(item, "alphaLocked", false)), pixels)
            }
            val ids = parsed.map { it.info.id }
            require(ids.distinct().size == ids.size) { "Duplicate layer IDs" }
            val selected = manifest.optString("selectedLayerId", ids.first())
            require(selected in ids) { "Selected layer is missing" }
            // Validate every image header before allocating any layer bitmap.
            parsed.forEach { item ->
                val crc = CRC32()
                zip.getInputStream(item.pixels).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var count = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                        require(count <= item.pixels.size) { "Invalid layer pixel size" }
                        crc.update(buffer, 0, read)
                    }
                    require(count == item.pixels.size && crc.value == item.pixels.crc) { "Layer pixel checksum failed" }
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                zip.getInputStream(item.pixels).use { BitmapFactory.decodeStream(it, null, bounds) }
                require(bounds.outMimeType == "image/png" && bounds.outWidth == spec.width && bounds.outHeight == spec.height) {
                    "Layer pixels do not match document dimensions or PNG format"
                }
            }
            val available = Runtime.getRuntime().let { it.maxMemory() - it.totalMemory() + it.freeMemory() }
            require(available > spec.bitmapBytes * (parsed.size + 1) + 8L * 1024 * 1024) {
                "Not enough free memory to open this drawing. Try a smaller canvas first."
            }
            val layers = mutableListOf<RasterLayer>()
            try {
                parsed.forEach { item ->
                    val options = BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false }
                    val bitmap = requireNotNull(zip.getInputStream(item.pixels).use { BitmapFactory.decodeStream(it, null, options) }) {
                        "Cannot decode layer pixels"
                    }
                    val info = item.info
                    layers += RasterLayer(bitmap, info.id, info.name, info.locked, info.visible, info.opacity, info.alphaLocked)
                }
                DrawingDocument(spec, layers, documentId, selected).apply {
                    penSettings = PenSettings.fromJson(manifest.optJSONObject("penSettings"))
                }
            } catch (error: Throwable) { layers.forEach { it.bitmap.recycle() }; throw error }
        }
    }

    private fun integer(json: JSONObject, key: String): Int {
        val value = json.get(key)
        require(value is Number && value.toDouble().isFinite() && value.toDouble() % 1.0 == 0.0 &&
            value.toDouble() in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) { "Invalid $key" }
        return value.toInt()
    }

    private fun boolean(json: JSONObject, key: String, default: Boolean): Boolean {
        if (!json.has(key)) return default
        return (json.get(key) as? Boolean) ?: throw IllegalArgumentException("Invalid $key")
    }
}

/** Limits provider input before writing it to disk, including streams with no known length. */
internal fun java.io.InputStream.copyLimited(output: java.io.OutputStream, limit: Long) {
    val buffer = ByteArray(64 * 1024)
    var count = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        count += read
        require(count <= limit) { "Drawing file is too large" }
        output.write(buffer, 0, read)
    }
}

private fun java.io.InputStream.readBytesLimited(limit: Long): ByteArray =
    java.io.ByteArrayOutputStream().also { copyLimited(it, limit) }.toByteArray()
