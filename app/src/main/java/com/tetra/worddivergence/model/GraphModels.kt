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
fun nodeLabelLines(text: String): List<String> {
    val clean = text.trim()
    if (clean.length <= 18) return listOf(clean)

    val delimiters = charArrayOf('、', '。', '！', '？', '・', ' ', '／', '/', '，', ',', '：', ':', '；', ';')
    val middle = clean.length / 2
    var best = -1
    var bestDistance = Int.MAX_VALUE
    for (i in 1 until clean.length - 1) {
        if (clean[i] in delimiters) {
            val distance = kotlin.math.abs(i - middle)
            if (distance < bestDistance) {
                best = i
                bestDistance = distance
            }
        }
    }
    if (best < 0) return listOf(clean)

    val left = clean.substring(0, best + 1).trim()
    val right = clean.substring(best + 1).trim()
    return if (left.isNotEmpty() && right.isNotEmpty()) listOf(left, right) else listOf(clean)
}

private fun estimatedTextWidthDp(text: String): Float {
    var width = 0f
    text.forEach { ch ->
        width += if (ch.code in 0x20..0x7e) 7.2f else 12.8f
    }
    return width
}

fun nodeWidthWorld(text: String, isRoot: Boolean = false): Float {
    val lineWidth = nodeLabelLines(text).maxOfOrNull { estimatedTextWidthDp(it) } ?: 0f
    return (lineWidth + if (isRoot) 30f else 24f).coerceAtLeast(if (isRoot) 62f else 48f)
}

fun nodeHeightWorld(text: String, isRoot: Boolean = false): Float {
    val lines = nodeLabelLines(text).size
    val base = if (lines == 1) 34f else 52f
    return base + if (isRoot) 4f else 0f
}

fun nodeCollisionRadiusWorld(text: String, isRoot: Boolean = false): Float {
    val halfW = nodeWidthWorld(text, isRoot) / 2f
    val halfH = nodeHeightWorld(text, isRoot) / 2f
    return kotlin.math.sqrt(halfW * halfW + halfH * halfH)
}

data class GraphNode(
    val id: String,
    val text: String,
    val parentId: String?,
    val depth: Int,
    val semanticDistance: Float,
    val parentSimilarity: Float,
    val angle: Float,
    var x: Float,
    var y: Float,
    var loading: Boolean = false,
    var expanded: Boolean = false,
    var starred: Boolean = false
) {
    fun visualWidth(): Float = nodeWidthWorld(text, parentId == null)
    fun visualHeight(): Float = nodeHeightWorld(text, parentId == null)
    fun collisionRadius(): Float = nodeCollisionRadiusWorld(text, parentId == null)
}

data class Candidate(
    val text: String,
    val semanticDistance: Float,
    val parentSimilarity: Float = 1f
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
    val minSimilarity: Float = 0.45f,
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
            val childRadius = nodeCollisionRadiusWorld(candidate.text)
            val linkLength = parent.collisionRadius() + childRadius + 92f
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
                parentSimilarity = candidate.parentSimilarity.coerceIn(-1f, 1f),
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
        put("minSimilarity", minSimilarity)
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
                    put("parentSimilarity", n.parentSimilarity)
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
                minSimilarity = o.optDouble("minSimilarity", 0.45).toFloat(),
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
                    parentSimilarity = n.optDouble("parentSimilarity", 1.0).toFloat(),
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
