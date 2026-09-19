package com.tetra.worddivergence.model

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

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

/** Visual radius in graph-world dp units. */
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
    var x: Float,
    var y: Float,
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
    var updatedAt: Long = System.currentTimeMillis(),
    var layoutVersion: Int = 2
) {
    fun root(): GraphNode = nodes.values.first { it.parentId == null }

    /**
     * New nodes appear near their parent. Final positions are produced by the
     * deterministic force-layout pass; semantic distance is not used as geometry.
     */
    fun addChildren(parent: GraphNode, candidates: List<Candidate>): List<GraphNode> {
        if (candidates.isEmpty()) {
            parent.expanded = true
            parent.loading = false
            return emptyList()
        }

        val count = candidates.size
        val outward = if (parent.parentId == null) {
            0f
        } else {
            atan2(parent.y, parent.x)
        }
        val out = ArrayList<GraphNode>(count)

        candidates.forEachIndexed { index, candidate ->
            val childRadius = nodeRadiusWorld(candidate.text)
            val linkLength = parent.visualRadius() + childRadius + 125f
            val angle = if (parent.parentId == null) {
                2f * PI.toFloat() * index / count
            } else {
                val t = if (count == 1) 0f else index.toFloat() / (count - 1) - 0.5f
                val deterministicJitter =
                    ((candidate.text.hashCode() and 0xffff) / 65535f - 0.5f) * 0.12f
                outward + t * 1.35f + deterministicJitter
            }

            val node = GraphNode(
                id = parent.id + "." + index + "." + System.nanoTime(),
                text = candidate.text,
                parentId = parent.id,
                depth = parent.depth + 1,
                semanticDistance = candidate.semanticDistance.coerceIn(0f, 2f),
                angle = angle,
                x = parent.x + cos(angle) * linkLength,
                y = parent.y + sin(angle) * linkLength
            )
            nodes[node.id] = node
            out += node
        }

        parent.expanded = true
        parent.loading = false
        updatedAt = System.currentTimeMillis()
        layoutVersion = 2
        return out
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("rootText", rootText)
        put("branchCount", branchCount)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("layoutVersion", layoutVersion)
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
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                layoutVersion = o.optInt("layoutVersion", 1)
            )

            val cam = o.optJSONObject("camera")
            if (cam != null) {
                s.camera.centerX = cam.optDouble("x", 0.0).toFloat()
                s.camera.centerY = cam.optDouble("y", 0.0).toFloat()
                s.camera.scale = cam.optDouble("scale", 0.86).toFloat()
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
                    angle = n.optDouble("angle", 0.0).toFloat(),
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
