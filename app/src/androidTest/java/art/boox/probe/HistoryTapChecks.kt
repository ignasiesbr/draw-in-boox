package art.boox.probe

import android.view.InputDevice
import android.view.MotionEvent

/** Virtual-time checks: no sleeps and no ambiguity between delayed undo and double-tap redo. */
object HistoryTapChecks {
    fun run() {
        var now = 0L
        var eligible = true
        var undoEnabled = true
        var redoEnabled = true
        val tasks = mutableMapOf<Runnable, Long>()
        val actions = mutableListOf<String>()
        val taps = HistoryTapGestures(20f, 300L, { eligible },
            post = { task, delay -> tasks[task] = now + delay }, remove = { tasks.remove(it) },
            undo = { actions += "undo" }, redo = { actions += "redo" },
            undoEnabled = { undoEnabled }, redoEnabled = { redoEnabled })
        var dispatch: (MotionEvent) -> Boolean = taps::onTouchEvent
        fun advance(time: Long) {
            require(time >= now)
            while (true) {
                val due = tasks.entries.filter { it.value <= time }.minByOrNull { it.value } ?: break
                now = due.value; val task = due.key; tasks.remove(task); task.run()
            }
            now = time
        }
        fun send(time: Long, action: Int, ids: List<Int>, move: Float = 0f, tool: Int = MotionEvent.TOOL_TYPE_FINGER,
            flags: Int = 0, spread: Float = 1f, degrees: Float = 0f): Boolean {
            advance(time)
            val props = ids.map { id -> MotionEvent.PointerProperties().apply { this.id = id; toolType = tool } }.toTypedArray()
            val coords = ids.map { id -> MotionEvent.PointerCoords().apply {
                val radians = Math.toRadians(degrees.toDouble())
                x = 280f + (id - 6) * 30f * spread * kotlin.math.cos(radians).toFloat() + move
                y = 100f + (id - 6) * 30f * spread * kotlin.math.sin(radians).toFloat()
                pressure = 0.5f
            } }.toTypedArray()
            val event = MotionEvent.obtain(time, time, action, ids.size, props, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags)
            return try { dispatch(event) } finally { event.recycle() }
        }
        fun down(index: Int) = MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        fun up(index: Int) = MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        fun chord(time: Long, accepted: Boolean = true) {
            check(!send(time, MotionEvent.ACTION_DOWN, listOf(3)))
            check(!send(time + 20, down(1), listOf(3, 9)))
            // Indices reorder without moving the physical contacts.
            check(!send(time + 40, MotionEvent.ACTION_MOVE, listOf(9, 3)))
            check(!send(time + 60, up(0), listOf(9, 3)))
            check(send(time + 100, MotionEvent.ACTION_UP, listOf(3)) == accepted)
        }

        chord(100)
        advance(499)
        check(actions.isEmpty()) { "Single tap fired before the double-tap window" }
        advance(500)
        check(actions == listOf("undo"))
        actions.clear()
        chord(1000)
        chord(1250)
        check(actions == listOf("redo")) { "Double tap incorrectly executed undo first" }
        advance(2000)
        check(actions == listOf("redo")) { "Delayed undo leaked after redo" }
        actions.clear()
        chord(2100)
        chord(2490) // second starts 290ms after first release, ends after the old timer
        advance(3000)
        check(actions == listOf("redo"))

        // Pending taps cannot act after focus loss or a pen beginning to draw.
        actions.clear(); chord(3100); taps.cancel(); advance(3700)
        check(actions.isEmpty())
        chord(3800)
        eligible = false
        send(3920, MotionEvent.ACTION_DOWN, listOf(7), tool = MotionEvent.TOOL_TYPE_STYLUS)
        eligible = true; advance(4500)
        check(actions.isEmpty())

        // Extra contacts, movement, cancellation and slow chords never become history edits.
        send(4600, MotionEvent.ACTION_DOWN, listOf(3))
        send(4620, down(1), listOf(3, 9))
        send(4640, down(2), listOf(3, 9, 12))
        send(4660, down(3), listOf(3, 9, 12, 15))
        send(4680, MotionEvent.ACTION_CANCEL, listOf(3, 9, 12, 15))
        send(4800, MotionEvent.ACTION_DOWN, listOf(3))
        send(4820, down(1), listOf(3, 9))
        send(4840, MotionEvent.ACTION_MOVE, listOf(3, 9), move = 40f)
        send(4860, up(1), listOf(3, 9), move = 40f)
        send(4880, MotionEvent.ACTION_UP, listOf(3), move = 40f)
        send(5000, MotionEvent.ACTION_DOWN, listOf(3))
        send(5300, down(1), listOf(3, 9))
        send(5320, up(1), listOf(3, 9))
        send(5340, MotionEvent.ACTION_UP, listOf(3))
        send(5400, MotionEvent.ACTION_DOWN, listOf(3))
        send(5420, down(1), listOf(3, 9))
        send(5460, MotionEvent.ACTION_UP, listOf(3), flags = MotionEvent.FLAG_CANCELED)
        advance(6000)
        check(actions.isEmpty())

        // Single fingers and replacing a contact in a chord are not valid taps.
        send(6100, MotionEvent.ACTION_DOWN, listOf(3))
        send(6120, MotionEvent.ACTION_UP, listOf(3))
        send(6200, MotionEvent.ACTION_DOWN, listOf(3))
        send(6220, down(1), listOf(3, 9))
        send(6240, up(1), listOf(3, 9))
        send(6260, down(1), listOf(3, 12))
        send(6280, up(1), listOf(3, 12))
        send(6300, MotionEvent.ACTION_UP, listOf(3))
        advance(6600)
        check(actions.isEmpty())
        send(6700, MotionEvent.ACTION_DOWN, listOf(3))
        send(6720, down(1), listOf(3, 9))
        send(7200, up(1), listOf(3, 9))
        send(7220, MotionEvent.ACTION_UP, listOf(3))
        advance(7600)
        check(actions.isEmpty())

        // Exercise exactly the tap-before-navigation arbitration used by RasterSurface.
        lateinit var combined: HistoryTapGestures
        var pans = 0
        var rotations = 0
        var zooms = 0
        val navigation = CanvasGestures(20f, 90f,
            begin = { combined.cancel(); true },
            pan = { _, _ -> pans++ }, rotation = { rotations++ }, zoom = { _, _, _ -> zooms++ },
            end = {}, rotationEnabled = { true })
        combined = HistoryTapGestures(20f, 300L, { !navigation.active },
            post = { task, delay -> tasks[task] = now + delay }, remove = { tasks.remove(it) },
            undo = { actions += "undo" }, redo = { actions += "redo" },
            undoEnabled = { undoEnabled }, redoEnabled = { redoEnabled })
        dispatch = { event ->
            val consumed = combined.onTouchEvent(event)
            if (consumed) navigation.cancel() else navigation.onTouchEvent(event)
            consumed
        }
        chord(8000)
        advance(8500)
        check(actions == listOf("undo")) { "Navigation swallowed a two-finger tap" }
        actions.clear()
        chord(8600); chord(8850); advance(9300)
        check(actions == listOf("redo"))
        actions.clear()
        for ((index, kind) in listOf("pan", "rotation", "zoom").withIndex()) {
            val time = 9400L + index * 1000
            send(time, MotionEvent.ACTION_DOWN, listOf(3))
            send(time + 20, down(1), listOf(3, 9))
            val move = if (kind == "pan") 40f else 0f
            val spread = if (kind == "zoom") 1.6f else 1f
            val degrees = if (kind == "rotation") 8f else 0f
            send(time + 40, MotionEvent.ACTION_MOVE, listOf(3, 9), move = move, spread = spread, degrees = degrees)
            check(navigation.active) { "Tap candidate blocked $kind" }
            send(time + 60, up(1), listOf(3, 9), move = move, spread = spread, degrees = degrees)
            send(time + 100, MotionEvent.ACTION_UP, listOf(3), move = move, spread = spread, degrees = degrees)
            advance(time + 700)
            check(actions.isEmpty() && !navigation.active) { "$kind triggered history" }
        }
        check(pans > 0 && rotations > 0 && zooms > 0)

        // The more forgiving join/hold windows accept a deliberate but slower chord.
        send(12500, MotionEvent.ACTION_DOWN, listOf(3))
        send(12700, down(1), listOf(3, 9))
        send(12850, up(1), listOf(3, 9))
        send(12900, MotionEvent.ACTION_UP, listOf(3))
        advance(13200)
        check(actions == listOf("undo"))
        // Independent toggles keep single/double-tap meanings stable.
        dispatch = taps::onTouchEvent
        actions.clear(); undoEnabled = false
        chord(14000); advance(14500)
        check(actions.isEmpty())
        chord(14600); chord(14850); advance(15400)
        check(actions == listOf("redo")) { "Disabling undo also disabled redo" }
        actions.clear(); undoEnabled = true; redoEnabled = false
        chord(15500); advance(16000)
        check(actions == listOf("undo"))
        actions.clear(); chord(16100); chord(16350); advance(16900)
        check(actions.isEmpty()) { "Disabled redo became undo" }
        undoEnabled = false
        chord(17000, accepted = false); chord(17250, accepted = false); advance(17800)
        check(actions.isEmpty() && tasks.isEmpty())
        undoEnabled = true; redoEnabled = true
        chord(18000); undoEnabled = false; advance(18500)
        check(actions.isEmpty()) { "A disabled pending undo fired" }

        // Turning both shortcuts off must not swallow two-finger navigation.
        redoEnabled = false
        dispatch = { event ->
            val consumed = combined.onTouchEvent(event)
            if (consumed) navigation.cancel() else navigation.onTouchEvent(event)
            consumed
        }
        val previousPans = pans
        send(19000, MotionEvent.ACTION_DOWN, listOf(3))
        send(19020, down(1), listOf(3, 9))
        send(19040, MotionEvent.ACTION_MOVE, listOf(3, 9), move = 40f)
        check(navigation.active && pans > previousPans)
        send(19060, up(1), listOf(3, 9), move = 40f)
        send(19100, MotionEvent.ACTION_UP, listOf(3), move = 40f)
        advance(19700)
        check(actions.isEmpty() && !navigation.active)
    }
}
