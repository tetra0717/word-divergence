package com.tetra.worddivergence.graph

import com.tetra.worddivergence.model.GraphNode
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Deterministic force-directed layout:
 * - link springs keep parent/child relationships readable
 * - local many-body charge opens space organically
 * - an explicit final collision solver guarantees visible bubbles do not overlap
 *
 * Semantic distance is NOT encoded into geometry.
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
            sim.size < 250 -> 70
            sim.size < 900 -> 52
            sim.size < 2200 -> 34
            else -> 24
        }

        repeat(iterations) { iteration ->
            val t = iteration.toFloat() / max(1, iterations - 1)
            val alpha = 0.90f * (1f - t) * (1f - t) + 0.05f
            val fx = FloatArray(sim.size)
            val fy = FloatArray(sim.size)

            applyLinkForce(sim, byId, fx, fy, alpha)
            applyChargeAndSoftCollision(sim, fx, fy, alpha)

            for (i in sim.indices) {
                val node = sim[i]
                if (node.root) {
                    node.x = 0f
                    node.y = 0f
                    node.vx = 0f
                    node.vy = 0f
                    continue
                }

                node.vx = (node.vx + fx[i]).coerceIn(-32f, 32f) * 0.72f
                node.vy = (node.vy + fy[i]).coerceIn(-32f, 32f) * 0.72f
                node.x += node.vx
                node.y += node.vy
            }
        }

        // A fixed number of deterministic relaxation passes, not a heuristic
        // "freeze when settled" rule. This protects readability at high density.
        resolveHardCollisions(sim)

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
            val parentIndex = byId[parentId] ?: continue
            val parent = nodes[parentIndex]

            var dx = child.x - parent.x
            var dy = child.y - parent.y
            var distance = hypot(dx, dy)
            if (distance < 0.001f) {
                dx = deterministicUnitX(child.id)
                dy = deterministicUnitY(child.id)
                distance = 1f
            }

            val desired = parent.radius + child.radius + 125f
            val spring = (distance - desired) * 0.045f * alpha
            val ux = dx / distance
            val uy = dy / distance
            val sx = ux * spring
            val sy = uy * spring

            fx[i] -= sx
            fy[i] -= sy
            if (!parent.root) {
                fx[parentIndex] += sx
                fy[parentIndex] += sy
            }
        }
    }

    private fun applyChargeAndSoftCollision(
        nodes: List<SimNode>,
        fx: FloatArray,
        fy: FloatArray,
        alpha: Float
    ) {
        val cellSize = 320f
        val grid = buildGrid(nodes, cellSize)

        for (i in nodes.indices) {
            val a = nodes[i]
            val cx = cell(a.x, cellSize)
            val cy = cell(a.y, cellSize)

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
                        val minDistance = a.radius + b.radius + 22f

                        var push = 0f
                        if (distance < minDistance) {
                            push += (minDistance - distance) * 0.60f * alpha
                        }
                        if (distance < 300f) {
                            val safe = max(distance, 35f)
                            push += min(12f, 12000f / (safe * safe)) * alpha
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

    private fun resolveHardCollisions(nodes: List<SimNode>) {
        val cellSize = 260f

        repeat(30) {
            val grid = buildGrid(nodes, cellSize)
            var maximumOverlap = 0f

            for (i in nodes.indices) {
                val a = nodes[i]
                val cx = cell(a.x, cellSize)
                val cy = cell(a.y, cellSize)

                for (gx in (cx - 1)..(cx + 1)) {
                    for (gy in (cy - 1)..(cy + 1)) {
                        val bucket = grid[key(gx, gy)] ?: continue
                        for (j in bucket) {
                            if (j <= i) continue
                            val b = nodes[j]

                            var dx = b.x - a.x
                            var dy = b.y - a.y
                            var distance = hypot(dx, dy)
                            val required = a.radius + b.radius + 18f
                            if (distance >= required) continue

                            if (distance < 0.001f) {
                                dx = deterministicUnitX(a.id + b.id)
                                dy = deterministicUnitY(a.id + b.id)
                                distance = 1f
                            }

                            val overlap = required - distance
                            maximumOverlap = max(maximumOverlap, overlap)
                            val ux = dx / distance
                            val uy = dy / distance

                            when {
                                a.root -> {
                                    b.x += ux * overlap
                                    b.y += uy * overlap
                                }
                                b.root -> {
                                    a.x -= ux * overlap
                                    a.y -= uy * overlap
                                }
                                else -> {
                                    val shift = overlap * 0.55f
                                    a.x -= ux * shift
                                    a.y -= uy * shift
                                    b.x += ux * shift
                                    b.y += uy * shift
                                }
                            }
                        }
                    }
                }
            }

            if (maximumOverlap < 0.5f) return
        }
    }

    private fun buildGrid(
        nodes: List<SimNode>,
        cellSize: Float
    ): HashMap<Long, MutableList<Int>> {
        val grid = HashMap<Long, MutableList<Int>>()
        for (i in nodes.indices) {
            val n = nodes[i]
            grid.getOrPut(key(cell(n.x, cellSize), cell(n.y, cellSize))) {
                ArrayList()
            }.add(i)
        }
        return grid
    }

    private fun cell(value: Float, cellSize: Float): Int =
        floor(value / cellSize).toInt()

    private fun key(x: Int, y: Int): Long =
        (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)

    private fun deterministicUnitX(seed: String): Float {
        val angle = ((seed.hashCode() and 0xffff) / 65535.0) * Math.PI * 2.0
        return kotlin.math.cos(angle).toFloat()
    }

    private fun deterministicUnitY(seed: String): Float {
        val angle = ((seed.hashCode() and 0xffff) / 65535.0) * Math.PI * 2.0
        return kotlin.math.sin(angle).toFloat()
    }
}
