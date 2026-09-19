package com.tetra.worddivergence.model

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class PosCategory(val dbValue: String, val label: String) {
    NOUN("noun", "名詞"),
    VERB("verb", "動詞"),
    ADJECTIVE("adjective", "形容詞"),
    ADVERB("adverb", "副詞"),
    PROPER("proper", "固有名詞"),
    OTHER("other", "その他")
}

data class PosFilter(
    val enabled: Set<PosCategory> = setOf(
        PosCategory.NOUN,
        PosCategory.VERB,
        PosCategory.ADJECTIVE
    )
) {
    fun allows(dbValue: String): Boolean =
        enabled.any { it.dbValue == dbValue } ||
            (dbValue == "unknown" && PosCategory.OTHER in enabled)
}

/**
 * Visual size in graph-world dp units.
 *
 * Short words stay compact and circular. Longer phrases get a larger circle
 * with wrapped text. The size is deterministic from the label, so layout and
 * hit-testing use exactly the same geometry.
 */
fun nodeRadiusWorld(text: String, isRoot: Boolean = false): Float {
    val length = text.codePointCount(0, text.length)
    val base = when {
        length <= 2 -> 30f
        length <= 4 -> 34f
        length <= 7 -> 40f
        length <= 11 -> 48f
        length <= 17 -> 57f
        length <= 25 -> 66f
        else -> 76f
    }
    return base + if (isRoot) 5f else 0f
}

data class GraphNode(
    val id: String,
    val text: String,
    val parentId: String?,
    val depth: Int,
    val semanticDistance: Float,
    val angle: Float,
    val x: Float,
    val y: Float,
    var loading: Boolean = false,
    var expanded: Boolean = false,
    var starred: Boolean = false
) {
    fun visualRadius(): Float = nodeRadiusWorld(text, parentId == null)
}

data class Candidate(
    val text: String,
    val semanticDistance: Float
)

data class CameraState(
    var centerX: Float = 0f,
    var centerY: Float = 0f,
    var scale: Float = 0.86f
)

