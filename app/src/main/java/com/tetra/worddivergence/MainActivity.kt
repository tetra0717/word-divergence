package com.tetra.worddivergence

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.tetra.worddivergence.data.GraphStore
import com.tetra.worddivergence.engine.DemoSemanticEngine
import com.tetra.worddivergence.engine.ModelPackManager
import com.tetra.worddivergence.engine.SemanticEngine
import com.tetra.worddivergence.engine.UsearchSemanticEngine
import com.tetra.worddivergence.graph.SemanticGraphView
import com.tetra.worddivergence.model.GraphNode
import com.tetra.worddivergence.model.GraphSession
import com.tetra.worddivergence.model.PosCategory
import com.tetra.worddivergence.model.PosFilter
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.hypot

class MainActivity : Activity(), SemanticGraphView.Listener {
    private lateinit var store: GraphStore
    private lateinit var modelPack: ModelPackManager
    private var engine: SemanticEngine = DemoSemanticEngine()
    private val worker = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    private lateinit var graphView: SemanticGraphView
    private lateinit var brainstormPanel: LinearLayout
    private lateinit var randomPanel: LinearLayout
    private lateinit var seedInput: EditText
    private lateinit var branchInput: EditText
    private lateinit var detailText: TextView
    private lateinit var engineText: TextView
    private lateinit var randomInput: EditText
    private lateinit var randomCountInput: EditText
    private lateinit var randomResults: LinearLayout

    private val posChecks = linkedMapOf<PosCategory, CheckBox>()
    private var current: GraphSession? = null
    private var activeExpansions = 0
    private var lastAutoAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = GraphStore(this)
        modelPack = ModelPackManager(this)
        refreshEngine()
        setContentView(buildUi())
        graphView.listener = this

