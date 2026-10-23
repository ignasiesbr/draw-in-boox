package art.boox.probe

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.View

object LayerPanelChecks {
    fun run(instrumentation: Instrumentation) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun onMain(block: () -> Unit) {
            var failure: Throwable? = null
            instrumentation.runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
            failure?.let { throw it }
        }
        fun awaitLayout(predicate: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 3000
            while (true) {
                var ready = false
                onMain { ready = predicate() }
                if (ready) return
                check(SystemClock.uptimeMillis() < deadline) { "Layer panel layout did not settle" }
                Thread.sleep(20)
            }
        }
        var panel: LayerPanel? = null
        var surface: RasterSurface? = null
        var wasExpanded = true
        try {
            instrumentation.waitForIdleSync()
            onMain {
                val root = activity.window.decorView
                panel = root.findViewWithTag("Layer panel")
                surface = root.findViewWithTag("Drawing surface")
                wasExpanded = panel!!.isExpanded
                check(panel!!.setExpanded(true))
            }
            awaitLayout { surface!!.document != null && surface!!.viewport.viewWidth == surface!!.width && surface!!.inputEnabled }
            // Exercise real dialogs with isolated artwork; allow window focus to settle
            // between modal transitions as it does with actual finger interaction.
            lateinit var testDoc: DrawingDocument
            lateinit var isolatedPanel: LayerPanel
            val edits = EditHistory()
            onMain {
                testDoc = DrawingDocument.create(CanvasSpec(32, 32))
                isolatedPanel = LayerPanel(activity, true, { true }, {}, {
                    edits.executeLayer(testDoc, it); isolatedPanel.render(testDoc)
                })
                isolatedPanel.render(testDoc)
                check(isolatedPanel.findViewWithTag<View>("Select layer ${testDoc.layerId}").performLongClick())
            }
            instrumentation.waitForIdleSync()
            onMain {
                val input = android.view.inspector.WindowInspector.getGlobalWindowViews()
                    .firstNotNullOf { it.findViewWithTag<android.widget.EditText>("Rename layer name") }
                val dialogRoot = input.rootView
                input.setText("   ")
                dialogRoot.findViewById<View>(android.R.id.button1).performClick()
                check(input.error != null && testDoc.activeLayer.name == "Layer 1")
                input.setText("  Ink study  ")
                dialogRoot.findViewById<View>(android.R.id.button1).performClick()
                check(testDoc.activeLayer.name == "Ink study")
                check(edits.undo(testDoc) != null && testDoc.activeLayer.name == "Layer 1")
            }
            instrumentation.waitForIdleSync()
            for (apply in listOf(false, true)) {
                onMain {
                    check(isolatedPanel.findViewWithTag<View>("Layer thumbnail ${testDoc.layerId}").performLongClick())
                }
                instrumentation.waitForIdleSync()
                onMain {
                    val slider = android.view.inspector.WindowInspector.getGlobalWindowViews()
                        .firstNotNullOf { it.findViewWithTag<android.widget.SeekBar>("Layer opacity") }
                    check(slider.progress == 100)
                    val alphaLock = slider.rootView.findViewWithTag<EinkIconButton>("Layer alpha lock")
                    check(!alphaLock.isChecked); alphaLock.performClick()
                    check(alphaLock.isChecked && !testDoc.activeLayer.alphaLocked)
                    slider.progress = 35
                    check(testDoc.activeLayer.opacity == 100)
                    check(slider.rootView.findViewWithTag<android.widget.TextView>("Layer opacity value").text == "Opacity: 35%")
                    slider.rootView.findViewById<View>(if (apply) android.R.id.button1 else android.R.id.button2).performClick()
                    check(testDoc.activeLayer.opacity == if (apply) 35 else 100)
                    check(testDoc.activeLayer.alphaLocked == apply)
                }
                instrumentation.waitForIdleSync()
            }
            onMain {
                check(edits.undo(testDoc) == "Layer settings" && testDoc.activeLayer.opacity == 100 && !testDoc.activeLayer.alphaLocked)
                testDoc.recycle()
            }
            awaitLayout { activity.hasWindowFocus() && surface!!.inputEnabled }
            var expandedWidth = 0
            var documentId = ""
            onMain {
                val doc = surface!!.document!!
                documentId = doc.id; expandedWidth = surface!!.width
                check(panel!!.findViewWithTag<EinkIconButton>("Lock ${doc.layerId}").isChecked == doc.activeLayer.locked)
                check(panel!!.findViewWithTag<EinkIconButton>("Layer Add").isEnabled == (doc.layers.size < doc.maxLayers))
                check(panel!!.findViewWithTag<EinkIconButton>("Hide layers").performClick())
            }
            awaitLayout { surface!!.width > expandedWidth && surface!!.viewport.viewWidth == surface!!.width && surface!!.inputEnabled }
            onMain {
                check(surface!!.document!!.id == documentId)
                val icon = panel!!.findViewWithTag<View>("Show layers")
                check(icon.visibility == View.VISIBLE && icon.performClick())
            }
            awaitLayout { surface!!.width == expandedWidth && surface!!.viewport.viewWidth == expandedWidth && surface!!.inputEnabled }
            onMain { check(surface!!.document!!.id == documentId && panel!!.isExpanded) }
            val originalSide = AppSettingsStore(activity).load().layerPanelSide
            fun chooseSide(side: LayerPanelSide) {
                onMain {
                    activity.window.decorView.findViewWithTag<View>("Settings").performClick()
                    val radio = android.view.inspector.WindowInspector.getGlobalWindowViews()
                        .firstNotNullOf { it.findViewWithTag<android.widget.RadioButton>("Layers panel ${side.storedValue}") }
                    radio.performClick()
                    radio.rootView.findViewById<View>(android.R.id.button1).performClick()
                }
                awaitLayout {
                    val panelLocation = IntArray(2); val canvasLocation = IntArray(2)
                    panel!!.getLocationOnScreen(panelLocation); surface!!.getLocationOnScreen(canvasLocation)
                    surface!!.inputEnabled && surface!!.width == expandedWidth &&
                        (if (side == LayerPanelSide.RIGHT) panelLocation[0] > canvasLocation[0]
                         else panelLocation[0] < canvasLocation[0])
                }
                onMain { check(surface!!.document!!.id == documentId && panel!!.isExpanded) }
            }
            try {
                chooseSide(if (originalSide == LayerPanelSide.RIGHT) LayerPanelSide.LEFT else LayerPanelSide.RIGHT)
            } finally { chooseSide(originalSide) }

        } finally {
            onMain { panel?.setExpanded(wasExpanded) }
            instrumentation.waitForIdleSync()
            onMain { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }
}
