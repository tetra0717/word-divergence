package com.tetra.worddivergence.engine

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.tetra.worddivergence.model.Candidate
import com.tetra.worddivergence.model.PosFilter
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import java.io.File

class LlmSemanticEngine(
    context: Context,
    private val modelManager: LlmModelManager,
    private val onStatus: (String) -> Unit = {}
) : SemanticEngine {
    override val label: String = "Qwen3-1.7B • local"

    private val engine: InferenceEngine = AiChat.getInferenceEngine(context.applicationContext)
    private val lock = Any()
    @Volatile private var loaded = false
    private var needsConversationReset = false

    override fun generateChildren(
        rootText: String,
        parentText: String,
        path: List<String>,
        parentDistance: Float,
        count: Int,
        minSimilarity: Float,
        filter: PosFilter
    ): List<Candidate> = synchronized(lock) {
        ensureLoaded()
        val maxCount = count.coerceIn(1, 20)
        val allowedPos = filter.enabled.joinToString("・") { it.label }
        val prompt = buildString {
            appendLine("/no_think")
            appendLine("root=" + rootText)
            appendLine("path=" + path.joinToString("→"))
            appendLine("parent=" + parentText)
            appendLine("max=" + maxCount)
            appendLine("pos=" + allowedPos)
            appendLine("自然な直接連想だけを返す。弱い連想・語の断片・既出語は禁止。")
            appendLine("関係の種類はできるだけ分散。無理に件数を埋めない。")
            appendLine("""JSONのみ: [{"word":"船","relation":"移動手段"}]""")
        }
        val maxTokens = (64 + maxCount * 18).coerceIn(96, 220)
        parseCandidates(generate(prompt, maxTokens), maxCount, parentText, rootText)
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
        onStatus("Qwenモデルを準備中…")
        val modelFile = modelManager.ensureInternalModel { onStatus(it) }

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

            onStatus("Qwenモデルを読み込み中…")
            engine.loadModel(modelFile.absolutePath)
            onStatus("連想ルールを準備中…")
            engine.setSystemPrompt(SYSTEM_PROMPT)
            loaded = true
            needsConversationReset = false
        }
    }

    private fun generate(prompt: String, maxTokens: Int): String = runBlocking {
        onStatus("連想を生成中…")
        if (needsConversationReset) {
            engine.resetConversation()
        }
        needsConversationReset = true
        val out = StringBuilder()
        engine.sendUserPrompt(prompt, maxTokens)
            .takeWhile { token ->
                out.append(token)
                !hasCompleteJsonArray(out)
            }
            .collect()
        out.toString()
    }

    private fun hasCompleteJsonArray(text: CharSequence): Boolean {
        var started = false
        var depth = 0
        var inString = false
        var escaped = false

        for (i in 0 until text.length) {
            val ch = text[i]

            if (inString) {
                if (escaped) {
                    escaped = false
                    continue
                }
                when (ch) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
                continue
            }

            when (ch) {
                '"' -> inString = true
                '[' -> {
                    started = true
                    depth++
                }
                ']' -> {
                    if (started) {
                        depth--
                        if (depth == 0) return true
                    }
                }
            }
        }
        return false
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
日本語ブレインストーミング用の連想エンジン。
親ノードから人間に自然な1ステップ連想だけを返す。
文字列共起だけの語、語の断片、不自然な略語、固有名詞の一部分は禁止。
品質を件数より優先し、弱い候補は出さない。
指定されたJSON形式だけを返す。
/no_think
"""
    }
}
