package art.boox.probe

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import java.io.File

/** Explicit file operations freeze the editor instead of copying every layer into another snapshot.
 * No serialization is scheduled by stroke commits. Only this worker reads the frozen artwork.
 * Image decode returns independent pixels; layer/history changes remain on UI.
 */
@Suppress("DEPRECATION")
class DocumentActions(
    private val activity: Activity,
    private val surface: RasterSurface,
    private val inputBusy: () -> Boolean,
    private val closeInput: () -> Unit,
    private val resumeInput: () -> Unit
) {
    var busy = false
        private set
    private var destroyed = false
    private var progress: AlertDialog? = null
    private var afterSave: (() -> Unit)? = null
    private data class PendingResult(val request: Int, val uri: Uri)
    private var pendingResult: PendingResult? = null

    /** Picker results can precede the first SurfaceHolder callback after Activity recreation. */
    fun documentReady() {
        val pending = pendingResult ?: return
        if (!surface.initializationComplete || destroyed) return
        pendingResult = null
        onResult(pending.request, Activity.RESULT_OK, Intent().setData(pending.uri))
    }

    private fun available(): Boolean {
        if (!busy && !surface.isDrawing && !surface.isNavigating && !inputBusy()) return true
        message("Finish drawing or navigation before using files.")
        return false
    }

    fun menu(export: () -> Unit) {
        if (!available()) return
        AlertDialog.Builder(activity).setTitle("Drawing files")
            .setItems(arrayOf("Save a copy…", "Open drawing…", "Import image…", "Export PNG")) { _, index ->
                when (index) {
                    0 -> save()
                    1 -> confirmReplace("Open another drawing") { chooseOpen() }
                    2 -> chooseImage()
                    3 -> export()
                }
            }.show()
    }

    fun confirmReplace(title: String, replace: () -> Unit) {
        if (!available()) return
        AlertDialog.Builder(activity).setTitle(title)
            .setMessage("Save an editable copy of the current drawing before replacing it?")
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Discard & continue") { _, _ -> replace() }
            .setPositiveButton("Save & continue") { _, _ -> save(replace) }.show()
    }

    private fun save(then: (() -> Unit)? = null) {
        if (!available() || surface.document == null) return
        afterSave = then
        launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = DocumentFile.MIME_TYPE
            putExtra(Intent.EXTRA_TITLE, "drawing-${System.currentTimeMillis()}.boox")
        }, SAVE)
    }

    private fun chooseOpen() {
        if (!available()) return
        launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // Providers may identify .boox as ZIP or octet-stream; don't hide either.
            type = "*/*"
        }, OPEN)
    }

    private fun chooseImage() {
        if (!available()) return
        val current = surface.document
        if (current == null) { message("Create a canvas before importing an image."); return }
        if (current.layers.size >= current.maxLayers) {
            message("Layer memory limit reached (${current.maxLayers} for this canvas).")
            return
        }
        launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/png"))
        }, IMPORT_IMAGE)
    }

    private fun launch(intent: Intent, request: Int) {
        try { closeInput(); activity.startActivityForResult(intent, request) }
        catch (failure: Exception) { afterSave = null; message("Cannot open file picker: ${failure.message}"); resumeInput() }
    }

    fun onResult(request: Int, result: Int, data: Intent?): Boolean {
        if (request != SAVE && request != OPEN && request != IMPORT_IMAGE) return false
        val uri = data?.data
        if (result != Activity.RESULT_OK || uri == null) { pendingResult = null; afterSave = null; resumeInput(); return true }
        if (!surface.initializationComplete) {
            pendingResult = PendingResult(request, uri)
            closeInput()
            return true
        }
        if (!available()) { afterSave = null; return true }
        val current = surface.document
        if ((request == SAVE || request == IMPORT_IMAGE) && current == null) {
            afterSave = null
            message("Open a drawing or create a canvas before ${if (request == SAVE) "saving" else "importing an image"}.")
            resumeInput()
            return true
        }
        if (request == IMPORT_IMAGE && checkNotNull(current).layers.size >= current.maxLayers) {
            message("Layer memory limit reached (${current.maxLayers} for this canvas).")
            resumeInput()
            return true
        }
        val continuation = afterSave
        afterSave = null
        closeInput()
        surface.inputEnabled = false
        busy = true
        progress = AlertDialog.Builder(activity)
            .setTitle(when (request) { SAVE -> "Saving drawing…"; IMPORT_IMAGE -> "Importing image…"; else -> "Opening drawing…" })
            .setMessage("Please wait…").setCancelable(false).show()
        Thread({
            var replacement: DrawingDocument? = null
            var image: ImageImport.Prepared? = null
            var failure: Throwable? = null
            var temporary: File? = null
            try {
                if (request == IMPORT_IMAGE) {
                    image = ImageImport.read(activity, uri, checkNotNull(current).spec)
                } else {
                    val staged = File.createTempFile("drawing-", ".boox", activity.cacheDir).also { temporary = it }
                    if (request == SAVE) {
                        DocumentFile.write(staged, checkNotNull(current))
                        try {
                            checkNotNull(activity.contentResolver.openOutputStream(uri, "w")) { "Cannot write this location" }.use { output ->
                                staged.inputStream().use { it.copyTo(output) }
                            }
                        } catch (error: Throwable) {
                            // ACTION_CREATE_DOCUMENT created a new file; remove only that incomplete copy.
                            try { DocumentsContract.deleteDocument(activity.contentResolver, uri) } catch (_: Exception) { }
                            throw error
                        }
                    } else {
                        checkNotNull(activity.contentResolver.openInputStream(uri)) { "Cannot read this file" }.use { input ->
                            staged.outputStream().use { input.copyLimited(it, DocumentFile.MAX_FILE_BYTES) }
                        }
                        replacement = DocumentFile.read(staged)
                        // Atomic checkpoint succeeds before ownership changes. Failed imports retain the old document.
                        surface.checkpointReplacement(checkNotNull(replacement))
                    }
                }
            } catch (error: Exception) { failure = error }
            catch (error: OutOfMemoryError) { failure = error }
            finally { temporary?.delete() }
            activity.runOnUiThread {
                busy = false
                if (destroyed) {
                    image?.bitmap?.recycle()
                    replacement?.recycle()
                    surface.dispose()
                } else {
                    progress?.dismiss(); progress = null
                    if (failure != null) {
                        replacement?.recycle()
                        Log.e("BooxProbe", "Drawing file operation failed", failure)
                        message(if (failure is OutOfMemoryError) "Not enough memory for this file operation. Current artwork retained."
                            else "${when (request) { SAVE -> "Save"; IMPORT_IMAGE -> "Image import"; else -> "Open" }} failed: ${failure?.message}. Current artwork retained.")
                    } else if (request == IMPORT_IMAGE) {
                        try {
                            check(surface.document === current) { "The canvas changed while importing." }
                            val prepared = checkNotNull(image)
                            check(surface.applyLayerCommand(LayerCommand.ImportImage(prepared.bitmap, prepared.name))) {
                                "Finish drawing or navigation before importing an image."
                            }
                            // Explicit import only; never schedule file writes from pen-up.
                            if (surface.checkpoint()) message("Image imported. Long-press its layer thumbnail to change opacity.")
                        } catch (error: Exception) {
                            Log.e("BooxProbe", "Image layer import failed", error)
                            message("Image import failed: ${error.message}")
                        } catch (error: OutOfMemoryError) {
                            Log.e("BooxProbe", "Image layer import ran out of memory", error)
                            message("Not enough memory to import this image.")
                        } finally { image?.bitmap?.recycle() }
                    } else if (request == OPEN) {
                        surface.installDocument(checkNotNull(replacement))
                        message("Drawing opened")
                    } else {
                        message("Editable drawing saved")
                        continuation?.invoke()
                    }
                    resumeInput()
                }
            }
        }, "BooxDocumentIO").start()
        return true
    }

    /** Keep frozen bitmaps alive until a running writer finishes, even if the Activity closes. */
    fun destroy() {
        destroyed = true
        pendingResult = null
        progress?.dismiss(); progress = null
        if (!busy) surface.dispose()
    }

    private fun message(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    private companion object { const val SAVE = 101; const val OPEN = 102; const val IMPORT_IMAGE = 103 }
}
