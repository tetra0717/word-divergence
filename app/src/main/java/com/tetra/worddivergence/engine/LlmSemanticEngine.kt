package com.tetra.worddivergence.engine

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.tetra.worddivergence.model.Candidate
import com.tetra.worddivergence.model.PosFilter
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import java.io.File

class LlmSemanticEngine(
    context: Context,
    private val modelFile: File
) : SemanticEngine {
    override val label: String = "Qwen3-1.7B • local"

    private val engine: InferenceEngine = AiChat.getInferenceEngine(context.applicationContext)
    private val lock = Any()
    @Volatile private var loaded = false

    override fun generateChildren(
        rootText: String,
        parentText: String,
        parentDistance: Float,
        count: Int,
        minSimilarity: Float,
        filter: PosFilter
    ): List<Candidate> = synchronized(lock) {
        ensureLoaded()
        val maxCount = count.coerceIn(1, 20)
        val prompt = buildString {
            appendLine("/no_think")
            appendLine("これは独立した1回のブレインストーミング要求です。以前の会話は無視してください。")
            appendLine("ルート概念: 「" + rootText + "」")
            appendLine("今回タップされた親ノード: 「" + parentText + "」")
            appendLine("この親ノードから、日本語話者が自然に1ステップで直接連想できる語句だけを最大" + maxCount + "件返してください。")
            appendLine("無理に件数を埋めないでください。少しでも関係が弱い、語の断片、固有名詞の一部分、文字列共起だけの候補、説明文は出さないでください。")
            appendLine("同義語ばかりにせず、場所・用途・構成・原因・結果・行為・対象など関係の種類を分散してください。")
            appendLine("候補は単独で意味が通る自然な日本語語句にしてください。")
            appendLine("各候補について、親→候補の関係を短い日本語で relation に入れてください。")
            appendLine("出力前に各候補を内部で再検査し、不自然なものは削除してください。")
            appendLine("出力はJSON配列だけ。Markdownや説明は禁止。")
            appendLine("""形式: [{"word":"船","relation":"移動手段"}]""")
        }
        parseCandidates(generate(prompt, 320), maxCount, parentText, rootText)
    }

    override fun randomWords(
        seed: String?,
        count: Int,
        filter: PosFilter
    ): List<String> = synchronized(lock) {
        ensureLoaded()
        val maxCount = count.coerceIn(1, 100)
        val prompt = if (seed.isNullOrBlank()) {
            "/no_think\n" +
                "日本語の単語または短い語句を、意味分野が互いにできるだけ離れるように最大" + maxCount + "件挙げてください。\n" +
                "固有名詞の断片や意味不明な文字列は禁止です。\n" +
                "JSON文字列配列だけを返してください。例: [\"顕微鏡\",\"盆踊り\",\"税関\"]"
        } else {
            "/no_think\n" +
                "基準語「" + seed + "」と意味的にできるだけ無関係な、日本語の自然な単語または短い語句を最大" + maxCount + "件挙げてください。\n" +
                "反対語ではなく、話題領域そのものが離れているものを選んでください。\n" +
                "固有名詞の断片や意味不明な文字列は禁止です。\n" +
                "JSON文字列配列だけを返してください。"
        }
        parseStringArray(generate(prompt, 420)).take(maxCount)
    }

    private fun ensureLoaded() {
        if (loaded) return
        check(modelFile.isFile) { "Qwen model file is missing" }

        runBlocking {
            when (val state = engine.state.value) {
                is InferenceEngine.State.Initialized -> Unit
                is InferenceEngine.State.ModelReady -> {
                    loaded = true
                    return@runBlocking
                }
                is InferenceEngine.State.Error -> throw state.exception
                else -> {
                    val ready = engine.state.first {
                        it is InferenceEngine.State.Initialized ||
                            it is InferenceEngine.State.ModelReady ||
                            it is InferenceEngine.State.Error
                    }
                    if (ready is InferenceEngine.State.Error) throw ready.exception
                    if (ready is InferenceEngine.State.ModelReady) {
                        loaded = true
                        return@runBlocking
                    }
                }
            }

            engine.loadModel(modelFile.absolutePath)
            engine.setSystemPrompt(SYSTEM_PROMPT)
            loaded = true
        }
    }

    private fun generate(prompt: String, maxTokens: Int): String = runBlocking {
        engine.resetConversation()
        val out = StringBuilder()
        engine.sendUserPrompt(prompt, maxTokens).collect { out.append(it) }
        out.toString()
    }

    private fun parseCandidates(
        raw: String,
        limit: Int,
        parent: String,
        root: String
    ): List<Candidate> {
        val json = extractJsonArray(raw) ?: return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<Candidate>()

        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val word = obj.optString("word").trim()
            val relation = obj.optString("relation").trim()
            if (word.isBlank() || word == parent || word == root) continue
            if (word.length > 40) continue
            if (!seen.add(word)) continue
            out += Candidate(
                text = word,
                semanticDistance = 0f,
                parentSimilarity = 1f,
                relation = relation.ifBlank { null }
            )
            if (out.size >= limit) break
        }
        return out
    }

    private fun parseStringArray(raw: String): List<String> {
        val json = extractJsonArray(raw) ?: return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (i in 0 until array.length()) {
            val text = array.optString(i).trim()
            if (text.isNotBlank() && text.length <= 40) out += text
        }
        return out.toList()
    }

    private fun extractJsonArray(raw: String): String? {
        val withoutThinking = raw.replace(
            Regex("<think>.*?</think>", setOf(RegexOption.DOT_MATCHES_ALL)),
            ""
        )
        val start = withoutThinking.indexOf('[')
        val end = withoutThinking.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return withoutThinking.substring(start, end + 1)
    }

    override fun close() {
        if (loaded) {
            runCatching { engine.destroy() }
            loaded = false
        }
    }

    companion object {
        private const val SYSTEM_PROMPT = """
あなたは日本語のブレインストーミング用連想エンジンです。
目的は、ユーザーがノードを辿るたびに「その一歩は自然だ」と感じる関連概念だけを返すことです。
単なる文字列共起、語の断片、未知の略語、固有名詞の一部分を関連語として扱ってはいけません。
件数より品質を優先し、候補が弱い場合は少数または空配列を返してください。
出力形式の指定に厳密に従ってください。
/no_think
"""
    }
}
