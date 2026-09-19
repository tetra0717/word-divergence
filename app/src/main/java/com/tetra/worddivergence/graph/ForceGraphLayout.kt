package com.tetra.worddivergence.graph

import com.tetra.worddivergence.model.GraphNode
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Deterministic force-directed layout inspired by the classic
 * link + charge + collision pattern.
 *
 * It intentionally does NOT encode semantic distance into geometry.
 * Root stays anchored at (0, 0). All other nodes are allowed to relax.
 */
object ForceGraphLayout {

    data class Position(val x: Float, val y: Float)

    private data class SimNode(
        val id: String,
        val parentId: String?,
        val radius: Float,
        val root: Boolean,
        var x: Float,
        var y: Float,
        var vx: Float = 0f,
        var vy: Float = 0f
    )

    fun relax(nodes: List<GraphNode>): Map<String, Position> {
        if (nodes.size <= 1) {
            return nodes.associate { it.id to Position(it.x, it.y) }
        }

        val sim = nodes.map {
            SimNode(
                id = it.id,
                parentId = it.parentId,
                radius = it.visualRadius(),
                root = it.parentId == null,
                x = it.x,
                y = it.y
            )
        }
        val byId = sim.withIndex().associate { it.value.id to it.index }

        val iterations = when {
            sim.size < 250 -> 64
            sim.size < 900 -> 46
            sim.size < 2200 -> 30
            else -> 20
        }

        repeat(iterations) { iteration ->
            val t = iteration.toFloat() / max(1, iterations - 1)
            val alpha = 0.92f * (1f - t) * (1f - t) + 0.06f

            val fx = FloatArray(sim.size)
            val fy = FloatArray(sim.size)

            applyLinkForce(sim, byId, fx, fy, alpha)
            applyLocalChargeAndCollision(sim, fx, fy, alpha)

            for (i in sim.indices) {
                val n = sim[i]
                if (n.root) {
                    n.x = 0f
                    n.y = 0f
                    n.vx = 0f
                    n.vy = 0f
                    continue
                }

                // Small global gravity keeps connected components compact without
                // forcing a radial semantic layout.
                fx[i] += -n.x * 0.0016f * alpha
                fy[i] += -n.y * 0.0016f * alpha

                n.vx = (n.vx + fx[i]).coerceIn(-26f, 26f) * 0.70f
                n.vy = (n.vy + fy[i]).coerceIn(-26f, 26f) * 0.70f
                n.x += n.vx
                n.y += n.vy
            }
        }

        return sim.associate { it.id to Position(it.x, it.y) }
    }

    private fun applyLinkForce(
        nodes: List<SimNode>,
        byId: Map<String, Int>,
        fx: FloatArray,
        fy: FloatArray,
        alpha: Float
    ) {
        for (i in nodes.indices) {
            val child = nodes[i]
            val parentId = child.parentId ?: continue
            val pIndex = byId[parentId] ?: continue
            val parent = nodes[pIndex]

            var dx = child.x - parent.x
            var dy = child.y - parent.y
            var distance = hypot(dx, dy)
            if (distance < 0.001f) {
                dx = deterministicUnitX(child.id)
                dy = deterministicUnitY(child.id)
                distance = 1f
            }

            val desired = parent.radius + child.radius + 86f
            val spring = (distance - desired) * 0.055f * alpha
            val ux = dx / distance
            val uy = dy / distance
            val sx = ux * spring
            val sy = uy * spring

            fx[i] -= sx
            fy[i] -= sy
            if (!parent.root) {
                fx[pIndex] += sx
                fy[pIndex] += sy
            }
        }
    }

    private fun applyLocalChargeAndCollision(
        nodes: List<SimNode>,
        fx: FloatArray,
        fy: FloatArray,
        alpha: Float
    ) {
        val cellSize = 230f
        val grid = HashMap<Long, MutableList<Int>>()

        fun cell(v: Float): Int = kotlin.math.floor(v / cellSize).toInt()
        fun key(x: Int, y: Int): Long =
            (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)

        for (i in nodes.indices) {
            val n = nodes[i]
            grid.getOrPut(key(cell(n.x), cell(n.y))) { ArrayList() }.add(i)
        }

        for (i in nodes.indices) {
            val a = nodes[i]
            val cx = cell(a.x)
            val cy = cell(a.y)

            for (gx in (cx - 1)..(cx + 1)) {
                for (gy in (cy - 1)..(cy + 1)) {
                    val bucket = grid[key(gx, gy)] ?: continue
                    for (j in bucket) {
                        if (j <= i) continue
                        val b = nodes[j]

                        var dx = b.x - a.x
                        var dy = b.y - a.y
                        var distance = hypot(dx, dy)

                        if (distance < 0.001f) {
                            dx = deterministicUnitX(a.id + b.id)
                            dy = deterministicUnitY(a.id + b.id)
                            distance = 1f
                        }

                        val ux = dx / distance
                        val uy = dy / distance
                        val minDistance = a.radius + b.radius + 18f

                        var push = 0f
                        if (distance < minDistance) {
                            // Strong soft collision.
                            push += (minDistance - distance) * 0.42f * alpha
                        }

                        if (distance < 220f) {
                            // Local many-body charge. Finite range keeps 5k nodes practical.
                            val safe = max(distance, 28f)
                            push += min(6.5f, 3600f / (safe * safe)) * alpha
                        }

                        if (push <= 0f) continue

                        val px = ux * push
                        val py = uy * push
                        if (!a.root) {
                            fx[i] -= px
                            fy[i] -= py
                        }
                        if (!b.root) {
                            fx[j] += px
                            fy[j] += py
                        }
                    }
                }
            }
        }
    }

    private fun deterministicUnitX(seed: String): Float {
        val angle = ((seed.hashCode() and 0xffff) / 65535.0) * Math.PI * 2.0
        return kotlin.math.cos(angle).toFloat()
    }

    private fun deterministicUnitY(seed: String): Float {
        val angle = ((seed.hashCode() and 0xffff) / 65535.0) * Math.PI * 2.0
        return kotlin.math.sin(angle).toFloat()
    }
}
