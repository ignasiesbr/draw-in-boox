package art.boox.probe

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.widget.*

object BrushSettingsDialog {
    fun show(context: Context, initial: BrushSettings, sizeOnly: Boolean = false,
        apply: (BrushSettings) -> Boolean): AlertDialog {
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(12))
            tag = "brush.dialog"
            setBackgroundColor(Color.WHITE)
        }
        fun label(text: String) = TextView(context).apply {
            this.text = text; textSize = 16f; setTextColor(Color.BLACK); content.addView(this)
        }
        val maximum = initial.preset.maxSize
        val eraser = initial.preset == BrushPreset.ERASER
        val fountain = initial.preset.booxFamily != null
        val range = "1–${maximum.toInt()}"
        label("Size in document pixels ($range)")
        val size = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(initial.size.toString()); tag = "Brush size input"
            contentDescription = "Brush size in document pixels"
            content.addView(this)
        }
        val slider = SeekBar(context).apply {
            max = ((maximum - 1f) * 10).toInt(); progress = ((initial.size - 1f) * 10).toInt()
            progressDrawable = GradientDrawable().apply { setColor(Color.BLACK); setSize(dp(120), dp(4)) }
            thumb = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE)
                setStroke(dp(2), Color.BLACK); setSize(dp(24), dp(24)) }
            contentDescription = "Brush size"; minimumHeight = dp(48); content.addView(this)
        }
        val pressure = CheckBox(context).apply {
            text = "Pressure changes width"; isChecked = initial.pressure; minHeight = dp(48)
            tag = "Brush pressure"; setTextColor(Color.BLACK)
            if (!sizeOnly && !eraser && !fountain) content.addView(this)
        }
        val tilt = CheckBox(context).apply {
            text = "Tilt widens stroke (up to 2.5×)"; isChecked = initial.tilt; minHeight = dp(48)
            tag = "Brush tilt"; setTextColor(Color.BLACK)
            if (!sizeOnly && !eraser && !fountain) content.addView(this)
        }
        val shade = if (sizeOnly || eraser) null else InkShadePicker(context, initial.shade).also { content.addView(it) }
        val explanation = label("")
        fun draft(): BrushSettings? = size.text.toString().toFloatOrNull()?.takeIf {
            it.isFinite() && it in 1f..maximum
        }?.let { initial.copy(size = it, pressure = pressure.isChecked, tilt = tilt.isChecked,
            shade = shade?.selected ?: initial.shade) }
        val preview = object : View(context) {
            private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = dp(14).toFloat() }
            override fun onDraw(canvas: Canvas) {
                canvas.drawColor(Color.WHITE)
                val settings = draft() ?: return
                val brush = settings.compile()
                val eraserLayer = if (eraser) canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null) else null
                // Erasing reveals paper in this sample. Never CLEAR the dialog canvas.
                if (eraser) {
                    val background = Paint().apply { color = Color.DKGRAY }
                    canvas.drawRect(0f, 0f, width.toFloat(), (height - dp(28)).toFloat(), background)
                }
                val sampleHeight = (height - dp(28)).coerceAtLeast(1).toFloat()
                val amplitude = minOf(dp(12).toFloat(), sampleHeight / 6f)
                val scale = minOf(1f, (sampleHeight - amplitude * 2 - dp(4)).coerceAtLeast(1f) / brush.maxRenderedWidth)
                val margin = maxOf(dp(12).toFloat(), brush.maxRenderedWidth * scale / 2f + dp(4))
                val saved = canvas.save()
                canvas.scale(scale, scale)
                val samples = (0..30).map { i -> PenSample(margin + i * (width - margin * 2).coerceAtLeast(1f) / 30f,
                    sampleHeight / 2f + kotlin.math.sin(i * 0.21f) * amplitude, i / 30f, i.toLong(),
                    tiltRadians = Math.toRadians(40.0).toFloat()).let { it.copy(x = it.x / scale, y = it.y / scale) } }
                brush.draw(canvas, samples)
                canvas.restoreToCount(saved)
                eraserLayer?.let { canvas.restoreToCount(it) }
                canvas.drawText("Stroke sample · ${(scale * 100).toInt()}% size", dp(4).toFloat(), height - dp(4).toFloat(), textPaint)
            }
        }
        content.addView(preview, LinearLayout.LayoutParams(-1, dp(100)))
        fun update() {
            val settings = draft()
            explanation.text = settings?.let {
                val brush = it.compile()
                if (eraser) "Erases the selected layer. Alpha lock must be off. Native preview: up to 30px at this zoom on one full-opacity layer."
                else if (fountain) "Experimental BOOX ${initial.preset.booxFamily!!.label} response. Compare stroke shape before and after lifting."
                else "Width ${brush.minWidth}–${brush.maxWidth} px; maximum with tilt ${brush.maxRenderedWidth} px"
            } ?: "Enter a size from $range."
            preview.invalidate()
        }
        size.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                draft()?.let { slider.progress = ((it.size - 1f) * 10).toInt() }
                update()
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) size.setText(String.format(java.util.Locale.US, "%.1f", 1f + progress / 10f))
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        pressure.setOnCheckedChangeListener { _, _ -> update() }
        tilt.setOnCheckedChangeListener { _, _ -> update() }
        shade?.onChanged = { update() }
        update()
        val dialog = AlertDialog.Builder(context).setTitle("${initial.preset.label} ${if (sizeOnly) "size" else "settings"}")
            .setView(ScrollView(context).apply { addView(content) })
            .setNegativeButton("Cancel", null).setPositiveButton("Apply", null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val settings = draft()
            if (settings == null) size.error = "Enter a finite size from $range"
            else if (apply(settings)) dialog.dismiss()
        }
        return dialog
    }
}
