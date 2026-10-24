package art.boox.probe

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color

object NativePreviewCalibrationChecks {
    fun run(context: Context) {
        val name = "preview-fit-${System.nanoTime()}"
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(key: String, mode: Int): SharedPreferences = context.getSharedPreferences(name, mode)
        }
        val prefs = isolated.getSharedPreferences("", 0)
        try {
            prefs.edit().putInt("brush_schema", 2).putFloat("brush_lab_pencil_size", 13.3f)
                .putBoolean("brush_lab_pencil_pressure", true).putBoolean("brush_lab_pencil_tilt", true).commit()
            val store = BrushSettingsStore(isolated)
            val migrated = store.load(BrushPreset.LAB_PENCIL)
            check(migrated.size == 13.3f && migrated.nativePreviewScale == 0.5f)
            val adjusted = migrated.copy(nativePreviewScale = 0.35f, nativePreviewTexture = NativePreviewTexture.COARSE)
            store.save(adjusted)
            check(BrushSettingsStore(isolated).load(BrushPreset.LAB_PENCIL) == adjusted)
            check(store.load(BrushPreset.LAB_CHARCOAL).nativePreviewScale == 0.5f)
            prefs.edit().putFloat("brush_lab_pencil_native_scale", Float.NaN)
                .putString("brush_lab_pencil_native_texture", "future").commit()
            check(store.load(BrushPreset.LAB_PENCIL).nativePreviewScale == 0.5f)
            check(store.load(BrushPreset.LAB_PENCIL).nativePreviewTexture == NativePreviewTexture.FINE)
        } finally { context.deleteSharedPreferences(name) }

        for (preset in listOf(BrushPreset.LAB_PENCIL, BrushPreset.LAB_CHARCOAL)) {
            val settings = preset.defaults().copy(size = 13.3f)
            val brush = settings.compile()
            check(brush.maxRenderedWidth > 30f && brush.nativePreviewMaxWidth < 30f)
            check(brush.nativePreviewBaseWidth == 6.65f && NativePreviewGuardChecks.guardedSupports(brush))
            check(!brush.hasDynamicWidth)
            for (scale in listOf(0.25f, 0.5f, 1f)) for (size in listOf(1f, 12f, 13.3f, 24f, 25f, 48f, 49f)) {
                val test = settings.copy(size = size, nativePreviewScale = scale).compile()
                check(NativePreviewGuardChecks.guardedSupports(test) == (size * scale * 2.5f <= 30f))
                if (NativePreviewGuardChecks.guardedSupports(test)) check(test.nativePreviewBaseWidth <= 12f)
                check(!NativePreviewGuardChecks.guardedSupports(settings.copy(size = size, nativePreviewScale = scale, shade = InkShade.NEAR_BLACK).compile()))
            }
            check(!NativePreviewGuardChecks.guardedSupports(settings.copy(size = 13.3f, nativePreviewScale = 1f, tilt = false).compile()))
            if (BuildConfig.RENDERER_BACKEND == "recovered") {
                val a = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
                val b = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
                val samples = List(100) { i -> PenSample(30f + i * 2.5f, 85f + kotlin.math.sin(i * 0.1f) * 22f,
                    0.1f + i / 111f, i * 4L, tiltRadians = 0.8f, documentAzimuthRadians = 0.6f) }
                try {
                    brush.draw(Canvas(a), samples)
                    for (texture in NativePreviewTexture.entries) for (scale in listOf(0.25f, 1f)) {
                        b.eraseColor(Color.TRANSPARENT)
                        val changed = settings.copy(nativePreviewScale = scale, nativePreviewTexture = texture).compile()
                        changed.draw(Canvas(b), samples)
                        check(a.sameAs(b)) { "Preview calibration changed final ${preset.label} pixels" }
                        check(brush.renderedBounds(samples) == changed.renderedBounds(samples))
                    }
                } finally { a.recycle(); b.recycle() }
            }
        }
        for (preset in listOf(BrushPreset.INK, BrushPreset.TILT, BrushPreset.LAB_FOUNTAIN, BrushPreset.LAB_BRUSH)) {
            val a = preset.defaults().compile()
            val b = preset.defaults().copy(nativePreviewScale = 0.25f, nativePreviewTexture = NativePreviewTexture.COARSE).compile()
            check(a.nativePreviewBaseWidth == a.maxWidth && a.nativePreviewBaseWidth == b.nativePreviewBaseWidth)
            check(a.nativePreviewStyle == b.nativePreviewStyle && a.nativePreviewMaxWidth == b.nativePreviewMaxWidth)
        }
        check(!NativePreviewGuardChecks.guardedSupports(BrushPreset.MONOLINE.defaults().copy(size = 100.8f, nativePreviewScale = 0.25f).compile()))
    }
}
