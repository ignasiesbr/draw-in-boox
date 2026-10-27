package art.boox.probe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import kotlin.math.roundToInt

/** Decode only a canvas-sized image on the file worker. No live document pixels cross threads. */
object ImageImport {
    const val MAX_FILE_BYTES = 64L * 1024 * 1024
    private const val MAX_SOURCE_PIXELS = 100_000_000L
    data class Prepared(val bitmap: Bitmap, val name: String)

    fun read(context: Context, uri: Uri, spec: CanvasSpec): Prepared {
        CanvasSizes.validate(spec)
        val runtime = Runtime.getRuntime()
        val available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        // Decoder may return F16; reserve its pixels, the new ARGB layer and bounded history.
        val required = 3 * spec.bitmapBytes + minOf(32L * 1024 * 1024,
            spec.bitmapBytes + spec.pixels / 16) + 8L * 1024 * 1024
        check(required <= available) { "Not enough memory to import an image into this canvas." }
        val staged = File.createTempFile("image-import-", ".image", context.cacheDir)
        try {
            checkNotNull(context.contentResolver.openInputStream(uri)) { "Cannot read this image." }.use { input ->
                staged.outputStream().use { input.copyLimited(it, MAX_FILE_BYTES) }
            }
            val name = imageName(context, uri)
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(staged)) { decoder, info, _ ->
                require(info.mimeType == "image/jpeg" || info.mimeType == "image/png") { "Choose a JPG or PNG image." }
                val width = info.size.width
                val height = info.size.height
                require(width > 0 && height > 0 && width.toLong() * height <= MAX_SOURCE_PIXELS) {
                    "Image dimensions are too large (maximum 100 megapixels)."
                }
                val scale = minOf(spec.width.toDouble() / width, spec.height.toDouble() / height)
                decoder.setTargetSize((width * scale).roundToInt().coerceIn(1, spec.width),
                    (height * scale).roundToInt().coerceIn(1, spec.height))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                // Keep the default rejection of incomplete/corrupt image data.
            }
            return Prepared(bitmap, name)
        } finally { staged.delete() }
    }

    private fun imageName(context: Context, uri: Uri): String {
        val displayName = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) { null }
        return (displayName ?: uri.lastPathSegment.orEmpty()).substringAfterLast('/')
            .replace(Regex("(?i)\\.(jpe?g|png)$"), "").filterNot { it.isISOControl() }
            .trim().take(DrawingDocument.MAX_LAYER_NAME_LENGTH).ifBlank { "Imported image" }
    }
}
