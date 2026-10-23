package art.boox.probe

import android.content.Context

enum class LayerPanelSide(val label: String, val storedValue: String) {
    LEFT("Left", "left"),
    RIGHT("Right", "right")
}

/** Application preferences live outside document archives and can grow independently. */
data class AppSettings(val layerPanelSide: LayerPanelSide = LayerPanelSide.RIGHT)

class AppSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        LayerPanelSide.entries.firstOrNull {
            it.storedValue == preferences.getString("layer_panel_side", null)
        } ?: LayerPanelSide.RIGHT
    )

    fun save(settings: AppSettings) {
        preferences.edit().putString("layer_panel_side", settings.layerPanelSide.storedValue).apply()
    }
}
