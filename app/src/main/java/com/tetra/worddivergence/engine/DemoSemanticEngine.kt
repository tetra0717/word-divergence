package com.tetra.worddivergence.engine

import com.tetra.worddivergence.model.Candidate
import com.tetra.worddivergence.model.PosFilter
import kotlin.math.abs
import kotlin.random.Random

class DemoSemanticEngine : SemanticEngine {
    override val label: String = "デモ辞書"

    private val words = listOf(
        "海","波","船","港","航路","税関","旅券","写真","履歴書","面接","工場","旋盤","半導体",
        "砂浜","深海","潮流","灯台","漁業","市場","帳簿","議事録","麻酔","編み物","戸籍","電池",
        "学校","校舎","黒板","廊下","図書館","病院","倉庫","地下室","雨","夜","静寂","記憶",
        "監視","通信","電波","衛星","鉱石","農場","楽器","時計","裁判","保険","彫刻","発酵",
        "惑星","昆虫","温室","鉄道","印刷","陶器","階段","窓","標本","地図","遺跡","書庫"
    )

    override fun generateChildren(
        rootText: String,
        parentText: String,
        parentDistance: Float,
        count: Int,
        filter: PosFilter
    ): List<Candidate> {
        val r = Random(rootText.hashCode() * 31 + parentText.hashCode())
        val target = (parentDistance + 0.10f).coerceAtMost(1.15f)
        return words
            .asSequence()
            .filter { it != parentText && it != rootText }
            .map { w ->
                val noise = (abs((w.hashCode() xor parentText.hashCode()) % 1000) / 1000f)
                val dist = (target + (noise - 0.5f) * 0.16f).coerceIn(parentDistance + 0.025f, 1.3f)
                w to dist
            }
            .sortedBy { abs(it.second - target) + r.nextFloat() * 0.08f }
            .take(count)
            .map { Candidate(it.first, it.second) }
            .toList()
    }

    override fun randomWords(seed: String?, count: Int, filter: PosFilter): List<String> {
        val r = Random((seed ?: System.nanoTime().toString()).hashCode())
        return words.shuffled(r).filter { seed.isNullOrBlank() || it != seed }.take(count)
    }
}
