package com.tetra.worddivergence

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
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
import com.tetra.worddivergence.engine.UnavailableSemanticEngine
import com.tetra.worddivergence.graph.ForceGraphLayout
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
    private var engineLoadError: String? = null
    private val worker = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    private lateinit var graphView: SemanticGraphView
    private lateinit var brainstormPanel: FrameLayout
    private lateinit var randomPanel: LinearLayout
    private lateinit var seedInput: EditText
    private lateinit var branchInput: EditText
    private lateinit var similarityInput: EditText
    private lateinit var detailText: TextView
    private lateinit var engineText: TextView
    private lateinit var randomInput: EditText
    private lateinit var randomCountInput: EditText
    private lateinit var randomResults: LinearLayout
    private lateinit var brainstormTab: TextView
    private lateinit var randomTab: TextView
    private lateinit var filterButton: TextView

    private val posState = linkedMapOf<PosCategory, Boolean>()
    private var current: GraphSession? = null
    private var activeExpansions = 0
    private var layoutRunning = false
    private var layoutQueued = false
    private var lastViewportActivityAt = 0L
    private var frontierWatchPosted = false

    private val frontierWatch = object : Runnable {
        override fun run() {
            frontierWatchPosted = false
            val now = System.currentTimeMillis()
            if (now - lastViewportActivityAt > FRONTIER_ACTIVE_MS) return

            val session = current ?: return
            if (session.nodes.size >= MAX_NODES) return

            if (activeExpansions < 2) {
                val candidate = graphView.frontierNodeIds()
                    .asSequence()
                    .mapNotNull { session.nodes[it] }
                    .filter { !it.expanded && !it.loading && it.parentId != null }
                    .maxByOrNull { graphView.distanceFromViewportCenter(it) }

                if (candidate != null) expandNode(candidate)
            }

            frontierWatchPosted = true
            main.postDelayed(this, 280L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = GraphStore(this)
        modelPack = ModelPackManager(this)

        PosCategory.values().forEach {
            posState[it] = it in setOf(
                PosCategory.NOUN,
                PosCategory.VERB,
                PosCategory.ADJECTIVE
            )
        }

        refreshEngine()
        setContentView(buildUi())
        graphView.listener = this

        store.loadAll().firstOrNull()?.let { loadSession(it) }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(
                    0,
                    insets.systemWindowInsetTop,
                    0,
                    insets.systemWindowInsetBottom
                )
                insets
            }
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(10), dp(8))
            background = solid(Color.WHITE)
            elevation = dpF(2f)
        }

        val title = TextView(this).apply {
            text = "word divergence"
            textSize = 16f
            setTextColor(TEXT)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        toolbar.addView(title, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            gravity = Gravity.CENTER_VERTICAL
        })

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(MUTED, 14f)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        brainstormTab = compactTab("Graph") { showTab(true) }
        randomTab = compactTab("Random") { showTab(false) }
        tabs.addView(brainstormTab)
        tabs.addView(randomTab)
        toolbar.addView(tabs)

        toolbar.addView(iconButton("☆") { showHistory() }, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            marginStart = dp(6)
        })
        toolbar.addView(iconButton("↓") { showModelMenu() }, LinearLayout.LayoutParams(dp(42), dp(42)))

        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(58)))

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

    private fun buildBrainstormPanel(): FrameLayout {
        val panel = FrameLayout(this).apply { setBackgroundColor(BG) }

        graphView = SemanticGraphView(this)
        panel.addView(graphView, FrameLayout.LayoutParams(-1, -1))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(Color.WHITE, 18f, STROKE)
            elevation = dpF(8f)
        }

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        seedInput = EditText(this).apply {
            hint = "単語や文章を入力"
            isSingleLine = true
            textSize = 15f
            setTextColor(TEXT)
            setHintTextColor(SUBTLE)
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(FIELD, 12f)
            setText("海")
        }

        branchInput = EditText(this).apply {
            setText("5")
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(TEXT)
            background = rounded(FIELD, 12f)
            hint = "上限"
        }

        similarityInput = EditText(this).apply {
            setText("0.45")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(TEXT)
            background = rounded(FIELD, 12f)
            hint = "類似度"
        }

        val generate = primaryButton("生成") { createMap(seedInput.text.toString()) }

        inputRow.addView(seedInput, LinearLayout.LayoutParams(0, dp(48), 1f))
        inputRow.addView(branchInput, LinearLayout.LayoutParams(dp(56), dp(48)).apply {
            marginStart = dp(8)
        })
        inputRow.addView(similarityInput, LinearLayout.LayoutParams(dp(66), dp(48)).apply {
            marginStart = dp(8)
        })
        inputRow.addView(generate, LinearLayout.LayoutParams(dp(68), dp(48)).apply {
            marginStart = dp(8)
        })
        controls.addView(inputRow)

        val metaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        filterButton = secondaryButton("品詞") { showPartOfSpeechDialog() }
        metaRow.addView(filterButton, LinearLayout.LayoutParams(-2, dp(36)))

        engineText = TextView(this).apply {
            textSize = 11f
            setTextColor(SUBTLE)
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(dp(10), 0, 0, 0)
        }
        metaRow.addView(engineText, LinearLayout.LayoutParams(0, dp(36), 1f))
        controls.addView(metaRow)

        panel.addView(
            controls,
            FrameLayout.LayoutParams(-1, -2).apply {
                leftMargin = dp(12)
                rightMargin = dp(12)
                topMargin = dp(12)
                gravity = Gravity.TOP
            }
        )

        detailText = TextView(this).apply {
            text = "タップで詳細・長押しで保存 / 新しい中心"
            textSize = 12f
            setTextColor(Color.rgb(71, 76, 87))
            setPadding(dp(13), dp(9), dp(13), dp(9))
            background = rounded(Color.argb(238, 255, 255, 255), 14f, STROKE)
            elevation = dpF(5f)
        }

        panel.addView(
            detailText,
            FrameLayout.LayoutParams(-2, -2).apply {
                leftMargin = dp(12)
                bottomMargin = dp(14)
                gravity = Gravity.BOTTOM or Gravity.START
            }
        )

        return panel
    }

    private fun buildRandomPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(BG)
        }

        panel.addView(TextView(this).apply {
            text = "無関係な言葉"
            textSize = 22f
            setTextColor(TEXT)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })

        panel.addView(TextView(this).apply {
            text = "基準語あり：意味的に無関係な語 / 空欄：完全ランダム"
            textSize = 12f
            setTextColor(SUBTLE)
            setPadding(0, dp(4), 0, dp(12))
        })

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(Color.WHITE, 18f, STROKE)
            elevation = dpF(5f)
        }

        randomInput = EditText(this).apply {
            hint = "基準語（空欄でもOK）"
            isSingleLine = true
            textSize = 15f
            setTextColor(TEXT)
            setHintTextColor(SUBTLE)
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(FIELD, 12f)
        }

        randomCountInput = EditText(this).apply {
            setText("20")
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(TEXT)
            background = rounded(FIELD, 12f)
        }

        card.addView(randomInput, LinearLayout.LayoutParams(0, dp(48), 1f))
        card.addView(randomCountInput, LinearLayout.LayoutParams(dp(62), dp(48)).apply {
            marginStart = dp(8)
        })
        card.addView(primaryButton("生成") { generateRandom() }, LinearLayout.LayoutParams(dp(72), dp(48)).apply {
            marginStart = dp(8)
        })
        panel.addView(card)

        randomResults = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(20))
        }
        panel.addView(ScrollView(this).apply {
            clipToPadding = false
            addView(randomResults)
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        return panel
    }

    private fun showTab(brainstorm: Boolean) {
        brainstormPanel.visibility = if (brainstorm) View.VISIBLE else View.GONE
        randomPanel.visibility = if (brainstorm) View.GONE else View.VISIBLE

        brainstormTab.background = rounded(if (brainstorm) Color.WHITE else Color.TRANSPARENT, 11f)
        randomTab.background = rounded(if (!brainstorm) Color.WHITE else Color.TRANSPARENT, 11f)
        brainstormTab.setTextColor(if (brainstorm) TEXT else SUBTLE)
        randomTab.setTextColor(if (!brainstorm) TEXT else SUBTLE)
    }

    private fun showPartOfSpeechDialog() {
        val categories = PosCategory.values()
        val labels = categories.map { it.label }.toTypedArray()
        val checked = categories.map { posState[it] == true }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("生成する品詞")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                posState[categories[which]] = isChecked
            }
            .setPositiveButton("完了") { _, _ ->
                current?.posFilter = currentFilter()
                updateFilterLabel()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun createMap(rawSeed: String) {
        val seed = rawSeed.trim()
        if (seed.isEmpty()) return
        if (!ensureSemanticEngineReady()) return

        current?.let { persist(it) }
        val branches = branchInput.text.toString().toIntOrNull()?.coerceIn(1, 30) ?: 5
        val minSimilarity = similarityInput.text.toString()
            .toFloatOrNull()
            ?.coerceIn(0f, 1f)
            ?: 0.45f
        similarityInput.setText(String.format("%.2f", minSimilarity))

        val session = GraphSession(
            id = UUID.randomUUID().toString(),
            title = seed,
            rootText = seed,
            branchCount = branches,
            minSimilarity = minSimilarity,
            posFilter = currentFilter()
        )

        val root = GraphNode(
            id = "root",
            text = seed,
            parentId = null,
            depth = 0,
            semanticDistance = 0f,
            parentSimilarity = 1f,
            angle = 0f,
            x = 0f,
            y = 0f
        )

        session.nodes[root.id] = root
        current = session
        graphView.setSession(session, resetCamera = true)
        detailText.text = "「" + seed + "」から生成中…"
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
                    session.minSimilarity,
                    filter
                )
            }

            main.post {
                node.loading = false
                activeExpansions = (activeExpansions - 1).coerceAtLeast(0)

                result.onSuccess { candidates ->
                    val existing = session.nodes.values.mapTo(HashSet()) { it.text }
                    val unique = candidates
                        .filter { it.text !in existing }
                        .take((MAX_NODES - session.nodes.size).coerceAtLeast(0))

                    session.addChildren(node, unique)
                    graphView.refreshSession()
                    requestLayout(session)
                    persist(session)
                    ensureFrontierWatch()
                }.onFailure {
                    node.expanded = true
                    graphView.refreshSession()
                    Toast.makeText(this, "生成失敗: " + (it.message ?: "unknown"), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun requestLayout(session: GraphSession) {
        if (layoutRunning) {
            layoutQueued = true
            return
        }

        layoutRunning = true
        val snapshot = session.nodes.values.map { it.copy() }

        worker.submit {
            val positions = ForceGraphLayout.relax(snapshot)
            main.post {
                if (current?.id == session.id) {
                    positions.forEach { (id, position) ->
                        session.nodes[id]?.let { node ->
                            node.x = position.x
                            node.y = position.y
                        }
                    }
                    session.layoutVersion = 3
                    graphView.refreshSession()
                    persist(session)
                }

                layoutRunning = false
                if (layoutQueued) {
                    layoutQueued = false
                    current?.let { requestLayout(it) }
                }
            }
        }
    }

    override fun onNodeSelected(nodeId: String) {
        val node = current?.nodes?.get(nodeId) ?: return
        detailText.text = node.text +
            "   親との関連度 " + String.format("%.3f", node.parentSimilarity) +
            "   ルート距離 " + String.format("%.3f", node.semanticDistance) +
            "   深さ " + node.depth
    }

    override fun onNodeLongPressed(nodeId: String) {
        val session = current ?: return
        val node = session.nodes[nodeId] ?: return
        val starLabel = if (node.starred) "☆ 保存を解除" else "☆ この地点を保存"

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
        if (hypot(dirX, dirY) > 0.8f || dirX == 0f && dirY == 0f) {
            lastViewportActivityAt = System.currentTimeMillis()
            ensureFrontierWatch()
        }
    }

    private fun ensureFrontierWatch() {
        if (frontierWatchPosted) return
        frontierWatchPosted = true
        main.postDelayed(frontierWatch, 120L)
    }

    private fun generateRandom() {
        if (!ensureSemanticEngineReady()) return

        val count = randomCountInput.text.toString().toIntOrNull()?.coerceIn(1, 1000) ?: 20
        val seed = randomInput.text.toString().trim().ifBlank { null }

        randomResults.removeAllViews()
        randomResults.addView(ProgressBar(this))

        worker.submit {
            val result = runCatching { engine.randomWords(seed, count, currentFilter()) }
            main.post {
                randomResults.removeAllViews()
                result.onSuccess { words ->
                    words.forEach { word ->
                        val row = TextView(this).apply {
                            text = word
                            textSize = 16f
                            setTextColor(TEXT)
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(16), dp(14), dp(16), dp(14))
                            background = rounded(Color.WHITE, 14f, STROKE)
                            setOnClickListener {
                                showTab(true)
                                seedInput.setText(word)
                                createMap(word)
                            }
                        }
                        randomResults.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                            bottomMargin = dp(8)
                        })
                    }
                }.onFailure {
                    randomResults.addView(TextView(this).apply {
                        text = "生成失敗: " + it.message
                        setTextColor(TEXT)
                    })
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

        val labels = sessions.map { session ->
            val stars = session.nodes.values.count { it.starred }
            (if (stars > 0) "★ " else "") +
                session.title + "   " + session.nodes.size + " nodes"
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
        similarityInput.setText(String.format("%.2f", session.minSimilarity))

        PosCategory.values().forEach { posState[it] = it in session.posFilter.enabled }
        updateFilterLabel()
        graphView.setSession(session, resetCamera = true)

        if (session.layoutVersion < 3) requestLayout(session)

        session.nodes.values.firstOrNull { it.starred }?.let {
            detailText.text = "★ " + it.text + " が保存されています"
        }

        showTab(true)
    }

    private fun persist(session: GraphSession) {
        graphView.syncCameraToSession()
        session.updatedAt = System.currentTimeMillis()
        store.save(session)
    }

    private fun currentFilter(): PosFilter {
        val enabled = posState.filterValues { it }.keys.toSet()
        return PosFilter(if (enabled.isEmpty()) setOf(PosCategory.NOUN) else enabled)
    }

    private fun updateFilterLabel() {
        if (!::filterButton.isInitialized) return
        val count = posState.count { it.value }
        filterButton.text = "品詞  " + count + "/" + PosCategory.values().size
    }

    private fun ensureSemanticEngineReady(): Boolean {
        return when (engine) {
            is UsearchSemanticEngine -> true

            is DemoSemanticEngine -> {
                AlertDialog.Builder(this)
                    .setTitle("日本語モデルが必要です")
                    .setMessage(
                        "Brainstormは意味ベクトルを使うため、UI確認用デモ辞書では生成しません。\n" +
                            "フル日本語モデルをダウンロードしてください。"
                    )
                    .setPositiveButton("ダウンロード") { _, _ -> downloadModel() }
                    .setNegativeButton("キャンセル", null)
                    .show()
                false
            }

            else -> {
                showModelMenu()
                false
            }
        }
    }

    private fun refreshEngine() {
        runCatching { engine.close() }
        engineLoadError = null

        engine = if (modelPack.isInstalled()) {
            runCatching {
                UsearchSemanticEngine(modelPack.modelDir)
            }.getOrElse { error ->
                val detail = buildString {
                    append(error.javaClass.simpleName)
                    error.message?.takeIf { it.isNotBlank() }?.let {
                        append(": ")
                        append(it)
                    }
                }
                engineLoadError = detail
                UnavailableSemanticEngine(detail)
            }
        } else {
            DemoSemanticEngine()
        }

        if (::engineText.isInitialized) updateEngineLabel()
    }

    private fun updateEngineLabel() {
        engineText.text = when (engine) {
            is UsearchSemanticEngine -> "2M語 • local"
            is DemoSemanticEngine -> "DEMO • 非意味検索"
            else -> "MODEL ERROR"
        }
        engineText.setTextColor(
            when (engine) {
                is UsearchSemanticEngine -> SUBTLE
                is DemoSemanticEngine -> Color.rgb(194, 120, 18)
                else -> Color.rgb(190, 45, 45)
            }
        )
        updateFilterLabel()
    }

    private fun showModelMenu() {
        val installed = modelPack.isInstalled()
        val message = when (engine) {
            is UsearchSemanticEngine ->
                "200万語のfastText + HNSWローカルモデルを使用中です。"

            is DemoSemanticEngine ->
                "フル日本語モデルは未導入です。\n現在のDEMOはUI確認用で、意味的な連想品質は保証しません。"

            else ->
                "モデルファイルはありますが、読み込みに失敗しています。\n" +
                    (engineLoadError ?: "原因不明") +
                    "\n\n再ダウンロードしてください。"
        }

        AlertDialog.Builder(this)
            .setTitle("日本語モデル")
            .setMessage(message)
            .setPositiveButton(if (installed) "再ダウンロード" else "ダウンロード") { _, _ ->
                downloadModel()
            }
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
                modelPack.downloadAndInstall { progress ->
                    main.post {
                        bar.progress = progress
                        text.text = progress.toString() + "%"
                    }
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

    private fun compactTab(label: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(13), 0, dp(13), 0)
            setOnClickListener { action() }
        }

    private fun iconButton(label: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(TEXT)
            background = rounded(Color.TRANSPARENT, 12f)
            setOnClickListener { action() }
        }

    private fun primaryButton(label: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = rounded(ACCENT, 12f)
            setOnClickListener { action() }
        }

    private fun secondaryButton(label: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(78, 82, 92))
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(FIELD, 11f)
            setOnClickListener { action() }
        }

    private fun solid(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
        }

    private fun rounded(
        color: Int,
        radiusDp: Float,
        strokeColor: Int? = null
    ): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(radiusDp)
            setColor(color)
            strokeColor?.let { setStroke(dp(1), it) }
        }

    override fun onPause() {
        super.onPause()
        current?.let { persist(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(frontierWatch)
        worker.shutdownNow()
        runCatching { engine.close() }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun dpF(value: Float): Float =
        value * resources.displayMetrics.density

    companion object {
        private const val MAX_NODES = 5000
        private const val FRONTIER_ACTIVE_MS = 1900L

        private val BG = Color.rgb(247, 248, 251)
        private val FIELD = Color.rgb(244, 245, 248)
        private val MUTED = Color.rgb(239, 241, 245)
        private val STROKE = Color.rgb(224, 227, 234)
        private val TEXT = Color.rgb(28, 31, 38)
        private val SUBTLE = Color.rgb(124, 131, 144)
        private val ACCENT = Color.rgb(79, 70, 229)
    }
}
