package com.tetra.worddivergence.engine

import android.database.sqlite.SQLiteDatabase
import android.icu.text.BreakIterator
import cloud.unum.usearch.Index
import com.tetra.worddivergence.model.Candidate
import com.tetra.worddivergence.model.PosFilter
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

class UsearchSemanticEngine(modelDir: File) : SemanticEngine {
    override val label: String = "fastText + HNSW"

    private val db = SQLiteDatabase.openDatabase(
        File(modelDir, "words.sqlite").absolutePath,
        null,
        SQLiteDatabase.OPEN_READONLY
    )
    private val index = Index.viewFromPath(File(modelDir, "vectors.usearch").absolutePath)
    private val maxId: Long = db.rawQuery(
        "SELECT value FROM meta WHERE key='max_id' LIMIT 1", null
    ).use { c -> if (c.moveToFirst()) c.getString(0).toLong() else index.size() }

    init {
        validateModelIntegrity()
    }

    private fun validateModelIntegrity() {
        check(index.dimensions() == 300L) {
            "unexpected vector dimensions: " + index.dimensions()
        }
        check(index.size() == maxId) {
            "index/word DB size mismatch: index=" + index.size() + " db=" + maxId
        }
        check(maxId > 0L) { "empty semantic index" }

        val probeId = 1L
        val probeVector = index.get(probeId)
        check(probeVector.size == 300) {
            "unexpected probe vector size: " + probeVector.size
        }

        val nearest = index.search(probeVector, 8)
        check(nearest.contains(probeId)) {
            "semantic index self-search failed; model pack may be incompatible"
        }
    }

    override fun generateChildren(
        rootText: String,
        parentText: String,
        parentDistance: Float,
        count: Int,
        filter: PosFilter
    ): List<Candidate> {
        val root = vectorForText(rootText) ?: return emptyList()
        val parent = vectorForText(parentText) ?: return emptyList()
        val wanted = count.coerceIn(1, 50)
        val step = 0.085f
        val target = (parentDistance + step).coerceAtMost(1.25f)

        // HNSW is queried around the parent because each child should remain a
        // genuine association of that parent. Root-distance progression is then
        // used as a constraint, not as the final ranking criterion.
        val keys = index.search(parent, maxOf(900, wanted * 140).toLong())
        val sentenceMode = isSentenceLike(parentText)

        data class ScoredCandidate(
            val text: String,
            val rootDistance: Float,
            val parentSimilarity: Float
        )

        val pool = ArrayList<ScoredCandidate>()

        for (key in keys) {
            val row = wordRow(key) ?: continue
            if (!filter.allows(row.second)) continue
            if (row.first == parentText || row.first == rootText) continue

            val v = runCatching { index.get(key) }.getOrNull() ?: continue
            val rootDistance = (1f - cosine(root, v)).coerceIn(0f, 2f)

            // Preserve the "walk outward" behavior: a child must be farther
            // from the root than its parent.
            if (rootDistance <= parentDistance + 0.012f) continue

            val generated = if (sentenceMode) {
                replaceOneKnownToken(parentText, row.first)
            } else {
                row.first
            }
            if (generated == parentText) continue

            pool += ScoredCandidate(
                text = generated,
                rootDistance = rootDistance,
                parentSimilarity = cosine(parent, v)
            )
        }

        val unique = pool
            .distinctBy { it.text }

        if (unique.isEmpty()) return emptyList()

        // First keep candidates near the desired outward step. Within that band,
        // choose the words most semantically related to the actual parent.
        // If the band is too sparse, widen it deterministically until enough
        // candidates are available.
        val halfBands = floatArrayOf(
            step * 0.50f,
            step * 0.85f,
            step * 1.25f,
            step * 1.75f,
            step * 2.50f,
            Float.POSITIVE_INFINITY
        )

        var eligible: List<ScoredCandidate> = emptyList()
        for (halfBand in halfBands) {
            eligible = unique.filter { candidate ->
                abs(candidate.rootDistance - target) <= halfBand
            }
            if (eligible.size >= wanted || halfBand.isInfinite()) break
        }

        return eligible
            .sortedWith(
                compareByDescending<ScoredCandidate> { it.parentSimilarity }
                    .thenBy { abs(it.rootDistance - target) }
                    .thenBy { it.text }
            )
            .take(wanted)
            .map { Candidate(it.text, it.rootDistance) }
    }

