package art.boox.probe

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.InputFilter
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*

/** UI owns no pixels. Icons reflect document state; thumbnails scale existing bitmaps. */
// Native widgets match the Activity framework theme; no AppCompat tinting is needed.
@android.annotation.SuppressLint("AppCompatCustomView")
class LayerPanel(context: Context, initiallyExpanded: Boolean,
    private val toggle: (Boolean) -> Boolean,
    private val select: (String) -> Unit,
    private val command: (LayerCommand) -> Unit
) : LinearLayout(context) {
    private val expanded = LinearLayout(context).apply { orientation = VERTICAL }
    private val rows = LinearLayout(context).apply { orientation = VERTICAL }
    private val thumbnails = mutableListOf<View>()
    private val buttons = mutableMapOf<String, EinkIconButton>()
    private val count = TextView(context).apply { textSize = 14f; setTextColor(Color.BLACK) }
    private var document: DrawingDocument? = null
    private var open = initiallyExpanded
    private val show = EinkIconButton(context, UiIcon.LAYERS, "Show layers").apply {
        tag = "Show layers"; setOnClickListener { setExpanded(true) }
    }

    init {
        tag = "Layer panel"; orientation = VERTICAL; setBackgroundColor(Color.WHITE)
        addView(show, LayoutParams(dp(52), dp(60)))
        addView(expanded, LayoutParams(-1, -1))
        val header = LinearLayout(context)
        header.addView(TextView(context).apply {
            text = "Layers"; textSize = 20f; setTextColor(Color.BLACK)
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, 0, 0)
        }, LayoutParams(0, dp(52), 1f))
        header.addView(EinkIconButton(context, UiIcon.HIDE, "Hide layers").apply {
            tag = "Hide layers"; setOnClickListener { setExpanded(false) }
        }, LayoutParams(dp(52), dp(52)))
        expanded.addView(header)
        count.setPadding(dp(8), 0, dp(8), dp(4)); expanded.addView(count)
        val actions = LinearLayout(context)
        fun action(label: String, icon: UiIcon, factory: (DrawingDocument) -> LayerCommand) {
            val button = EinkIconButton(context, icon, label).apply {
                tag = "Layer $label"
                setOnClickListener { document?.let { command(factory(it)) } }
            }
            buttons[label] = button; actions.addView(button, LayoutParams(0, dp(48), 1f))
        }
        action("Add", UiIcon.ADD) { LayerCommand.Add }
        action("Duplicate", UiIcon.DUPLICATE) { LayerCommand.Duplicate(it.layerId) }
        action("Merge down", UiIcon.MERGE_DOWN) { LayerCommand.MergeDown(it.layerId) }
        action("Delete", UiIcon.DELETE) { LayerCommand.Delete(it.layerId) }
        expanded.addView(actions)
        expanded.addView(ScrollView(context).apply { addView(rows) }, LayoutParams(-1, 0, 1f))
        updateExpansion()
    }

    val panelWidth: Int get() = dp(if (open) 220 else 52)
    val isExpanded: Boolean get() = open
    fun setExpanded(value: Boolean): Boolean {
        if (value == open) return true
        if (!toggle(value)) return false
        open = value; updateExpansion()
        layoutParams?.let { it.width = panelWidth; layoutParams = it }
        return true
    }
    private fun updateExpansion() {
        expanded.visibility = if (open) VISIBLE else GONE
        show.visibility = if (open) GONE else VISIBLE
    }
    fun invalidateThumbnails() { thumbnails.forEach { it.invalidate() } }

    fun render(value: DrawingDocument?) {
        document = value; rows.removeAllViews(); thumbnails.clear()
        buttons.values.forEach { it.isEnabled = false }
        if (value == null) { count.text = "No canvas"; return }
        count.text = "${value.layers.size} / ${value.maxLayers} layers"
        val selected = value.activeLayer
        val index = value.layers.indexOf(selected)
        buttons["Add"]?.isEnabled = value.layers.size < value.maxLayers
        buttons["Duplicate"]?.isEnabled = value.layers.size < value.maxLayers
        buttons["Delete"]?.isEnabled = value.layers.size > 1 && !selected.locked
        buttons["Merge down"]?.isEnabled = index > 0 && !selected.locked && selected.visible &&
            !value.layers[index - 1].locked && value.layers[index - 1].visible
        value.layers.asReversed().forEach { layer ->
            val item = LinearLayout(context).apply {
                orientation = VERTICAL; isSelected = layer.id == value.layerId
                tag = "Layer row ${layer.id}"
                setPadding(dp(4), dp(4), dp(4), dp(4))
                background = EinkUi.selectionBackground(context, isSelected)
            }
            val top = LinearLayout(context)
            val thumbnail = object : Thumbnail(context, layer) {
                override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(info)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_LONG_CLICK, "Layer settings"))
                }
            }.apply { tag = "Layer thumbnail ${layer.id}" }
            thumbnails += thumbnail
            top.addView(thumbnail, LayoutParams(dp(56), dp(56)))
            top.addView(object : TextView(context) {
                override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(info)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_LONG_CLICK, "Rename layer"))
                }
            }.apply {
                text = layer.name; tag = "Select layer ${layer.id}"; textSize = 15f
                setTextColor(Color.BLACK); gravity = Gravity.CENTER_VERTICAL
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(6), 0, dp(3), 0); isFocusable = true
                tooltipText = "Long press to rename ${layer.name}"
                setOnClickListener { select(layer.id) }
                setOnLongClickListener { rename(layer); true }
            }, LayoutParams(0, dp(56), 1f))
            thumbnail.contentDescription = "Select ${layer.name}, opacity ${layer.opacity} percent"
            thumbnail.setOnClickListener { select(layer.id) }
            thumbnail.setOnLongClickListener {
                LayerSettingsDialog.show(context, layer) { command(LayerCommand.Properties(layer.id, it)) }
                true
            }
            item.addView(top)
            val controls = LinearLayout(context)
            fun control(icon: UiIcon, label: String, tagValue: String, checked: Boolean? = null,
                        enabled: Boolean = true, action: () -> Unit) {
                controls.addView(EinkIconButton(context, icon, label, checked != null).apply {
                    tag = tagValue; if (checked != null) isChecked = checked
                    isEnabled = enabled; setOnClickListener { action() }
                }, LayoutParams(0, dp(48), 1f))
            }
            control(if (layer.visible) UiIcon.VISIBLE else UiIcon.HIDDEN, "Visibility: ${layer.name}",
                "Visibility ${layer.id}", layer.visible) { command(LayerCommand.Visibility(layer.id, !layer.visible)) }
            control(if (layer.locked) UiIcon.LOCK else UiIcon.UNLOCK, "Lock: ${layer.name}",
                "Lock ${layer.id}", layer.locked) { command(LayerCommand.Lock(layer.id, !layer.locked)) }
            val position = value.layers.indexOf(layer)
            control(UiIcon.UP, "Move ${layer.name} up", "Layer up ${layer.id}",
                enabled = position < value.layers.lastIndex) { command(LayerCommand.Move(layer.id, 1)) }
            control(UiIcon.DOWN, "Move ${layer.name} down", "Layer down ${layer.id}",
                enabled = position > 0) { command(LayerCommand.Move(layer.id, -1)) }
            item.addView(controls)
            rows.addView(item, LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        }
    }

    private fun rename(layer: RasterLayer) {
        val input = EditText(context).apply {
            tag = "Rename layer name"; setSingleLine(); setText(layer.name); selectAll()
            hint = "Layer name"; contentDescription = "Layer name"
            filters = arrayOf(InputFilter.LengthFilter(DrawingDocument.MAX_LAYER_NAME_LENGTH))
        }
        val container = FrameLayout(context).apply {
            setPadding(dp(20), dp(8), dp(20), 0); addView(input)
        }
        val dialog = AlertDialog.Builder(context).setTitle("Rename layer").setView(container)
            .setNegativeButton("Cancel", null).setPositiveButton("Rename", null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = input.text.toString().trim()
            if (name.isBlank() || name.any { Character.isISOControl(it) }) {
                input.error = "Enter a non-empty, single-line name"
            } else {
                command(LayerCommand.Rename(layer.id, name)); dialog.dismiss()
            }
        }
        input.requestFocus()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private open class Thumbnail(context: Context, private val layer: RasterLayer) : View(context) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val rect = RectF()
        override fun onDraw(canvas: Canvas) {
            if (layer.bitmap.isRecycled) return
            canvas.drawColor(Color.WHITE)
            val size = 8f * resources.displayMetrics.density
            paint.color = 0xffdddddd.toInt()
            var y = 0f
            while (y < height) {
                var x = 0f
                while (x < width) {
                    if (((x / size).toInt() + (y / size).toInt()) % 2 == 0) canvas.drawRect(x, y, x + size, y + size, paint)
                    x += size
                }
                y += size
            }
            val scale = minOf(width.toFloat() / layer.bitmap.width, height.toFloat() / layer.bitmap.height)
            val w = layer.bitmap.width * scale; val h = layer.bitmap.height * scale
            rect.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
            paint.color = Color.BLACK
            paint.alpha = (layer.opacity * 255 + 50) / 100
            canvas.drawBitmap(layer.bitmap, null, rect, paint)
            paint.alpha = 255
        }
    }

}
