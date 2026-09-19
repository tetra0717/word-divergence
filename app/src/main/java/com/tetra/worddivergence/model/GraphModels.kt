package com.tetra.worddivergence.model

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
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
        enabled.any { it.dbValue == dbValue } || (dbValue == "unknown" && PosCategory.OTHER in enabled)
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
)

data class Candidate(
    val text: String,
    val semanticDistance: Float
)

data class CameraState(
    var centerX: Float = 0f,
    var centerY: Float = 0f,
    var scale: Float = 1f
)

data class GraphSession(
    val id: String,
    var title: String,
    val rootText: String,
    val branchCount: Int,
    val posFilter: PosFilter,
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
        val spread = if (parent.parentId == null) (2f * PI.toFloat()) else (PI.toFloat() * 0.78f)
        val out = ArrayList<GraphNode>(count)
        candidates.forEachIndexed { index, c ->
            val angle = if (parent.parentId == null) {
                (2f * PI.toFloat() * index / count)
            } else {
                val t = if (count == 1) 0f else (index.toFloat() / (count - 1) - 0.5f)
                baseAngle + t * spread
            }
            val normalized = c.semanticDistance.coerceIn(0.02f, 1.35f)
            val radius = 150f + normalized * 1800f
            val node = GraphNode(
                id = parent.id + "." + index + "." + System.nanoTime(),
                text = c.text,
                parentId = parent.id,
                depth = parent.depth + 1,
                semanticDistance = normalized,
                angle = angle,
                x = cos(angle) * radius,
                y = sin(angle) * radius
            )
            nodes[node.id] = node
            out += node
        }
        parent.expanded = true
        parent.loading = false
        updatedAt = System.currentTimeMillis()
        return out
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
            put("x", camera.centerX); put("y", camera.centerY); put("scale", camera.scale)
        })
        put("nodes", JSONArray().apply {
            nodes.values.forEach { n ->
                put(JSONObject().apply {
                    put("id", n.id); put("text", n.text); put("parentId", n.parentId)
                    put("depth", n.depth); put("semanticDistance", n.semanticDistance)
                    put("angle", n.angle); put("x", n.x); put("y", n.y)
                    put("loading", false); put("expanded", n.expanded); put("starred", n.starred)
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
                posFilter = PosFilter(if (enabled.isEmpty()) setOf(PosCategory.NOUN) else enabled),
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
