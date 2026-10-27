package art.boox.probe

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.View

object LayerPanelChecks {
    private fun sizing(context: android.content.Context) {
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val document = DrawingDocument.create(CanvasSpec(64, 64))
        try {
            val panel = LayerPanel(context, true, { true }, {}, {})
            fun measure(): Int {
                panel.render(document)
                panel.measure(View.MeasureSpec.makeMeasureSpec(panel.panelWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dp(600), View.MeasureSpec.AT_MOST))
                panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
                return panel.height
            }
            val one = measure()
            check(one in dp(160)..dp(350)) { "Single-layer panel did not wrap its content: $one" }
            document.addLayer()
            check(measure() > one) { "Panel did not grow with its layer list" }
            repeat(minOf(6, document.maxLayers - document.layers.size)) { document.addLayer() }
            check(measure() == dp(600)) { "Long layer list did not respect the available height" }
            fun scroll(view: View): android.widget.ScrollView? {
                if (view is android.widget.ScrollView) return view
                if (view is android.view.ViewGroup) for (index in 0 until view.childCount)
                    scroll(view.getChildAt(index))?.let { return it }
                return null
            }
            val list = checkNotNull(scroll(panel))
            list.scrollTo(0, Int.MAX_VALUE)
            check(list.scrollY > 0) { "Bounded layer list cannot scroll to lower rows" }
            check(panel.setExpanded(false))
            check(measure() == dp(60)) { "Collapsed panel still occupies a full-height rail" }
        } finally { document.recycle() }
    }

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
                sizing(activity)
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
            var initialTransform: ViewTransform? = null
            fun checkOverlay() {
                val canvas = checkNotNull(surface)
                val layers = checkNotNull(panel)
                val workspace = (canvas.parent as View).parent as View
                check(canvas.width == workspace.width && canvas.height == workspace.height) {
                    "Layers panel still reserves a column beside the canvas"
                }
                val origin = IntArray(2).also { canvas.getLocationOnScreen(it) }
                val location = IntArray(2).also { layers.getLocationOnScreen(it) }
                val bounds = android.graphics.Rect(location[0] - origin[0], location[1] - origin[1],
                    location[0] - origin[0] + layers.width, location[1] - origin[1] + layers.height)
                val exclusions = (activity as MainActivity).drawingInputExclusions()
                check(bounds in exclusions) { "Native pen input does not exclude the visible panel" }
                check(bounds.bottom < canvas.height && exclusions.none { it.contains(bounds.centerX(), bounds.bottom + 1) }) {
                    "Pen input remains blocked below the layers panel"
                }
            }
            onMain {
                val doc = surface!!.document!!
                documentId = doc.id; expandedWidth = surface!!.width
                initialTransform = surface!!.viewport.transform
                checkOverlay()
                check(panel!!.findViewWithTag<EinkIconButton>("Lock ${doc.layerId}").isChecked == doc.activeLayer.locked)
                check(panel!!.findViewWithTag<EinkIconButton>("Layer Add").isEnabled == (doc.layers.size < doc.maxLayers))
                check(panel!!.findViewWithTag<EinkIconButton>("Hide layers").performClick())
            }
            awaitLayout { !panel!!.isExpanded && panel!!.height == (60 * activity.resources.displayMetrics.density).toInt() &&
                !panel!!.isLayoutRequested && surface!!.inputEnabled }
            onMain {
                check(surface!!.document!!.id == documentId && surface!!.width == expandedWidth)
                check(surface!!.viewport.transform == initialTransform)
                checkOverlay()
                val icon = panel!!.findViewWithTag<View>("Show layers")
                check(icon.visibility == View.VISIBLE && icon.performClick())
            }
            awaitLayout { panel!!.isExpanded && panel!!.width == panel!!.panelWidth && !panel!!.isLayoutRequested && surface!!.inputEnabled }
            onMain {
                check(surface!!.document!!.id == documentId && surface!!.width == expandedWidth)
                check(surface!!.viewport.transform == initialTransform)
                checkOverlay()
            }
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
                        panelLocation[0] == canvasLocation[0] +
                            (if (side == LayerPanelSide.RIGHT) surface!!.width - panel!!.width else 0)
                }
                onMain {
                    check(surface!!.document!!.id == documentId && panel!!.isExpanded)
                    check(surface!!.viewport.transform == initialTransform)
                    checkOverlay()
                }
            }
            try {
                chooseSide(if (originalSide == LayerPanelSide.RIGHT) LayerPanelSide.LEFT else LayerPanelSide.RIGHT)
            } finally { chooseSide(originalSide) }

            // Hiding overlays preserves the view and removes their native exclusions.
            val root = activity.window.decorView
            val toolbar = root.findViewWithTag<View>("Floating toolbar")
            val showControls = root.findViewWithTag<View>("Show controls")
            onMain {
                check(root.findViewWithTag<View>("Drawing mode").performClick())
                check(toolbar.visibility == View.GONE && panel!!.visibility == View.GONE)
                check(showControls.visibility == View.VISIBLE)
                check(surface!!.viewport.transform == initialTransform && surface!!.document!!.id == documentId)
            }
            awaitLayout { showControls.isShown && showControls.width > 0 && surface!!.inputEnabled }
            onMain {
                val exclusions = (activity as MainActivity).drawingInputExclusions()
                val location = IntArray(2).also { showControls.getLocationOnScreen(it) }
                val origin = IntArray(2).also { surface!!.getLocationOnScreen(it) }
                check(exclusions == listOf(android.graphics.Rect(location[0] - origin[0], location[1] - origin[1],
                    location[0] - origin[0] + showControls.width, location[1] - origin[1] + showControls.height))) {
                    "Hidden controls still block native ink"
                }
                surface!!.beginRawStroke()
                try { check(!activity.setDrawingMode(false) && toolbar.visibility == View.GONE) }
                finally { surface!!.endRawStroke() }
                check(showControls.performClick())
            }
            awaitLayout { toolbar.isShown && panel!!.isShown && surface!!.inputEnabled }
            onMain {
                check(surface!!.viewport.transform == initialTransform && surface!!.document!!.id == documentId && panel!!.isExpanded)
                checkOverlay()
                check((activity as MainActivity).setDrawingMode(true))
            }
            awaitLayout { showControls.isShown && surface!!.inputEnabled }
            onMain {
                @Suppress("DEPRECATION")
                activity.onBackPressed()
                check(toolbar.visibility == View.VISIBLE && !activity.isFinishing)
            }
            awaitLayout { toolbar.isShown && panel!!.isShown && surface!!.inputEnabled }

        } finally {
            onMain { (activity as MainActivity).setDrawingMode(false) }
            onMain { panel?.setExpanded(wasExpanded) }
            instrumentation.waitForIdleSync()
            onMain { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }
}