        store.loadAll().firstOrNull()?.let { loadSession(it) }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(250, 250, 250))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(6))
        }
        val brainstormTab = Button(this).apply {
            text = "Brainstorm"
            isAllCaps = false
            setOnClickListener { showTab(true) }
        }
        val randomTab = Button(this).apply {
            text = "Random"
            isAllCaps = false
            setOnClickListener { showTab(false) }
        }
        val history = Button(this).apply {
            text = "履歴 / ☆"
            isAllCaps = false
            setOnClickListener { showHistory() }
        }
        val model = Button(this).apply {
            text = "モデル"
            isAllCaps = false
            setOnClickListener { showModelMenu() }
        }
        engineText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(dp(8), 0, 0, 0)
        }
        top.addView(brainstormTab)
        top.addView(randomTab)
        top.addView(history)
        top.addView(model)
        top.addView(engineText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(top)

        val content = FrameLayout(this)
        brainstormPanel = buildBrainstormPanel()
        randomPanel = buildRandomPanel()
        content.addView(brainstormPanel, FrameLayout.LayoutParams(-1, -1))
        content.addView(randomPanel, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        showTab(true)
        updateEngineLabel()
        return root
    }

    private fun buildBrainstormPanel(): LinearLayout {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        seedInput = EditText(this).apply {
            hint = "単語 / 文章"
            isSingleLine = true
            setText("海")
        }
        branchInput = EditText(this).apply {
            setText("5")
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "枝数"
            gravity = Gravity.CENTER
        }
        val generate = Button(this).apply {
            text = "生成"
            isAllCaps = false
            setOnClickListener { createMap(seedInput.text.toString()) }
        }
        inputRow.addView(seedInput, LinearLayout.LayoutParams(0, dp(48), 1f))
        inputRow.addView(branchInput, LinearLayout.LayoutParams(dp(64), dp(48)))
        inputRow.addView(generate, LinearLayout.LayoutParams(dp(74), dp(48)))
        panel.addView(inputRow)

        val filterScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), dp(2))
        }
        PosCategory.values().forEach { category ->
            val cb = CheckBox(this).apply {
                text = category.label
                isChecked = category in setOf(PosCategory.NOUN, PosCategory.VERB, PosCategory.ADJECTIVE)
                setOnCheckedChangeListener { _, _ ->
                    current?.posFilter = currentFilter()
                }
            }
            posChecks[category] = cb
            filterRow.addView(cb)
        }
        filterScroll.addView(filterRow)
        panel.addView(filterScroll, LinearLayout.LayoutParams(-1, dp(44)))

        detailText = TextView(this).apply {
            text = "ノードをタップすると意味距離を表示。長押しで☆ / 新しい中心。"
            textSize = 12f
            setTextColor(Color.rgb(80, 80, 88))
            setPadding(dp(14), dp(2), dp(12), dp(6))
        }
        panel.addView(detailText)

        graphView = SemanticGraphView(this)
        panel.addView(graphView, LinearLayout.LayoutParams(-1, 0, 1f))
        return panel
    }

    private fun buildRandomPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        randomInput = EditText(this).apply {
            hint = "基準語（空欄なら完全ランダム）"
            isSingleLine = true
        }
        randomCountInput = EditText(this).apply {
            setText("20")
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
        }
        val button = Button(this).apply {
            text = "生成"
            isAllCaps = false
            setOnClickListener { generateRandom() }
        }
        row.addView(randomInput, LinearLayout.LayoutParams(0, dp(50), 1f))
        row.addView(randomCountInput, LinearLayout.LayoutParams(dp(68), dp(50)))
        row.addView(button, LinearLayout.LayoutParams(dp(74), dp(50)))
        panel.addView(row)

        val help = TextView(this).apply {
            text = "入力あり: コサイン類似度が0付近の語をサンプリング。入力なし: 語彙全体からランダム。"
            setPadding(dp(14), 0, dp(14), dp(8))
            textSize = 12f
            setTextColor(Color.DKGRAY)
        }
        panel.addView(help)

        randomResults = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(20))
        }
        val scroll = ScrollView(this).apply { addView(randomResults) }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return panel
    }

    private fun showTab(brainstorm: Boolean) {
        brainstormPanel.visibility = if (brainstorm) View.VISIBLE else View.GONE
        randomPanel.visibility = if (brainstorm) View.GONE else View.VISIBLE
    }

    private fun createMap(rawSeed: String) {
        val seed = rawSeed.trim()
        if (seed.isEmpty()) return
        current?.let { persist(it) }
        val branches = branchInput.text.toString().toIntOrNull()?.coerceIn(1, 30) ?: 5
        val session = GraphSession(
            id = UUID.randomUUID().toString(),
            title = seed,
            rootText = seed,
            branchCount = branches,
            posFilter = currentFilter()
        )
        val root = GraphNode(
            id = "root",
            text = seed,
            parentId = null,
            depth = 0,
            semanticDistance = 0f,
            angle = 0f,
            x = 0f,
            y = 0f
        )
        session.nodes[root.id] = root
        current = session
        graphView.setSession(session, resetCamera = true)
        detailText.text = "「" + seed + "」から探索中…"
        expandNode(root)
    }

    private fun expandNode(node: GraphNode) {
        val session = current ?: return
        if (node.loading || node.expanded || session.nodes.size >= MAX_NODES) return
        node.loading = true
        activeExpansions++
        graphView.refreshSession()

        val filter = currentFilter()
        session.posFilter = filter
        worker.submit {
            val result = runCatching {
                engine.generateChildren(
                    session.rootText,
                    node.text,
                    node.semanticDistance,
                    session.branchCount,
                    filter
                )
            }
            main.post {
                node.loading = false
                activeExpansions = (activeExpansions - 1).coerceAtLeast(0)
                result.onSuccess { candidates ->
                    val existing = session.nodes.values.mapTo(HashSet()) { it.text }
                    val unique = candidates.filter { it.text !in existing }
                        .take((MAX_NODES - session.nodes.size).coerceAtLeast(0))
                    session.addChildren(node, unique)
                    graphView.refreshSession()
                    persist(session)
                }.onFailure {
                    node.expanded = true
                    graphView.refreshSession()
                    Toast.makeText(this, "生成失敗: " + (it.message ?: "unknown"), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onNodeSelected(nodeId: String) {
        val n = current?.nodes?.get(nodeId) ?: return
        detailText.text = n.text + "   ルートからの意味距離 " +
            String.format("%.3f", n.semanticDistance) + "   深さ " + n.depth
    }

    override fun onNodeLongPressed(nodeId: String) {
        val session = current ?: return
        val node = session.nodes[nodeId] ?: return
        val starLabel = if (node.starred) "☆を外す" else "☆ この地点を保存"
        AlertDialog.Builder(this)
            .setTitle(node.text)
            .setItems(arrayOf(starLabel, "◎ 新しい中心にする")) { _, which ->
                when (which) {
                    0 -> {
                        node.starred = !node.starred
                        graphView.refreshSession()
                        persist(session)
                    }
                    1 -> {
                        persist(session)
                        seedInput.setText(node.text)
                        createMap(node.text)
                    }
                }
            }
            .show()
    }

    override fun onViewportMoved(
        visibleNodeIds: List<String>,
        centerX: Float,
        centerY: Float,
        dirX: Float,
        dirY: Float
    ) {
        val now = System.currentTimeMillis()
        if (now - lastAutoAt < 220L || activeExpansions >= 2) return
        val session = current ?: return
        if (session.nodes.size >= MAX_NODES) return
        val dirLen = hypot(dirX, dirY)
        if (dirLen < 4f) return
        val ux = dirX / dirLen
        val uy = dirY / dirLen
        val candidate = visibleNodeIds
            .asSequence()
            .mapNotNull { session.nodes[it] }
            .filter { !it.expanded && !it.loading && it.parentId != null }
            .map { n ->
                val vx = n.x - centerX
                val vy = n.y - centerY
                n to (vx * ux + vy * uy)
            }
            .filter { it.second > 20f }
            .maxByOrNull { it.second }
            ?.first ?: return
        lastAutoAt = now
        expandNode(candidate)
    }

    private fun generateRandom() {
        val count = randomCountInput.text.toString().toIntOrNull()?.coerceIn(1, 1000) ?: 20
        val seed = randomInput.text.toString().trim().ifBlank { null }
        randomResults.removeAllViews()
        val progress = ProgressBar(this)
        randomResults.addView(progress)
        worker.submit {
            val result = runCatching { engine.randomWords(seed, count, currentFilter()) }
            main.post {
                randomResults.removeAllViews()
                result.onSuccess { words ->
                    words.forEachIndexed { index, word ->
                        val row = TextView(this).apply {
                            text = (index + 1).toString() + ".  " + word
                            textSize = 17f
                            setTextColor(Color.rgb(45, 45, 52))
                            setPadding(dp(14), dp(12), dp(14), dp(12))
                            setBackgroundColor(if (index % 2 == 0) Color.WHITE else Color.rgb(247, 247, 248))
                            setOnClickListener {
                                showTab(true)
                                seedInput.setText(word)
                                createMap(word)
                            }
                        }
                        randomResults.addView(row, LinearLayout.LayoutParams(-1, -2))
                    }
                }.onFailure {
                    randomResults.addView(TextView(this).apply { text = "生成失敗: " + it.message })
                }
            }
        }
    }

    private fun showHistory() {
        current?.let { persist(it) }
        val sessions = store.loadAll()
        if (sessions.isEmpty()) {
            Toast.makeText(this, "履歴はまだありません", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = sessions.map { s ->
            val stars = s.nodes.values.count { it.starred }
            (if (stars > 0) "☆ " else "") + s.title + "   (" + s.nodes.size + " nodes)"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("履歴 / 保存地点")
            .setItems(labels) { _, which -> loadSession(sessions[which]) }
            .show()
    }

    private fun loadSession(session: GraphSession) {
        current = session
        seedInput.setText(session.rootText)
        branchInput.setText(session.branchCount.toString())
        posChecks.forEach { (category, check) -> check.isChecked = category in session.posFilter.enabled }
        graphView.setSession(session, resetCamera = true)
        session.nodes.values.firstOrNull { it.starred }?.let {
            detailText.text = "☆ " + it.text + " が保存されています"
        }
        showTab(true)
    }

    private fun persist(session: GraphSession) {
        graphView.syncCameraToSession()
        session.updatedAt = System.currentTimeMillis()
        store.save(session)
    }

    private fun currentFilter(): PosFilter {
        val enabled = posChecks.filterValues { it.isChecked }.keys.toSet()
        return PosFilter(if (enabled.isEmpty()) setOf(PosCategory.NOUN) else enabled)
    }

    private fun refreshEngine() {
        runCatching { engine.close() }
        engine = if (modelPack.isInstalled()) {
            runCatching { UsearchSemanticEngine(modelPack.modelDir) }.getOrElse { DemoSemanticEngine() }
        } else {
            DemoSemanticEngine()
        }
        if (::engineText.isInitialized) updateEngineLabel()
    }

    private fun updateEngineLabel() {
        engineText.text = engine.label + if (modelPack.isInstalled()) "" else "（モデル未導入）"
    }

    private fun showModelMenu() {
        val installed = modelPack.isInstalled()
        AlertDialog.Builder(this)
            .setTitle("日本語モデル")
            .setMessage(
                if (installed) "フル語彙のローカル検索パックを使用中です。"
                else "現在はUI確認用デモ辞書です。model-v1 Releaseからフル語彙パックをダウンロードできます。"
            )
            .setPositiveButton(if (installed) "再ダウンロード" else "ダウンロード") { _, _ -> downloadModel() }
            .setNegativeButton("閉じる", null)
            .show()
    }

    private fun downloadModel() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(18))
        }
        val text = TextView(this).apply { this.text = "0%" }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        dialogView.addView(text)
        dialogView.addView(bar, LinearLayout.LayoutParams(-1, dp(28)))
        val dialog = AlertDialog.Builder(this)
            .setTitle("モデルをダウンロード")
            .setView(dialogView)
            .setCancelable(false)
            .create()
        dialog.show()

        worker.submit {
            val result = runCatching {
                modelPack.downloadAndInstall { p ->
                    main.post { bar.progress = p; text.text = p.toString() + "%" }
                }
            }
            main.post {
                dialog.dismiss()
                result.onSuccess {
                    refreshEngine()
                    Toast.makeText(this, "モデルをインストールしました", Toast.LENGTH_LONG).show()
                }.onFailure {
                    Toast.makeText(this, "ダウンロード失敗: " + it.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        current?.let { persist(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
        runCatching { engine.close() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_NODES = 5000
    }
}