data class GraphSession(
    val id: String,
    var title: String,
    val rootText: String,
    val branchCount: Int,
    var posFilter: PosFilter,
    val nodes: LinkedHashMap<String, GraphNode> = linkedMapOf(),
    val camera: CameraState = CameraState(),
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
) {
    fun root(): GraphNode = nodes.values.first { it.parentId == null }

    fun addChildren(parent: GraphNode, candidates: List<Candidate>): List<GraphNode> {
        if (candidates.isEmpty()) {
            parent.expanded = true
            parent.loading = false
            return emptyList()
        }

        val count = candidates.size
        val baseAngle = if (parent.parentId == null) 0f else parent.angle
        val rootSector = (2f * PI.toFloat()) / branchCount.coerceAtLeast(1)
        val spread = if (parent.parentId == null) {
            2f * PI.toFloat()
        } else {
            rootSector * 0.88f / sqrt(parent.depth.coerceAtLeast(1).toFloat())
        }

        val out = ArrayList<GraphNode>(count)
        candidates.forEachIndexed { index, candidate ->
            val normalized = candidate.semanticDistance.coerceIn(0.02f, 1.35f)
            // Larger semantic canvas than the prototype. Radius remains monotonic
            // with root semantic distance while providing room for readable nodes.
            val semanticRadius = 90f + normalized * 1150f

            val desiredAngle = if (parent.parentId == null) {
                2f * PI.toFloat() * index / count
            } else {
                val t = if (count == 1) 0f else index.toFloat() / (count - 1) - 0.5f
                baseAngle + t * spread
            }

            val visualRadius = nodeRadiusWorld(candidate.text)
            val placement = findOpenPlacement(
                desiredAngle = desiredAngle,
                semanticRadius = semanticRadius,
                nodeRadius = visualRadius,
                sectorSpan = if (parent.parentId == null) rootSector else spread
            )

            val node = GraphNode(
                id = parent.id + "." + index + "." + System.nanoTime(),
                text = candidate.text,
                parentId = parent.id,
                depth = parent.depth + 1,
                semanticDistance = normalized,
                angle = placement.first,
                x = cos(placement.first) * placement.second,
                y = sin(placement.first) * placement.second
            )
            nodes[node.id] = node
            out += node
        }

        parent.expanded = true
        parent.loading = false
        updatedAt = System.currentTimeMillis()
        return out
    }

    /**
     * Collision-free placement is deterministic and one-shot: old nodes never
     * move after insertion. We search angle first, preserving semantic radius.
     * Only if a ring is completely saturated do we add the minimum outward
     * visual offset needed to fit; the semanticDistance value itself is unchanged.
     */
    private fun findOpenPlacement(
        desiredAngle: Float,
        semanticRadius: Float,
        nodeRadius: Float,
        sectorSpan: Float
    ): Pair<Float, Float> {
        val margin = 14f
        val angularStep = (PI.toFloat() / 90f).coerceAtMost(sectorSpan / 18f)
        val maxAngularSteps = 28
        val radialSteps = floatArrayOf(0f, 18f, 36f, 56f, 80f, 108f, 140f)

        for (radialOffset in radialSteps) {
            val radius = semanticRadius + radialOffset
            for (step in 0..maxAngularSteps) {
                val signed = when {
                    step == 0 -> 0
                    step % 2 == 1 -> (step + 1) / 2
                    else -> -(step / 2)
                }
                val angle = desiredAngle + signed * angularStep
                val x = cos(angle) * radius
                val y = sin(angle) * radius
                if (isOpen(x, y, nodeRadius, margin)) return angle to radius
            }
        }

        // Dense worst-case fallback: deterministic outward position instead of
        // overlapping existing nodes or moving the whole graph.
        val fallbackRadius = semanticRadius + 180f
        return desiredAngle to fallbackRadius
    }

    private fun isOpen(x: Float, y: Float, radius: Float, margin: Float): Boolean {
        for (other in nodes.values) {
            val dx = other.x - x
            val dy = other.y - y
            val minDistance = other.visualRadius() + radius + margin
            if (dx * dx + dy * dy < minDistance * minDistance) return false
        }
        return true
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("rootText", rootText)
        put("branchCount", branchCount)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("enabledPos", JSONArray(posFilter.enabled.map { it.name }))
        put("camera", JSONObject().apply {
            put("x", camera.centerX)
            put("y", camera.centerY)
            put("scale", camera.scale)
        })
        put("nodes", JSONArray().apply {
            nodes.values.forEach { n ->
                put(JSONObject().apply {
                    put("id", n.id)
                    put("text", n.text)
                    put("parentId", n.parentId)
                    put("depth", n.depth)
                    put("semanticDistance", n.semanticDistance)
                    put("angle", n.angle)
                    put("x", n.x)
                    put("y", n.y)
                    put("loading", false)
                    put("expanded", n.expanded)
                    put("starred", n.starred)
                })
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): GraphSession {
            val enabled = mutableSetOf<PosCategory>()
            val p = o.optJSONArray("enabledPos") ?: JSONArray()
            for (i in 0 until p.length()) {
                runCatching { enabled += PosCategory.valueOf(p.getString(i)) }
            }
            val s = GraphSession(
                id = o.getString("id"),
                title = o.getString("title"),
                rootText = o.getString("rootText"),
                branchCount = o.getInt("branchCount"),
                posFilter = PosFilter(
                    if (enabled.isEmpty()) setOf(PosCategory.NOUN) else enabled
                ),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
            )
            val cam = o.optJSONObject("camera")
            if (cam != null) {
                s.camera.centerX = cam.optDouble("x", 0.0).toFloat()
                s.camera.centerY = cam.optDouble("y", 0.0).toFloat()
                s.camera.scale = cam.optDouble("scale", 1.0).toFloat()
            }
            val a = o.getJSONArray("nodes")
            for (i in 0 until a.length()) {
                val n = a.getJSONObject(i)
                val parentId = if (n.isNull("parentId")) null else n.optString("parentId", null)
                s.nodes[n.getString("id")] = GraphNode(
                    id = n.getString("id"),
                    text = n.getString("text"),
                    parentId = parentId,
                    depth = n.getInt("depth"),
                    semanticDistance = n.getDouble("semanticDistance").toFloat(),
                    angle = n.getDouble("angle").toFloat(),
                    x = n.getDouble("x").toFloat(),
                    y = n.getDouble("y").toFloat(),
                    loading = false,
                    expanded = n.optBoolean("expanded", false),
                    starred = n.optBoolean("starred", false)
                )
            }
            return s
        }
    }
}
