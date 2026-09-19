package com.tetra.worddivergence.graph

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.tetra.worddivergence.model.GraphNode
import com.tetra.worddivergence.model.GraphSession
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class SemanticGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onNodeSelected(nodeId: String)
        fun onNodeLongPressed(nodeId: String)
        fun onViewportMoved(visibleNodeIds: List<String>, centerX: Float, centerY: Float, dirX: Float, dirY: Float)
    }

    var listener: Listener? = null
    private var session: GraphSession? = null

    private var centerX = 0f
    private var centerY = 0f
    private var scale = 1f
    private var selectedId: String? = null

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(205, 205, 210)
        strokeWidth = dp(1f)
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(72, 72, 82)
        style = Paint.Style.FILL
    }
    private val rootPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(75, 75, 200)
        style = Paint.Style.FILL
    }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(105, 82, 220)
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(30, 30, 35)
        style = Paint.Style.STROKE
        strokeWidth = dp(2.3f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(48, 48, 55)
        textSize = dp(12f)
    }
    private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(91, 91, 214)
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }

    private val spatial = SpatialHash(260f)

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

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaleDetector.isInProgress) return false
            val dxWorld = distanceX / scale
            val dyWorld = distanceY / scale
            centerX += dxWorld
            centerY += dyWorld
            notifyViewport(dxWorld, dyWorld)
            invalidate()
            return true
        }
    })

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val oldScale = scale
            val worldX = centerX + (detector.focusX - width / 2f) / oldScale
            val worldY = centerY + (detector.focusY - height / 2f) / oldScale
            scale = (scale * detector.scaleFactor).coerceIn(0.16f, 3.4f)
            centerX = worldX - (detector.focusX - width / 2f) / scale
            centerY = worldY - (detector.focusY - height / 2f) / scale
            invalidate()
            return true
        }
    })

    init {
        setBackgroundColor(Color.rgb(250, 250, 250))
        isClickable = true
        isFocusable = true
    }

    fun setSession(newSession: GraphSession, resetCamera: Boolean) {
        session = newSession
        rebuildSpatial()
        if (resetCamera) {
            centerX = newSession.camera.centerX
            centerY = newSession.camera.centerY
            scale = newSession.camera.scale.coerceIn(0.16f, 3.4f)
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
        scale = max(scale, 0.72f)
        selectedId = node.id
        invalidate()
    }

    fun visibleNodeIds(): List<String> = spatial.query(visibleWorldRect(80f))

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gesture.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = session ?: return
        val visible = spatial.query(visibleWorldRect(120f))
        val visibleSet = visible.toHashSet()

        for (id in visible) {
            val n = s.nodes[id] ?: continue
            val parent = n.parentId?.let { s.nodes[it] } ?: continue
            if (id !in visibleSet && parent.id !in visibleSet) continue
            canvas.drawLine(worldToScreenX(parent.x), worldToScreenY(parent.y),
                worldToScreenX(n.x), worldToScreenY(n.y), edgePaint)
        }

        var hasVisibleLoading = false
        for (id in visible) {
            val n = s.nodes[id] ?: continue
            val sx = worldToScreenX(n.x)
            val sy = worldToScreenY(n.y)
            val isRoot = n.parentId == null
            val r = dp(if (isRoot) 7.5f else 5.3f) * min(1.25f, max(0.72f, scale))
            canvas.drawCircle(sx, sy, r, if (isRoot) rootPaint else nodePaint)

            if (n.starred) {
                canvas.drawCircle(sx, sy, r + dp(4f), starPaint)
            }
            if (selectedId == n.id) {
                canvas.drawCircle(sx, sy, r + dp(6.5f), selectedPaint)
            }
            if (n.loading) {
                hasVisibleLoading = true
                val rr = r + dp(9f)
                val rect = RectF(sx - rr, sy - rr, sx + rr, sy + rr)
                val phase = ((SystemClock.uptimeMillis() / 3L) % 360L).toFloat()
                canvas.drawArc(rect, phase, 230f, false, spinnerPaint)
            }

            if (scale >= 0.46f) {
                val text = if (n.text.length > 20) n.text.take(19) + "…" else n.text
                val old = textPaint.textSize
                textPaint.textSize = dp(if (scale > 1.25f) 13f else 11.5f)
                canvas.drawText(text, sx + r + dp(5f), sy + textPaint.textSize * 0.35f, textPaint)
                textPaint.textSize = old
            }
        }

        if (hasVisibleLoading) postInvalidateOnAnimation()
    }

    private fun hitTest(screenX: Float, screenY: Float): String? {
        val wx = centerX + (screenX - width / 2f) / scale
        val wy = centerY + (screenY - height / 2f) / scale
        val radiusWorld = dp(30f) / scale
        val ids = spatial.query(RectF(wx - radiusWorld, wy - radiusWorld, wx + radiusWorld, wy + radiusWorld))
        var best: String? = null
        var bestD = Float.MAX_VALUE
        val s = session ?: return null
        for (id in ids) {
            val n = s.nodes[id] ?: continue
            val d = hypot(n.x - wx, n.y - wy)
            if (d < bestD && d <= radiusWorld) {
                bestD = d
                best = id
            }
        }
        return best
    }

    private fun notifyViewport(dxWorld: Float, dyWorld: Float) {
        val rect = visibleWorldRect(90f)
        val ids = spatial.query(rect)
        listener?.onViewportMoved(ids, centerX, centerY, dxWorld, dyWorld)
    }

    private fun visibleWorldRect(extraPx: Float): RectF {
        val halfW = (width / 2f + dp(extraPx)) / scale
        val halfH = (height / 2f + dp(extraPx)) / scale
        return RectF(centerX - halfW, centerY - halfH, centerX + halfW, centerY + halfH)
    }

    private fun worldToScreenX(x: Float): Float = width / 2f + (x - centerX) * scale
    private fun worldToScreenY(y: Float): Float = height / 2f + (y - centerY) * scale

    private fun rebuildSpatial() {
        spatial.clear()
        session?.nodes?.values?.forEach { spatial.put(it.id, it.x, it.y) }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private class SpatialHash(private val cellSize: Float) {
        private val cells = HashMap<Long, MutableList<Entry>>()

        data class Entry(val id: String, val x: Float, val y: Float)

        fun clear() = cells.clear()

        fun put(id: String, x: Float, y: Float) {
            cells.getOrPut(key(cell(x), cell(y))) { ArrayList() }.add(Entry(id, x, y))
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
                    for (e in entries) {
                        if (e.x >= rect.left && e.x <= rect.right && e.y >= rect.top && e.y <= rect.bottom) {
                            out += e.id
                        }
                    }
                }
            }
            return out
        }

        private fun cell(v: Float): Int = floor(v / cellSize).toInt()
        private fun key(x: Int, y: Int): Long = (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)
    }
}
