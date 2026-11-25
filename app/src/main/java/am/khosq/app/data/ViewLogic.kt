package am.khosq.app.data

/**
 * The picture viewer: how far a picture may be zoomed and moved. Sizes are pixels. A position is measured from the middle of
 * the screen box (the picture is shown fitted and centred, and is scaled around that middle).
 */
object ZoomMath {
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 5f
    const val DOUBLE_TAP_SCALE = 2.5f

    /** How far the picture is zoomed and how far it was moved sideways ([x]) and up or down ([y]). */
    class State(val scale: Float, val x: Float, val y: Float)

    val REST = State(MIN_SCALE, 0f, 0f)

    fun clampScale(s: Float): Float = if (s.isNaN()) MIN_SCALE else s.coerceIn(MIN_SCALE, MAX_SCALE)

    /** The size the picture has when it is shown whole inside the box (width, height). */
    fun fitSize(picW: Float, picH: Float, boxW: Float, boxH: Float): Pair<Float, Float> {
        if (picW <= 0f || picH <= 0f || boxW <= 0f || boxH <= 0f) return Pair(0f, 0f)
        val k = minOf(boxW / picW, boxH / picH)
        return Pair(picW * k, picH * k)
    }

    /** How far the picture may be moved along one side: until its edge meets the edge of the box, never past it. */
    fun limit(fit: Float, box: Float, scale: Float): Float = maxOf(0f, (fit * scale - box) / 2f)

    private fun keep(v: Float, fit: Float, box: Float, scale: Float): Float {
        val l = limit(fit, box, scale)
        return if (v.isNaN()) 0f else v.coerceIn(-l, l)
    }

    /**
     * One step of a pinch or a drag: zoom by [zoom] around the point ([cx], [cy]) so that the spot under the fingers stays
     * under them, then move by ([panX], [panY]).
     */
    fun step(
        s: State,
        zoom: Float,
        cx: Float,
        cy: Float,
        panX: Float,
        panY: Float,
        fitW: Float,
        fitH: Float,
        boxW: Float,
        boxH: Float,
    ): State {
        val z = if (zoom.isNaN() || zoom <= 0f) 1f else zoom
        val ns = clampScale(s.scale * z)
        val r = ns / s.scale
        val nx = cx - (cx - s.x) * r + panX
        val ny = cy - (cy - s.y) * r + panY
        return State(ns, keep(nx, fitW, boxW, ns), keep(ny, fitH, boxH, ns))
    }

    /** A double tap: back to the whole picture when it is zoomed, otherwise zoom in on the tapped spot. */
    fun doubleTap(
        s: State,
        cx: Float,
        cy: Float,
        fitW: Float,
        fitH: Float,
        boxW: Float,
        boxH: Float,
    ): State {
        if (s.scale > 1.05f) return REST
        return step(s, DOUBLE_TAP_SCALE / s.scale, cx, cy, 0f, 0f, fitW, fitH, boxW, boxH)
    }
}

/** When the open chat follows its newest message down by itself. */
object ChatScroll {
    /**
     * [previous] and [now] are the ids of the newest message before and after a change (null: no message, or the chat was not
     * opened yet). The chat jumps to the newest message when it is mine (I just wrote it), or when it was already showing the
     * bottom. It stays where it is when I have scrolled up to read, when I am searching, and when the chat has only just opened.
     */
    fun follow(previous: String?, now: String?, mine: Boolean, wasAtBottom: Boolean, searching: Boolean): Boolean {
        if (searching) return false
        if (previous == null || now == null || previous == now) return false
        return mine || wasAtBottom
    }

    /** The "typing" bubble takes the bottom place: show it when the chat was at the bottom. */
    fun followTyping(typing: Boolean, wasAtBottom: Boolean, searching: Boolean): Boolean = typing && wasAtBottom && !searching
}

/** Sizes of the small copy of a profile photo that stays in memory. */
object PhotoMath {
    /**
     * The biggest power of two by which a picture can be read smaller and still be at least [target] pixels on both sides
     * (so a list of many people does not hold a big picture each).
     */
    fun sample(w: Int, h: Int, target: Int): Int {
        if (w <= 0 || h <= 0 || target <= 0) return 1
        var s = 1
        while (s < 64 && w / (s * 2) >= target && h / (s * 2) >= target) s *= 2
        return s
    }
}
