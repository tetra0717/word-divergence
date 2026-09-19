package com.tetra.worddivergence.engine

import com.tetra.worddivergence.model.Candidate
import com.tetra.worddivergence.model.PosFilter

interface SemanticEngine : AutoCloseable {
    val label: String
    fun generateChildren(
        rootText: String,
        parentText: String,
        parentDistance: Float,
        count: Int,
        filter: PosFilter
    ): List<Candidate>

    fun randomWords(seed: String?, count: Int, filter: PosFilter): List<String>

    override fun close() {}
}
