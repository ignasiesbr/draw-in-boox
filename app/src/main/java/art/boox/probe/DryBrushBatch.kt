package art.boox.probe

import android.graphics.*
import kotlin.math.cos
import kotlin.math.sin

/** Bounded rasterizer: one textured triangle submission per 128 dabs. */
internal class DryBrushBatch(private val family: BooxBrushFamily) {
    private val vertices = FloatArray(128 * 12)
    private val texture = FloatArray(vertices.size)
    private val colors = IntArray(128 * 6)
    private val corners = FloatArray(8)
    private val order = intArrayOf(0, 1, 2, 2, 3, 0)
    private val shader by lazy { BitmapShader(DryBrushTips.atlas(family), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }

    fun draw(canvas: Canvas, paint: Paint, data: FloatArray) {
        val originalShader = paint.shader; val originalAlpha = paint.alpha
        val originalFilter = paint.isFilterBitmap
        paint.shader = shader; paint.alpha = 255; paint.isFilterBitmap = true
        var used = 0
        fun flush() {
            if (used == 0) return
            canvas.drawVertices(Canvas.VertexMode.TRIANGLES, used * 12, vertices, 0, texture, 0,
                colors, 0, null, 0, 0, paint)
            used = 0
        }
        try {
            for (i in data.indices step 7) {
                val angle = Math.toRadians(data[i + 4].toDouble())
                val c = cos(angle).toFloat(); val s = sin(angle).toFloat()
                val dx = c * data[i + 2] / 2f; val dy = s * data[i + 2] / 2f
                val ux = -s * data[i + 3] / 2f; val uy = c * data[i + 3] / 2f
                val x = data[i]; val y = data[i + 1]
                corners[0] = x - dx - ux; corners[1] = y - dy - uy
                corners[2] = x + dx - ux; corners[3] = y + dy - uy
                corners[4] = x + dx + ux; corners[5] = y + dy + uy
                corners[6] = x - dx + ux; corners[7] = y - dy + uy
                val left = data[i + 6].toInt() * 68 + 2f
                val color = Color.argb((data[i + 5] * originalAlpha).toInt().coerceIn(0, 255), 255, 255, 255)
                for (v in 0 until 6) {
                    val corner = order[v]; val destination = used * 12 + v * 2
                    vertices[destination] = corners[corner * 2]; vertices[destination + 1] = corners[corner * 2 + 1]
                    texture[destination] = left + if (corner == 1 || corner == 2) 64f else 0f
                    texture[destination + 1] = 2f + if (corner >= 2) 64f else 0f
                    colors[used * 6 + v] = color
                }
                used++
                if (used == 128) flush()
            }
            flush()
        } finally { paint.shader = originalShader; paint.alpha = originalAlpha; paint.isFilterBitmap = originalFilter }
    }
}
