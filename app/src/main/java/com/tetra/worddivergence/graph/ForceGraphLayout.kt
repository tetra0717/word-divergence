package com.tetra.worddivergence.graph

import com.tetra.worddivergence.model.GraphNode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Obsidian-inspired force layout adapted to this app's tree-like graph.
 *
 * Core forces:
 * - link spring
 * - node repulsion
 * - center force
 * - collision
 *
 * Tree-specific additions solve a problem Obsidian's arbitrary graph does not
 * need to guarantee: generated descendants must not fold back through the root
 * and tangle unrelated root branches.
 *
 * Semantic distance is NEVER used as geometry.
 */
object ForceGraphLayout {

    data class Position(val x: Float, val y: Float)

    private data class SimNode(
        val id: String,
        val parentId: String?,
        val depth: Int,
        val preferredAngle: Float,
        val radius: Float,
        val root: Boolean,
        var branchAngle: Float = 0f,
        var branchHalfSpan: Float = PI.toFloat(),
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
                depth = it.depth,
                preferredAngle = it.angle,
                radius = it.collisionRadius(),
                root = it.parentId == null,
                x = it.x,
                y = it.y
            )
        }

        val byId = sim.withIndex().associate { it.value.id to it.index }
        assignRootBranchSectors(sim, byId)

        val iterations = when {
            sim.size < 250 -> 78
            sim.size < 900 -> 58
            sim.size < 2200 -> 40
            else -> 28
        }

        repeat(iterations) { iteration ->
            val t = iteration.toFloat() / max(1, iterations - 1)
            val alpha = 0.92f * (1f - t) * (1f - t) + 0.05f
            val fx = FloatArray(sim.size)
            val fy = FloatArray(sim.size)

            applyLinkForce(sim, byId, fx, fy, alpha)
            applyChargeAndSoftCollision(sim, fx, fy, alpha)
            applyBranchTopologyForce(sim, byId, fx, fy, alpha)

            for (i in sim.indices) {
                val node = sim[i]
                if (node.root) {
                    node.x = 0f
                    node.y = 0f
                    node.vx = 0f
                    node.vy = 0f
                    continue
                }

                // Obsidian-like center force, but weaker for deep descendants.
                // This keeps the whole graph centered without folding long
                // branches back through the root.
                val centerStrength = 0.00115f / (1f + node.depth * 1.65f)
                fx[i] += -node.x * centerStrength * alpha
                fy[i] += -node.y * centerStrength * alpha

                node.vx = (node.vx + fx[i]).coerceIn(-34f, 34f) * 0.72f
                node.vy = (node.vy + fy[i]).coerceIn(-34f, 34f) * 0.72f
                node.x += node.vx
                node.y += node.vy
            }
        }

        resolveHardCollisions(sim)
        enforceOutwardTopology(sim, byId)

        return sim.associate { it.id to Position(it.x, it.y) }
    }

    private fun assignRootBranchSectors(
        nodes: List<SimNode>,
        byId: Map<String, Int>
    ) {
        val root = nodes.firstOrNull { it.root } ?: return
        val rootChildren = nodes
            .filter { it.parentId == root.id }
            .sortedBy { normalizeAnglePositive(it.preferredAngle) }

        if (rootChildren.isEmpty()) return

        val sector = (2f * PI.toFloat()) / rootChildren.size
        val halfSpan = sector * 0.43f
        val branchAngles = HashMap<String, Float>()

        rootChildren.forEachIndexed { index, child ->
            val currentRadius = hypot(child.x, child.y)
            val initial = if (currentRadius > 1f) {
                atan2(child.y, child.x)
            } else {
                2f * PI.toFloat() * index / rootChildren.size
            }
            branchAngles[child.id] = initial
        }

        fun topBranchId(node: SimNode): String? {
            var current = node
            var guard = 0
            while (guard++ < 10000) {
                val parentId = current.parentId ?: return null
                if (parentId == root.id) return current.id
                val parentIndex = byId[parentId] ?: return null
                current = nodes[parentIndex]
            }
            return null
        }

        for (node in nodes) {
            if (node.root) continue
            val top = topBranchId(node) ?: continue
            node.branchAngle = branchAngles[top] ?: node.preferredAngle
            node.branchHalfSpan = halfSpan
        }
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

            val desired = parent.radius + child.radius + 92f
            val spring = (distance - desired) * 0.047f * alpha
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

            // Preserve the link's generated direction gently. This reduces
            // crossings without making the graph rigid or grid-like.
            val targetX = parent.x + cos(child.preferredAngle) * desired
            val targetY = parent.y + sin(child.preferredAngle) * desired
            val directionalStrength = 0.010f * alpha
            fx[i] += (targetX - child.x) * directionalStrength
            fy[i] += (targetY - child.y) * directionalStrength
        }
    }

    private fun applyBranchTopologyForce(
        nodes: List<SimNode>,
        byId: Map<String, Int>,
        fx: FloatArray,
        fy: FloatArray,
        alpha: Float
    ) {
        for (i in nodes.indices) {
            val node = nodes[i]
            if (node.root) continue

            val parentIndex = node.parentId?.let { byId[it] }
            val parent = parentIndex?.let { nodes[it] }

            // A descendant should not drift substantially closer to the root
            // than its parent. This is topology-only, not semantic distance.
            if (parent != null && !parent.root) {
                val parentRadius = hypot(parent.x, parent.y)
                val nodeRadius = hypot(node.x, node.y)
                val minimumRadius = parentRadius + 42f

                if (nodeRadius < minimumRadius) {
                    val push = (minimumRadius - nodeRadius) * 0.035f * alpha
                    val angle = if (nodeRadius > 1f) atan2(node.y, node.x) else node.branchAngle
                    fx[i] += cos(angle) * push
                    fy[i] += sin(angle) * push
                }
            }

            // Keep each root subtree in its own broad fan. Force layout is free
            // inside the fan, but different root branches no longer fold through
            // the center and cross one another.
            val radius = hypot(node.x, node.y)
            if (radius > 1f) {
                val angle = atan2(node.y, node.x)
                val delta = normalizeAngle(angle - node.branchAngle)
                val outside = abs(delta) - node.branchHalfSpan

                if (outside > 0f) {
                    val boundary = node.branchAngle +
                        if (delta > 0f) node.branchHalfSpan else -node.branchHalfSpan
                    val targetX = cos(boundary) * radius
                    val targetY = sin(boundary) * radius
                    val strength = 0.020f * alpha
                    fx[i] += (targetX - node.x) * strength
                    fy[i] += (targetY - node.y) * strength
                } else {
                    // Very weak branch cohesion; enough to keep a clean visual
                    // family resemblance without collapsing the branch to a ray.
                    val targetX = cos(node.branchAngle) * radius
                    val targetY = sin(node.branchAngle) * radius
                    val strength = 0.0018f * alpha
                    fx[i] += (targetX - node.x) * strength
                    fy[i] += (targetY - node.y) * strength
                }
            }
        }
    }

    private fun applyChargeAndSoftCollision(
        nodes: List<SimNode>,
        fx: FloatArray,
        fy: FloatArray,
        alpha: Float
    ) {
        val cellSize = 440f
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
                        val minDistance = a.radius + b.radius + 20f

                        var push = 0f
                        if (distance < minDistance) {
                            push += (minDistance - distance) * 0.62f * alpha
                        }
                        if (distance < 420f) {
                            val safe = max(distance, 34f)
                            push += min(16f, 21000f / (safe * safe)) * alpha
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
        val cellSize = 340f

        repeat(34) {
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

    private fun enforceOutwardTopology(
        nodes: List<SimNode>,
        byId: Map<String, Int>
    ) {
        // Final deterministic pass after collision resolution. This prevents
        // collisions from pushing a deep child back across its parent toward
        // the root. We iterate in depth order so parents are established first.
        nodes.sortedBy { it.depth }.forEach { node ->
            if (node.root) return@forEach
            val parentIndex = node.parentId?.let { byId[it] } ?: return@forEach
            val parent = nodes[parentIndex]
            if (parent.root) return@forEach

            val parentRadius = hypot(parent.x, parent.y)
            val nodeRadius = hypot(node.x, node.y)
            val minimumRadius = parentRadius + 30f
            if (nodeRadius >= minimumRadius) return@forEach

            val angle = if (nodeRadius > 1f) atan2(node.y, node.x) else node.branchAngle
            node.x = cos(angle) * minimumRadius
            node.y = sin(angle) * minimumRadius
        }

        // Outward correction can create a rare local overlap, so resolve once
        // more with the same fixed deterministic collision solver.
        resolveHardCollisions(nodes)
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

    private fun normalizeAngle(value: Float): Float {
        var angle = value
        while (angle > PI) angle -= (2.0 * PI).toFloat()
        while (angle < -PI) angle += (2.0 * PI).toFloat()
        return angle
    }

    private fun normalizeAnglePositive(value: Float): Float {
        var angle = value
        val full = (2.0 * PI).toFloat()
        while (angle < 0f) angle += full
        while (angle >= full) angle -= full
        return angle
    }

    private fun deterministicUnitX(seed: String): Float {
        val angle = ((seed.hashCode() and 0xffff) / 65535.0) * PI * 2.0
        return cos(angle).toFloat()
    }

    private fun deterministicUnitY(seed: String): Float {
        val angle = ((seed.hashCode() and 0xffff) / 65535.0) * PI * 2.0
        return sin(angle).toFloat()
    }
}
