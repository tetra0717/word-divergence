package com.tetra.worddivergence.graph

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.tetra.worddivergence.model.GraphNode
import com.tetra.worddivergence.model.GraphSession
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class SemanticGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onNodeSelected(nodeId: String)
        fun onNodeLongPressed(nodeId: String)
        fun onViewportMoved(
            visibleNodeIds: List<String>,
            centerX: Float,
            centerY: Float,
            dirX: Float,
            dirY: Float
        )
    }

    var listener: Listener? = null
    private var session: GraphSession? = null

    private var centerX = 0f
    private var centerY = 0f
    private var scale = 1f
    private var selectedId: String? = null

    private val density get() = resources.displayMetrics.density

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(247, 248, 251)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(42, 92, 99, 112)
        style = Paint.Style.FILL
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(148, 174, 181, 193)
        strokeWidth = dp(1.15f)
        strokeCap = Paint.Cap.ROUND
    }
    private val nodeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 255, 255)
        style = Paint.Style.FILL
    }
    private val nodeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(205, 210, 220)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.15f)
    }
    private val rootFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(241, 242, 255)
        style = Paint.Style.FILL
    }
    private val rootStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(99, 102, 241)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.7f)
    }
    private val selectedStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(79, 70, 229)
        style = Paint.Style.STROKE
        strokeWidth = dp(2.4f)
    }
    private val selectedHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(36, 79, 70, 229)
        style = Paint.Style.FILL
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(34, 37, 44)
        textSize = sp(12.5f)
        textAlign = Paint.Align.LEFT
    }
    private val rootTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(49, 46, 129)
        textSize = sp(13f)
        isFakeBoldText = true
        textAlign = Paint.Align.LEFT
    }
    private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(99, 102, 241)
        style = Paint.Style.STROKE
        strokeWidth = dp(2.1f)
        strokeCap = Paint.Cap.ROUND
    }
    private val starFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(99, 102, 241)
        style = Paint.Style.FILL
    }
    private val starText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        textSize = sp(8.5f)
    }

    private val spatial = SpatialHash(180f)

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val id = hitTest(e.x, e.y) ?: return true
            selectedId = id
            listener?.onNodeSelected(id)
            invalidate()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            hitTest(e.x, e.y)?.let { listener?.onNodeLongPressed(it) }
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (scaleDetector.isInProgress) return false

            // Graph world coordinates are dp-based. Convert touch pixels back
            // through both density and zoom so high-DPI phones behave identically.
            val dxWorld = distanceX / worldScale()
            val dyWorld = distanceY / worldScale()
            centerX += dxWorld
            centerY += dyWorld
            notifyViewport(dxWorld, dyWorld)
            invalidate()
            return true
        }
    })

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val oldWorldScale = worldScale()
                val worldX = centerX + (detector.focusX - width / 2f) / oldWorldScale
                val worldY = centerY + (detector.focusY - height / 2f) / oldWorldScale

                scale = (scale * detector.scaleFactor).coerceIn(0.16f, 3.2f)

                val newWorldScale = worldScale()
                centerX = worldX - (detector.focusX - width / 2f) / newWorldScale
                centerY = worldY - (detector.focusY - height / 2f) / newWorldScale
                invalidate()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                notifyViewport(0f, 0f)
            }
        }
    )

    init {
        setBackgroundColor(Color.rgb(247, 248, 251))
        isClickable = true
        isFocusable = true
    }

    fun setSession(newSession: GraphSession, resetCamera: Boolean) {
        session = newSession
        rebuildSpatial()
        if (resetCamera) {
            centerX = newSession.camera.centerX
            centerY = newSession.camera.centerY
            scale = newSession.camera.scale.coerceIn(0.16f, 3.2f)
            selectedId = null
        }
        invalidate()
    }

    fun refreshSession() {
        rebuildSpatial()
        invalidate()
    }

    fun syncCameraToSession() {
        session?.camera?.apply {
            this.centerX = this@SemanticGraphView.centerX
            this.centerY = this@SemanticGraphView.centerY
            this.scale = this@SemanticGraphView.scale
        }
    }

    fun focusNode(node: GraphNode) {
        centerX = node.x
        centerY = node.y
        scale = max(scale, 0.78f)
        selectedId = node.id
        invalidate()
    }

    fun visibleNodeIds(): List<String> =
        spatial.query(visibleWorldRect(90f))

    /**
     * Nodes in the outer viewport band are the exploration frontier.
     * This is used by the Activity's debounced frontier watcher, so generation
     * doesn't depend on a single pan event landing at exactly the right moment.
     */
    fun frontierNodeIds(): List<String> {
        val outer = visibleWorldRect(40f)
        val innerW = outer.width() * 0.58f
        val innerH = outer.height() * 0.58f
        val inner = RectF(
            centerX - innerW / 2f,
            centerY - innerH / 2f,
            centerX + innerW / 2f,
            centerY + innerH / 2f
        )
        val s = session ?: return emptyList()
        return spatial.query(outer).filter { id ->
            val node = s.nodes[id] ?: return@filter false
            !node.expanded &&
                !node.loading &&
                node.parentId != null &&
                !inner.contains(node.x, node.y)
        }
    }

    fun distanceFromViewportCenter(node: GraphNode): Float =
        hypot(node.x - centerX, node.y - centerY)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gesture.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(backgroundPaint.color)
        drawDotGrid(canvas)

        val s = session ?: return
        val visible = spatial.query(visibleWorldRect(110f))
        val visibleSet = visible.toHashSet()

        // Edges first, always behind nodes.
        for (id in visible) {
            val node = s.nodes[id] ?: continue
            val parent = node.parentId?.let { s.nodes[it] } ?: continue
            if (node.id !in visibleSet && parent.id !in visibleSet) continue
            drawEdge(canvas, parent, node)
        }

        var hasVisibleLoading = false
        for (id in visible) {
            val node = s.nodes[id] ?: continue
            drawNode(canvas, node)
            if (node.loading) hasVisibleLoading = true
        }

        if (hasVisibleLoading) postInvalidateOnAnimation()
    }

    private fun drawDotGrid(canvas: Canvas) {
        val spacing = 34f
        val rect = visibleWorldRect(0f)
        val startX = floor(rect.left / spacing) * spacing
        val startY = floor(rect.top / spacing) * spacing
        val dotRadius = dp(if (scale < 0.45f) 0.65f else 0.8f)

        var x = startX
        while (x <= rect.right) {
            var y = startY
            while (y <= rect.bottom) {
                canvas.drawCircle(worldToScreenX(x), worldToScreenY(y), dotRadius, gridPaint)
                y += spacing
            }
            x += spacing
        }
    }

    private fun drawEdge(canvas: Canvas, parent: GraphNode, child: GraphNode) {
        val dx = child.x - parent.x
        val dy = child.y - parent.y
        val length = sqrt(dx * dx + dy * dy)
        if (length <= 0.001f) return

        val ux = dx / length
        val uy = dy / length
        val startX = parent.x + ux * parent.visualRadius()
        val startY = parent.y + uy * parent.visualRadius()
        val endX = child.x - ux * child.visualRadius()
        val endY = child.y - uy * child.visualRadius()

        canvas.drawLine(
            worldToScreenX(startX),
            worldToScreenY(startY),
            worldToScreenX(endX),
            worldToScreenY(endY),
            edgePaint
        )
    }

    private fun drawNode(canvas: Canvas, node: GraphNode) {
        val sx = worldToScreenX(node.x)
        val sy = worldToScreenY(node.y)
        val isRoot = node.parentId == null
        val screenRadius = node.visualRadius() * worldScale()

        // At very far zoom, use a clean dot representation instead of trying
        // to squeeze unreadable labels into tiny circles.
        if (scale < 0.30f) {
            val r = dp(if (isRoot) 5.2f else 3.8f)
            canvas.drawCircle(sx, sy, r, if (isRoot) rootStroke else nodeStroke)
            return
        }

        if (selectedId == node.id) {
            canvas.drawCircle(sx, sy, screenRadius + dp(7f), selectedHalo)
        }

        canvas.drawCircle(sx, sy, screenRadius, if (isRoot) rootFill else nodeFill)
        canvas.drawCircle(sx, sy, screenRadius, if (isRoot) rootStroke else nodeStroke)

        if (selectedId == node.id) {
            canvas.drawCircle(sx, sy, screenRadius + dp(2.5f), selectedStroke)
        }

        if (node.starred) {
            val badgeR = dp(10f)
            val bx = sx + screenRadius * 0.72f
            val by = sy - screenRadius * 0.72f
            canvas.drawCircle(bx, by, badgeR, starFill)
            val baseline = by - (starText.ascent() + starText.descent()) / 2f
            canvas.drawText("★", bx, baseline, starText)
        }

        if (node.loading) {
            val rr = screenRadius + dp(6.5f)
            val rect = RectF(sx - rr, sy - rr, sx + rr, sy + rr)
            val phase = ((SystemClock.uptimeMillis() / 3L) % 360L).toFloat()
            canvas.drawArc(rect, phase, 235f, false, spinnerPaint)
        }

        if (scale >= 0.42f) {
            drawNodeText(canvas, node, sx, sy, screenRadius, isRoot)
        }
    }

    private fun drawNodeText(
        canvas: Canvas,
        node: GraphNode,
        sx: Float,
        sy: Float,
        radiusPx: Float,
        isRoot: Boolean
    ) {
        val paint = if (isRoot) rootTextPaint else textPaint
        val zoomTextScale = scale.coerceIn(0.78f, 1.18f)
        paint.textSize = sp(if (isRoot) 13f else 12.2f) * zoomTextScale

        val horizontalPadding = dp(10f)
        val width = ((radiusPx * 1.52f) - horizontalPadding * 2f)
            .toInt()
            .coerceAtLeast(dp(28f).toInt())

        val layout = StaticLayout.Builder
            .obtain(node.text, 0, node.text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setMaxLines(if (node.text.length > 18) 3 else 2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(0f, 0.96f)
            .build()

        canvas.save()
        canvas.translate(sx - width / 2f, sy - layout.height / 2f)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun hitTest(screenX: Float, screenY: Float): String? {
        val wx = centerX + (screenX - width / 2f) / worldScale()
        val wy = centerY + (screenY - height / 2f) / worldScale()
        val probe = 92f
        val ids = spatial.query(RectF(wx - probe, wy - probe, wx + probe, wy + probe))
        val s = session ?: return null

        var best: String? = null
        var bestD = Float.MAX_VALUE
        for (id in ids) {
            val node = s.nodes[id] ?: continue
            val d = hypot(node.x - wx, node.y - wy)
            val touchRadius = node.visualRadius() + 7f / max(scale, 0.35f)
            if (d <= touchRadius && d < bestD) {
                best = id
                bestD = d
            }
        }
        return best
    }

    private fun notifyViewport(dxWorld: Float, dyWorld: Float) {
        val ids = spatial.query(visibleWorldRect(75f))
        listener?.onViewportMoved(ids, centerX, centerY, dxWorld, dyWorld)
    }

    private fun visibleWorldRect(extraDp: Float): RectF {
        val halfW = (width / 2f + dp(extraDp)) / worldScale()
        val halfH = (height / 2f + dp(extraDp)) / worldScale()
        return RectF(
            centerX - halfW,
            centerY - halfH,
            centerX + halfW,
            centerY + halfH
        )
    }

    private fun worldScale(): Float = density * scale

    private fun worldToScreenX(x: Float): Float =
        width / 2f + (x - centerX) * worldScale()

    private fun worldToScreenY(y: Float): Float =
        height / 2f + (y - centerY) * worldScale()

    private fun rebuildSpatial() {
        spatial.clear()
        session?.nodes?.values?.forEach { spatial.put(it.id, it.x, it.y) }
    }

    private fun dp(v: Float): Float = v * density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity

    private class SpatialHash(private val cellSize: Float) {
        private val cells = HashMap<Long, MutableList<Entry>>()

        data class Entry(val id: String, val x: Float, val y: Float)

        fun clear() = cells.clear()

        fun put(id: String, x: Float, y: Float) {
            cells.getOrPut(key(cell(x), cell(y))) { ArrayList() }
                .add(Entry(id, x, y))
        }

        fun query(rect: RectF): List<String> {
            if (rect.width() <= 0f || rect.height() <= 0f) return emptyList()
            val out = ArrayList<String>()
            val minX = cell(rect.left)
            val maxX = cell(rect.right)
            val minY = cell(rect.top)
            val maxY = cell(rect.bottom)

            for (cx in minX..maxX) {
                for (cy in minY..maxY) {
                    val entries = cells[key(cx, cy)] ?: continue
                    for (entry in entries) {
                        if (
                            entry.x >= rect.left &&
                            entry.x <= rect.right &&
                            entry.y >= rect.top &&
                            entry.y <= rect.bottom
                        ) {
                            out += entry.id
                        }
                    }
                }
            }
            return out
        }

        private fun cell(v: Float): Int = floor(v / cellSize).toInt()

        private fun key(x: Int, y: Int): Long =
            (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)
    }
}
