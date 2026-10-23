package art.boox.probe

import android.content.Context

enum class LayerPanelSide(val label: String, val storedValue: String) {
    LEFT("Left", "left"),
    RIGHT("Right", "right")
}

/** Application preferences live outside document archives and can grow independently. */
data class AppSettings(val layerPanelSide: LayerPanelSide = LayerPanelSide.RIGHT,
    val undoGestureEnabled: Boolean = true, val redoGestureEnabled: Boolean = true)

class AppSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        LayerPanelSide.entries.firstOrNull {
            it.storedValue == preferences.getString("layer_panel_side", null)
        } ?: LayerPanelSide.RIGHT,
        undoGestureEnabled = preferences.getBoolean("undo_gesture_enabled", true),
        redoGestureEnabled = preferences.getBoolean("redo_gesture_enabled", true)
    )

    fun save(settings: AppSettings) {
        preferences.edit().putString("layer_panel_side", settings.layerPanelSide.storedValue)
            .putBoolean("undo_gesture_enabled", settings.undoGestureEnabled)
            .putBoolean("redo_gesture_enabled", settings.redoGestureEnabled).apply()
    }
}