    override fun randomWords(seed: String?, count: Int, filter: PosFilter): List<String> {
        val wanted = count.coerceIn(1, 1000)
        if (maxId <= 0) return emptyList()
        val random = Random.Default

        if (seed.isNullOrBlank()) {
            val out = LinkedHashSet<String>()
            var guard = 0
            while (out.size < wanted && guard++ < wanted * 30) {
                val id = random.nextLong(1L, maxId + 1L)
                val row = wordRow(id) ?: continue
                if (filter.allows(row.second)) out += row.first
            }
            return out.toList()
        }

        val seedVector = vectorForText(seed) ?: return randomWords(null, wanted, filter)
        val sampleCount = maxOf(1800, wanted * 90).coerceAtMost(12000)
        val scored = ArrayList<Pair<String, Float>>(sampleCount)
        repeat(sampleCount) {
            val id = random.nextLong(1L, maxId + 1L)
            val row = wordRow(id) ?: return@repeat
            if (!filter.allows(row.second) || row.first == seed) return@repeat
            val v = runCatching { index.get(id) }.getOrNull() ?: return@repeat
            scored += row.first to abs(cosine(seedVector, v))
        }
        return scored.sortedBy { it.second }.distinctBy { it.first }.take(wanted).map { it.first }
    }

    private fun vectorForText(text: String): FloatArray? {
        wordId(text)?.let { return runCatching { index.get(it) }.getOrNull() }
        val tokens = tokenize(text)
        val vectors = tokens.mapNotNull { t -> wordId(t)?.let { id -> runCatching { index.get(id) }.getOrNull() } }
        if (vectors.isEmpty()) return null
        val dims = vectors.first().size
        val avg = FloatArray(dims)
        for (v in vectors) for (i in 0 until dims) avg[i] += v[i]
        for (i in avg.indices) avg[i] /= vectors.size.toFloat()
        return avg
    }

    private fun tokenize(text: String): List<String> {
        val breaker = BreakIterator.getWordInstance(Locale.JAPANESE)
        breaker.setText(text)
        val out = ArrayList<String>()
        var start = breaker.first()
        var end = breaker.next()
        while (end != BreakIterator.DONE) {
            val s = text.substring(start, end).trim()
            if (s.isNotEmpty() && s.any { it.isLetterOrDigit() }) out += s
            start = end
            end = breaker.next()
        }
        return out
    }

    private fun isSentenceLike(text: String): Boolean =
        text.length >= 5 && tokenize(text).size >= 2

    private fun replaceOneKnownToken(text: String, replacement: String): String {
        val tokens = tokenize(text).filter { wordId(it) != null }
        if (tokens.isEmpty()) return replacement
        val token = tokens.maxByOrNull { it.length } ?: return replacement
        val at = text.indexOf(token)
        if (at < 0) return replacement
        return text.substring(0, at) + replacement + text.substring(at + token.length)
    }

    private fun wordId(word: String): Long? =
        db.rawQuery("SELECT id FROM words WHERE word=? LIMIT 1", arrayOf(word)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }

    private fun wordRow(id: Long): Pair<String, String>? =
        db.rawQuery("SELECT word,pos FROM words WHERE id=? LIMIT 1", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.getString(0) to c.getString(1) else null
        }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var dot = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in 0 until n) {
            dot += a[i] * b[i]
            aa += a[i] * a[i]
            bb += b[i] * b[i]
        }
        if (aa == 0.0 || bb == 0.0) return 0f
        return (dot / (sqrt(aa) * sqrt(bb))).toFloat()
    }

    override fun close() {
        index.close()
        db.close()
    }
}
