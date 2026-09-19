package com.tetra.worddivergence.engine

import com.tetra.worddivergence.model.Candidate
import com.tetra.worddivergence.model.PosFilter

/**
 * Used when a real model pack exists but cannot be opened.
 *
 * We deliberately do NOT fall back to the demo engine here, because doing so
 * would make bogus "semantic" connections look like real model output.
 */
class UnavailableSemanticEngine(
    private val reason: String
) : SemanticEngine {
    override val label: String = "model error"

    private fun fail(): Nothing {
        throw IllegalStateException(
            "フル日本語モデルを開けませんでした。モデルを再ダウンロードしてください。\n$reason"
        )
    }

    override fun generateChildren(
        rootText: String,
        parentText: String,
        parentDistance: Float,
        count: Int,
        filter: PosFilter
    ): List<Candidate> = fail()

    override fun randomWords(
        seed: String?,
        count: Int,
        filter: PosFilter
    ): List<String> = fail()
}
