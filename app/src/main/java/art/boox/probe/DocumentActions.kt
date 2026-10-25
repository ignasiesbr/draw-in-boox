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
            .setItems(arrayOf("Save a copy…", "Open drawing…", "Export PNG")) { _, index ->
                when (index) { 0 -> save(); 1 -> confirmReplace("Open another drawing") { chooseOpen() }; 2 -> export() }
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

    private fun launch(intent: Intent, request: Int) {
        try { closeInput(); activity.startActivityForResult(intent, request) }
        catch (failure: Exception) { afterSave = null; message("Cannot open file picker: ${failure.message}"); resumeInput() }
    }

    fun onResult(request: Int, result: Int, data: Intent?): Boolean {
        if (request != SAVE && request != OPEN) return false
        val uri = data?.data
        if (result != Activity.RESULT_OK || uri == null) { pendingResult = null; afterSave = null; resumeInput(); return true }
        if (!surface.initializationComplete) {
            pendingResult = PendingResult(request, uri)
            closeInput()
            return true
        }
        if (!available()) { afterSave = null; return true }
        val current = surface.document
        if (request == SAVE && current == null) {
            afterSave = null
            message("Cannot save until a drawing is available. Open a drawing or create a canvas first.")
            return true
        }
        val continuation = afterSave
        afterSave = null
        closeInput()
        surface.inputEnabled = false
        busy = true
        progress = AlertDialog.Builder(activity)
            .setTitle(if (request == SAVE) "Saving drawing…" else "Opening drawing…")
            .setMessage("Please wait…").setCancelable(false).show()
        Thread({
            var replacement: DrawingDocument? = null
            var failure: Throwable? = null
            var temporary: File? = null
            try {
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
            } catch (error: Exception) { failure = error }
            catch (error: OutOfMemoryError) { failure = error }
            finally { temporary?.delete() }
            activity.runOnUiThread {
                busy = false
                if (destroyed) {
                    replacement?.recycle()
                    surface.dispose()
                } else {
                    progress?.dismiss(); progress = null
                    if (failure != null) {
                        replacement?.recycle()
                        Log.e("BooxProbe", "Drawing file operation failed", failure)
                        message(if (failure is OutOfMemoryError) "Not enough memory to open this drawing. Current artwork retained."
                            else "${if (request == SAVE) "Save" else "Open"} failed: ${failure?.message}. Current artwork retained.")
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

    private companion object { const val SAVE = 101; const val OPEN = 102 }
}
