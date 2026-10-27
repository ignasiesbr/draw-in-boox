package art.boox.probe

import android.graphics.Bitmap

/** Structural commands identify pixels needing backup; unchanged layers are never copied. */
sealed interface LayerCommand {
    val label: String
    fun affectedPixels(document: DrawingDocument): Set<String> = emptySet()
    fun apply(document: DrawingDocument)

    data object Add : LayerCommand {
        override val label = "Add layer"
        override fun apply(document: DrawingDocument) = document.addLayer()
    }
    data class ImportImage(val image: Bitmap, val name: String) : LayerCommand {
        override val label = "Import image"
        override fun apply(document: DrawingDocument) = document.importImage(image, name)
    }
    data class Duplicate(val id: String) : LayerCommand {
        override val label = "Duplicate layer"
        override fun apply(document: DrawingDocument) = document.duplicateLayer(id)
    }
    data class Delete(val id: String) : LayerCommand {
        override val label = "Delete layer"
        override fun affectedPixels(document: DrawingDocument) = setOf(id)
        override fun apply(document: DrawingDocument) = document.deleteLayer(id)
    }
    data class MergeDown(val id: String) : LayerCommand {
        override val label = "Merge layers"
        override fun affectedPixels(document: DrawingDocument): Set<String> {
            val index = document.layers.indexOfFirst { it.id == id }
            check(index > 0) { "No layer below to merge with" }
            return setOf(id, document.layers[index - 1].id)
        }
        override fun apply(document: DrawingDocument) = document.mergeDown(id)
    }
    data class Move(val id: String, val delta: Int) : LayerCommand {
        override val label = "Reorder layer"
        override fun apply(document: DrawingDocument) = document.moveLayer(id, delta)
    }
    data class Lock(val id: String, val locked: Boolean) : LayerCommand {
        override val label = if (locked) "Lock layer" else "Unlock layer"
        override fun apply(document: DrawingDocument) = document.setLayerLocked(id, locked)
    }
    data class Visibility(val id: String, val visible: Boolean) : LayerCommand {
        override val label = if (visible) "Show layer" else "Hide layer"
        override fun apply(document: DrawingDocument) = document.setLayerVisible(id, visible)
    }
    data class Properties(val id: String, val properties: LayerProperties) : LayerCommand {
        override val label = "Layer settings"
        override fun apply(document: DrawingDocument) = document.setLayerProperties(id, properties)
    }
    data class Opacity(val id: String, val percent: Int) : LayerCommand {
        override val label = "Layer opacity"
        override fun apply(document: DrawingDocument) = document.setLayerOpacity(id, percent)
    }
    data class Rename(val id: String, val name: String) : LayerCommand {
        override val label = "Rename layer"
        override fun apply(document: DrawingDocument) = document.renameLayer(id, name)
    }
}
