package art.boox.probe

import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.*
import java.util.Locale

/** Functional size selection; final toolbar/dialog design is intentionally deferred. */
object CanvasSizeDialog {
    fun show(activity: Activity, create: (CanvasSpec) -> Unit): AlertDialog {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
        }
        val choices = CanvasSizes.presets.map { "${it.name} — ${it.spec.width} × ${it.spec.height}" } + "Custom"
        val preset = Spinner(activity).apply {
            tag = "canvas-preset"
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, choices)
        }
        layout.addView(preset)
        fun dimension(label: String): EditText {
            layout.addView(TextView(activity).apply { text = label; textSize = 16f })
            return EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(InputFilter.LengthFilter(5))
                setSelectAllOnFocus(true)
                layout.addView(this)
            }
        }
        val width = dimension("Width (pixels)")
        val height = dimension("Height (pixels)")
        width.tag = "canvas-width"; height.tag = "canvas-height"
        val swap = Button(activity).apply { text = "Swap portrait / landscape"; isAllCaps = false }
        layout.addView(swap)
        val estimate = TextView(activity).apply { textSize = 16f }
        layout.addView(estimate)
        val dialog = AlertDialog.Builder(activity).setTitle("New canvas").setView(layout)
            .setNegativeButton("Cancel", null).setPositiveButton("Continue", null).create()
        fun spec(): CanvasSpec? {
            val w = width.text.toString().toIntOrNull() ?: return null
            val h = height.text.toString().toIntOrNull() ?: return null
            val selected = CanvasSizes.presets.getOrNull(preset.selectedItemPosition)?.spec
            val ppi = selected?.takeIf { (it.width == w && it.height == h) || (it.width == h && it.height == w) }?.ppi
            return CanvasSpec(w, h, ppi)
        }
        fun update() {
            val selected = spec()
            val error = if (selected == null) "Enter width and height." else CanvasSizes.error(selected)
            val memory = selected?.let {
                String.format(Locale.US, "Bitmap %.1f MiB · estimated working memory %.1f MiB",
                    it.bitmapBytes / 1048576.0, it.bitmapBytes * 5 / 1048576.0)
            } ?: ""
            estimate.text = if (error != null) error else memory + (selected?.ppi?.let { "\nPrint reference: $it ppi" } ?: "")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = error == null
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = update()
            override fun afterTextChanged(s: Editable?) = Unit
        }
        width.addTextChangedListener(watcher); height.addTextChangedListener(watcher)
        preset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                CanvasSizes.presets.getOrNull(position)?.spec?.let {
                    width.setText(it.width.toString()); height.setText(it.height.toString())
                }
                update()
            }
        }
        swap.setOnClickListener {
            val old = width.text.toString()
            width.setText(height.text.toString()); height.setText(old)
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = spec() ?: return@setOnClickListener
                if (CanvasSizes.error(selected) == null) { dialog.dismiss(); create(selected) }
            }
            update()
        }
        dialog.show()
        return dialog
    }
}
