package com.tetra.worddivergence.data

import android.content.Context
import com.tetra.worddivergence.model.GraphSession
import org.json.JSONArray

class GraphStore(context: Context) {
    private val prefs = context.getSharedPreferences("word_divergence_graphs", Context.MODE_PRIVATE)

    fun save(session: GraphSession) {
        val all = loadAll().associateBy { it.id }.toMutableMap()
        all[session.id] = session
        val sorted = all.values.sortedByDescending { it.updatedAt }.take(60)
        prefs.edit().putString("sessions", JSONArray(sorted.map { it.toJson() }).toString()).apply()
    }

    fun loadAll(): List<GraphSession> {
        val raw = prefs.getString("sessions", null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { GraphSession.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun starred(): List<GraphSession> = loadAll().filter { s -> s.nodes.values.any { it.starred } }
}
