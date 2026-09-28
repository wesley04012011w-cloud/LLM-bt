package com.llmbt

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private lateinit var chat: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private lateinit var systemPromptButton: Button
    private lateinit var samplingButton: Button
    private lateinit var threadsButton: Button
    private lateinit var reloadModelsButton: Button
    private lateinit var resetHistoryButton: Button
    private lateinit var generationStatsText: TextView
    private lateinit var root: FrameLayout
    private lateinit var drawerPanel: LinearLayout
    private lateinit var drawerScrim: View
    private lateinit var drawerChats: LinearLayout
    private val chatSessions = mutableListOf<ChatSession>()
    private var currentChat: ChatSession? = null
    private val streamingText = StringBuilder()
    private lateinit var preferences: android.content.SharedPreferences
    private var nativeLoaded = false
    private var modelLoaded = false
    private var currentModelFile: File? = null
    @Volatile private var streamingResponseStarted = false
    private var currentAssistantMessage: TextView? = null

    private external fun stringFromNative(): String
    private external fun loadModel(path: String): String
    private external fun generateText(prompt: String): String
    private external fun setSystemPrompt(prompt: String)
    private external fun setSamplingParams(
        temperature: Float,
        minP: Float,
        repeatPenalty: Float,
        topP: Float,
        topK: Int
    )
    private external fun setGenerationTokens(tokens: Int)
    private external fun setThreadConfig(generationThreads: Int, batchThreads: Int)
    private external fun resetConversation()
    private external fun restoreConversationHistory(roles: Array<String>, contents: Array<String>)

    private data class ChatMessage(val role: String, val content: String)

    private data class ChatSession(
        val id: String,
        var title: String,
        var modelName: String,
        var modelPath: String,
        val messages: MutableList<ChatMessage> = mutableListOf()
    )

    companion object {
        private const val PICK_MODEL = 1001
        private const val PREFS_NAME = "llm_bt_settings"
        private const val SYSTEM_PROMPT_KEY = "system_prompt"
        private const val TEMPERATURE_KEY = "sampling_temperature"
        private const val MIN_P_KEY = "sampling_min_p"
        private const val REPEAT_PENALTY_KEY = "sampling_repeat_penalty"
        private const val TOP_P_KEY = "sampling_top_p"
        private const val TOP_K_KEY = "sampling_top_k"
        private const val GENERATION_TOKENS_KEY = "generation_tokens"
        private const val GENERATION_THREADS_KEY = "generation_threads"
        private const val BATCH_THREADS_KEY = "batch_threads"
        private const val CHATS_FILE = "chats.json"
        private const val DEFAULT_GENERATION_THREADS = 4
        private const val DEFAULT_BATCH_THREADS = 4
        private const val MIN_THREADS = 1
        private const val MAX_THREADS = 8
        private const val DEFAULT_TEMPERATURE = 0.30f
        private const val DEFAULT_MIN_P = 0.15f
        private const val DEFAULT_REPEAT_PENALTY = 1.05f
        private const val DEFAULT_TOP_P = 0.95f
        private const val DEFAULT_TOP_K = 40
        private const val DEFAULT_GENERATION_TOKENS = 384
        private const val MIN_GENERATION_TOKENS = 64
        private const val MAX_GENERATION_TOKENS = 1024
        private const val GENERATION_TOKEN_STEP = 32
        private const val DEFAULT_SYSTEM_PROMPT = """Você é o LLM-BT, um assistente local.
Seu nome é LLM-BT.
Quando alguém perguntar seu nome, responda que seu nome é LLM-BT.
Quando alguém perguntar quem você é, diga que você é o LLM-BT, um assistente local.
Nunca invente outro nome para si mesmo.
Nunca diga que seu nome é Alex, Ana, João, Lúcio ou qualquer outro nome.
Você foi criado como parte do projeto LLM-BT.

Responda de forma natural, clara e direta.
Prefira uma conversa humana e espontânea, evitando respostas robóticas, excessivamente formais ou desnecessariamente longas.
Quando uma explicação simples for suficiente, não complique.
Não invente informações quando não souber a resposta."""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppLogger.write("MainActivity.onCreate started")
        AppLogger.write("Native engine will be loaded on demand")
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(6))
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val menuButton = TextView(this).apply {
            text = "☰"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(32, 33, 36))
            contentDescription = "Abrir chats"
            setOnClickListener { toggleDrawer() }
        }

        val title = TextView(this).apply {
            text = "LLM BT"
            textSize = 20f
            setTextColor(Color.rgb(32, 33, 36))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val engineButton = TextView(this).apply {
            text = "⚙"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(32, 33, 36))
            contentDescription = "Abrir engine"
            setOnClickListener { showEngineMenu() }
        }

        val importButton = Button(this).apply {
            text = "Importar GGUF"
            textSize = 12f
            isAllCaps = false
            setOnClickListener { openModelPicker() }
        }

        titleRow.addView(menuButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        titleRow.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        titleRow.addView(engineButton, LinearLayout.LayoutParams(dp(48), dp(48)))

        generationStatsText = TextView(this).apply {
            text = "Tokens: 0  |  tok/s: —"
            textSize = 12f
            setTextColor(Color.rgb(110, 110, 110))
            setPadding(dp(4), 0, dp(4), dp(4))
        }

        systemPromptButton = Button(this).apply {
            text = "System"
            textSize = 12f
            isAllCaps = false
            setOnClickListener { showSystemPromptDialog() }
        }

        samplingButton = Button(this).apply {
            text = "Sampling"
            textSize = 12f
            isAllCaps = false
            setOnClickListener { showSamplingDialog() }
        }

        threadsButton = Button(this).apply {
            text = "Threads"
            textSize = 12f
            isAllCaps = false
            setOnClickListener { showThreadsDialog() }
        }

        reloadModelsButton = Button(this).apply {
            text = "Recarregar modelos"
            textSize = 12f
            isAllCaps = false
            setOnClickListener { showModelListDialog() }
        }

        resetHistoryButton = Button(this).apply {
            text = "Resetar histórico"
            textSize = 12f
            isAllCaps = false
            setOnClickListener { resetChatHistory() }
        }

        topBar.addView(titleRow, LinearLayout.LayoutParams(-1, dp(48)))
        topBar.addView(generationStatsText, LinearLayout.LayoutParams(-1, dp(24)))

        chat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(20))
        }

        scrollView = ScrollView(this).apply {
            isFillViewport = true
            addView(chat)
        }

        addStatusMessage("Motor nativo: pronto", dark = true)
        addStatusMessage("Nenhum modelo carregado.", dark = false)

        input = EditText(this).apply {
            hint = "Digite uma mensagem..."
            textSize = 16f
            setTextColor(Color.rgb(32, 33, 36))
            setHintTextColor(Color.rgb(125, 125, 125))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            minLines = 1
            maxLines = 4
            setPadding(dp(16), dp(10), dp(8), dp(10))
            background = roundedBackground(Color.rgb(245, 245, 245), 24f)
        }

        sendButton = Button(this).apply {
            text = "➤"
            textSize = 22f
            isAllCaps = false
            setTextColor(Color.rgb(255, 255, 255))
            background = roundedBackground(Color.rgb(70, 70, 70), 22f)
            setPadding(0, 0, 0, 0)
            contentDescription = "Enviar mensagem"
            setOnClickListener { sendMessage() }
        }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else {
                false
            }
        }

        val inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(12), dp(8), dp(12), dp(10))
            background = roundedBackground(Color.rgb(245, 245, 245), 28f)

            addView(
                input,
                LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginEnd = dp(8)
                }
            )

            addView(
                sendButton,
                LinearLayout.LayoutParams(dp(44), dp(44))
            )
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(12))
            addView(
                inputBar,
                LinearLayout.LayoutParams(-1, -2)
            )
        }

        val mainContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)

            addView(topBar, LinearLayout.LayoutParams(-1, -2))
            addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(controls, LinearLayout.LayoutParams(-1, -2))
        }

        root = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(mainContent, FrameLayout.LayoutParams(-1, -1))
        }

        setupDrawer()
        loadChats()

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = max(bars.bottom, ime.bottom)
            )

            if (ime.bottom > 0) {
                scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
            }

            insets
        }

        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        refreshDrawerChats()

        AppLogger.write("MainActivity.onCreate completed")
    }

    private fun showEngineMenu() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        fun addAction(button: Button) {
            container.addView(
                button,
                LinearLayout.LayoutParams(-1, dp(48)).apply {
                    bottomMargin = dp(4)
                }
            )
        }

        addAction(importButtonForEngine())
        addAction(systemPromptButton)
        addAction(samplingButton)
        addAction(threadsButton)
        addAction(reloadModelsButton)
        addAction(resetHistoryButton)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Engine")
            .setView(container)
            .setNegativeButton("Fechar", null)
            .create()

        importButtonForEngine().apply {
            setOnClickListener {
                dialog.dismiss()
                openModelPicker()
            }
        }.also { importButton ->
            container.removeViewAt(0)
            container.addView(
                importButton,
                0,
                LinearLayout.LayoutParams(-1, dp(48)).apply {
                    bottomMargin = dp(4)
                }
            )
        }

        dialog.show()
    }

    private fun importButtonForEngine(): Button {
        return Button(this).apply {
            text = "Importar GGUF"
            textSize = 13f
            isAllCaps = false
        }
    }

    private fun setupDrawer() {
        drawerScrim = View(this).apply {
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            alpha = 0f
            visibility = View.GONE
            setOnClickListener { closeDrawer() }
        }

        drawerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            elevation = dp(12f)
            translationX = -dp(300f)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(12), dp(8))
        }

        val headerTitle = TextView(this).apply {
            text = "Chats"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(32, 33, 36))
        }

        val closeButton = TextView(this).apply {
            text = "×"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(70, 70, 70))
            setOnClickListener { closeDrawer() }
        }

        header.addView(headerTitle, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(closeButton, LinearLayout.LayoutParams(dp(48), dp(48)))

        val newChatButton = Button(this).apply {
            text = "+ Novo chat"
            textSize = 14f
            isAllCaps = false
            setOnClickListener { createNewChat() }
        }

        drawerChats = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(16))
        }

        val chatsScroll = ScrollView(this).apply {
            addView(drawerChats)
        }

        drawerPanel.addView(header, LinearLayout.LayoutParams(-1, -2))
        drawerPanel.addView(newChatButton, LinearLayout.LayoutParams(-1, dp(48)).apply {
            marginStart = dp(12)
            marginEnd = dp(12)
            bottomMargin = dp(8)
        })
        drawerPanel.addView(chatsScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(drawerScrim, FrameLayout.LayoutParams(-1, -1))
        root.addView(drawerPanel, FrameLayout.LayoutParams(dp(300), -1, Gravity.START))
    }

    private fun toggleDrawer() {
        if (drawerPanel.translationX < 0f) openDrawer() else closeDrawer()
    }

    private fun openDrawer() {
        refreshDrawerChats()
        drawerScrim.visibility = View.VISIBLE
        drawerScrim.animate().alpha(1f).setDuration(180).start()
        drawerPanel.animate().translationX(0f).setDuration(220).start()
    }

    private fun closeDrawer() {
        drawerScrim.animate().alpha(0f).setDuration(160).withEndAction {
            drawerScrim.visibility = View.GONE
        }.start()
        drawerPanel.animate().translationX(-dp(300f)).setDuration(200).start()
    }

    private fun createNewChat() {
        if (!input.isEnabled) return
        saveChats()
        if (nativeLoaded && modelLoaded) {
            resetConversation()
        }
        currentChat = null
        streamingText.clear()
        currentAssistantMessage = null
        streamingResponseStarted = false
        chat.removeAllViews()
        generationStatsText.text = "Tokens: 0  |  tok/s: —"
        addStatusMessage("Novo chat", dark = false)
        closeDrawer()
        scrollToBottom()
    }

    private fun refreshDrawerChats() {
        if (!::drawerChats.isInitialized) return
        drawerChats.removeAllViews()

        if (chatSessions.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Nenhum chat salvo ainda.\n\nA primeira mensagem cria um chat."
                textSize = 14f
                setTextColor(Color.rgb(120, 120, 120))
                setPadding(dp(12), dp(20), dp(12), dp(20))
            }
            drawerChats.addView(empty)
            return
        }

        for (session in chatSessions) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = roundedBackground(
                    if (session.id == currentChat?.id) Color.rgb(238, 238, 238) else Color.TRANSPARENT,
                    12f
                )
                setOnClickListener { selectChat(session) }
            }

            val title = TextView(this).apply {
                text = session.title
                textSize = 15f
                setTextColor(Color.rgb(35, 35, 35))
                maxLines = 2
            }

            val model = TextView(this).apply {
                text = if (session.modelName.isBlank()) "Modelo não disponível" else session.modelName
                textSize = 12f
                setTextColor(Color.rgb(120, 120, 120))
                setPadding(0, dp(3), 0, 0)
                maxLines = 1
            }

            item.addView(title, LinearLayout.LayoutParams(-1, -2))
            item.addView(model, LinearLayout.LayoutParams(-1, -2))
            drawerChats.addView(item, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(2)
            })
        }
    }

    private fun selectChat(session: ChatSession) {
        if (session.id == currentChat?.id) {
            closeDrawer()
            return
        }

        saveChats()
        closeDrawer()

        val targetModel = File(session.modelPath)
        if (!targetModel.exists()) {
            currentChat = session
            renderChatSession(session)
            addStatusMessage("Modelo deste chat não está mais disponível.", dark = false)
            return
        }

        input.isEnabled = false
        sendButton.isEnabled = false
        systemPromptButton.isEnabled = false
        samplingButton.isEnabled = false
        threadsButton.isEnabled = false
        reloadModelsButton.isEnabled = false
        resetHistoryButton.isEnabled = false

        Thread {
            try {
                if (!nativeLoaded) {
                    System.loadLibrary("llmbt")
                    nativeLoaded = true
                    setSystemPrompt(preferences.getString(SYSTEM_PROMPT_KEY, DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT)
                    setSamplingParams(
                        preferences.getFloat(TEMPERATURE_KEY, DEFAULT_TEMPERATURE),
                        preferences.getFloat(MIN_P_KEY, DEFAULT_MIN_P),
                        preferences.getFloat(REPEAT_PENALTY_KEY, DEFAULT_REPEAT_PENALTY),
                        preferences.getFloat(TOP_P_KEY, DEFAULT_TOP_P),
                        preferences.getInt(TOP_K_KEY, DEFAULT_TOP_K)
                    )
                    setGenerationTokens(
                        preferences.getInt(GENERATION_TOKENS_KEY, DEFAULT_GENERATION_TOKENS)
                            .coerceIn(MIN_GENERATION_TOKENS, MAX_GENERATION_TOKENS)
                    )
                }

                val generationThreads = preferences.getInt(GENERATION_THREADS_KEY, DEFAULT_GENERATION_THREADS)
                    .coerceIn(MIN_THREADS, MAX_THREADS)
                val batchThreads = preferences.getInt(BATCH_THREADS_KEY, DEFAULT_BATCH_THREADS)
                    .coerceIn(MIN_THREADS, MAX_THREADS)
                setThreadConfig(generationThreads, batchThreads)

                if (!modelLoaded || currentModelFile?.absolutePath != targetModel.absolutePath) {
                    val result = loadModel(targetModel.absolutePath)
                    if (!result.startsWith("Modelo carregado!")) {
                        throw IllegalStateException(result)
                    }
                }

                val roles = session.messages.map { it.role }.toTypedArray()
                val contents = session.messages.map { it.content }.toTypedArray()
                restoreConversationHistory(roles, contents)

                currentModelFile = targetModel
                modelLoaded = true
                currentChat = session

                runOnUiThread {
                    renderChatSession(session)
                    generationStatsText.text = "Tokens: 0  |  tok/s: —  |  threads: " + generationThreads
                    input.isEnabled = true
                    sendButton.isEnabled = true
                    systemPromptButton.isEnabled = true
                    samplingButton.isEnabled = true
                    threadsButton.isEnabled = true
                    reloadModelsButton.isEnabled = true
                    resetHistoryButton.isEnabled = true
                    refreshDrawerChats()
                }
            } catch (throwable: Throwable) {
                AppLogger.exception("CHAT LOAD FAILED", throwable)
                runOnUiThread {
                    addStatusMessage("ERRO ao abrir chat: " + (throwable.message ?: throwable.javaClass.simpleName), dark = false)
                    input.isEnabled = true
                    sendButton.isEnabled = true
                    systemPromptButton.isEnabled = true
                    samplingButton.isEnabled = true
                    threadsButton.isEnabled = true
                    reloadModelsButton.isEnabled = true
                    resetHistoryButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun renderChatSession(session: ChatSession) {
        chat.removeAllViews()
        currentAssistantMessage = null
        streamingResponseStarted = false
        streamingText.clear()

        for (message in session.messages) {
            if (message.role == "user") {
                addUserMessage(message.content)
            } else if (message.role == "assistant") {
                addAssistantMessage("LLM: " + message.content, loading = false)
            }
        }
        scrollToBottom()
    }

    private fun loadChats() {
        val file = File(filesDir, CHATS_FILE)
        if (!file.exists()) return

        try {
            val array = JSONArray(file.readText())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val messages = mutableListOf<ChatMessage>()
                val msgArray = obj.optJSONArray("messages") ?: JSONArray()
                for (j in 0 until msgArray.length()) {
                    val msg = msgArray.getJSONObject(j)
                    messages.add(ChatMessage(msg.optString("role"), msg.optString("content")))
                }
                chatSessions.add(
                    ChatSession(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        title = obj.optString("title", "Novo chat"),
                        modelName = obj.optString("modelName", ""),
                        modelPath = obj.optString("modelPath", ""),
                        messages = messages
                    )
                )
            }
        } catch (throwable: Throwable) {
            AppLogger.exception("CHAT STORE LOAD FAILED", throwable)
        }
    }

    private fun saveChats() {
        try {
            val array = JSONArray()
            for (session in chatSessions) {
                val obj = JSONObject()
                    .put("id", session.id)
                    .put("title", session.title)
                    .put("modelName", session.modelName)
                    .put("modelPath", session.modelPath)

                val messages = JSONArray()
                for (message in session.messages) {
                    messages.put(
                        JSONObject()
                            .put("role", message.role)
                            .put("content", message.content)
                    )
                }
                obj.put("messages", messages)
                array.put(obj)
            }
            File(filesDir, CHATS_FILE).writeText(array.toString())
        } catch (throwable: Throwable) {
            AppLogger.exception("CHAT STORE SAVE FAILED", throwable)
        }
    }

    private fun sendMessage() {
        val message = input.text.toString().trim()
        if (message.isEmpty()) return

        if (!modelLoaded) {
            addUserMessage(message)
            addStatusMessage("Importe um modelo GGUF primeiro.", dark = false)
            input.text.clear()
            return
        }

        if (currentChat == null) {
            val model = currentModelFile
            currentChat = ChatSession(
                id = UUID.randomUUID().toString(),
                title = message.take(48),
                modelName = model?.name ?: "Sem modelo",
                modelPath = model?.absolutePath ?: ""
            )
            chatSessions.add(0, currentChat!!)
            refreshDrawerChats()
        }

        currentChat?.messages?.add(ChatMessage("user", message))
        saveChats()
        streamingText.clear()

        input.isEnabled = false
        sendButton.isEnabled = false
        systemPromptButton.isEnabled = false
        samplingButton.isEnabled = false
        threadsButton.isEnabled = false
        reloadModelsButton.isEnabled = false
        resetHistoryButton.isEnabled = false
        streamingResponseStarted = false
        addUserMessage(message)
        currentAssistantMessage = addAssistantMessage("LLM: gerando...", loading = true)
        input.text.clear()
        generationStatsText.text = "Tokens: 0  |  tok/s: —"
        AppLogger.write("Generation requested")

        Thread {
            try {
                val result = generateText(message)
                AppLogger.write("Generation result: " + result.replace("\n", " | "))
                runOnUiThread {
                    if (!streamingResponseStarted) {
                        currentAssistantMessage?.apply {
                            text = "LLM: $result"
                            setTextColor(Color.rgb(32, 33, 36))
                        }
                    }

                    val assistantContent = if (streamingResponseStarted) {
                        streamingText.toString()
                    } else {
                        result.substringBefore("\n\n[perf]")
                    }
                    if (assistantContent.isNotBlank()) {
                        currentChat?.messages?.add(ChatMessage("assistant", assistantContent))
                        saveChats()
                        refreshDrawerChats()
                    }
                    input.isEnabled = true
                    sendButton.isEnabled = true
                    systemPromptButton.isEnabled = true
                    samplingButton.isEnabled = true
                    threadsButton.isEnabled = true
                    reloadModelsButton.isEnabled = true
                    resetHistoryButton.isEnabled = true
                    scrollToBottom()
                }
            } catch (throwable: Throwable) {
                AppLogger.exception("TEXT GENERATION FAILED", throwable)
                saveChats()
                runOnUiThread {
                    currentAssistantMessage?.apply {
                        text = "ERRO: " + (throwable.message ?: throwable.javaClass.simpleName)
                        setTextColor(Color.rgb(170, 40, 40))
                    } ?: addStatusMessage(
                        "ERRO: " + (throwable.message ?: throwable.javaClass.simpleName),
                        dark = false
                    )
                    input.isEnabled = true
                    sendButton.isEnabled = true
                    systemPromptButton.isEnabled = true
                    samplingButton.isEnabled = true
                    threadsButton.isEnabled = true
                    reloadModelsButton.isEnabled = true
                    resetHistoryButton.isEnabled = true
                    scrollToBottom()
                }
            }
        }.start()
    }



    fun appendGeneratedToken(piece: String) {
        streamingText.append(piece)
        runOnUiThread {
            currentAssistantMessage?.let { messageView ->
                if (!streamingResponseStarted) {
                    messageView.text = "LLM:"
                    messageView.setTextColor(Color.rgb(32, 33, 36))
                    streamingResponseStarted = true
                }
                messageView.append(piece)
                scrollToBottom()
            }
        }
    }

    fun updateGenerationStats(tokens: Int, tokensPerSecond: Double) {
        runOnUiThread {
            val speed = if (tokensPerSecond > 0.0) {
                String.format(java.util.Locale.US, "%.1f", tokensPerSecond)
            } else {
                "—"
            }
            generationStatsText.text = "Tokens: $tokens  |  tok/s: $speed"
        }
    }

    private fun addUserMessage(message: String) {
        val bubble = TextView(this).apply {
            text = "Você\n$message"
            textSize = 16f
            setTextColor(Color.rgb(35, 35, 35))
            setPadding(dp(16), dp(11), dp(16), dp(11))
            background = roundedBackground(Color.rgb(232, 232, 232), 18f)
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.END
            topMargin = dp(8)
            bottomMargin = dp(2)
            marginStart = dp(48)
        }

        chat.addView(bubble, params)
        scrollToBottom()
    }

    private fun addAssistantMessage(message: String, loading: Boolean): TextView {
        val view = TextView(this).apply {
            text = message
            textSize = 16f
            setTextColor(
                if (loading) Color.rgb(125, 125, 125)
                else Color.rgb(32, 33, 36)
            )
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(6)
            bottomMargin = dp(2)
        }

        chat.addView(view, params)
        scrollToBottom()
        return view
    }

    private fun addStatusMessage(message: String, dark: Boolean) {
        val view = TextView(this).apply {
            text = message
            textSize = if (dark) 15f else 14f
            setTextColor(
                if (dark) Color.rgb(75, 75, 75)
                else Color.rgb(145, 145, 145)
            )
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        chat.addView(
            view,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(2)
                bottomMargin = dp(2)
            }
        )
        scrollToBottom()
    }

    private fun showSystemPromptDialog() {
        val editor = EditText(this).apply {
            setText(preferences.getString(SYSTEM_PROMPT_KEY, DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT)
            textSize = 15f
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 12
            maxLines = 18
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBackground(Color.rgb(245, 245, 245), 12f)
            setSelectAllOnFocus(false)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
            addView(editor, LinearLayout.LayoutParams(-1, dp(260)))
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("System prompt")
            .setView(container)
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Restaurar padrão", null)
            .setPositiveButton("Salvar", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                editor.setText(DEFAULT_SYSTEM_PROMPT)
                editor.setSelection(editor.text.length)
            }

            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val prompt = editor.text.toString().trim()
                if (prompt.isEmpty()) {
                    editor.error = "O system prompt não pode ficar vazio."
                    return@setOnClickListener
                }

                preferences.edit().putString(SYSTEM_PROMPT_KEY, prompt).apply()

                if (nativeLoaded) {
                    setSystemPrompt(prompt)
                }

                addStatusMessage("System prompt atualizado.", dark = false)
                AppLogger.write("System prompt updated from UI")
                dialog.dismiss()
            }

            editor.requestFocus()
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }

        dialog.show()
    }




    private fun showThreadsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
        }

        val generationLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(75, 75, 75))
            setPadding(0, dp(8), 0, dp(2))
        }

        val generationSeekBar = android.widget.SeekBar(this).apply {
            max = MAX_THREADS - MIN_THREADS
            progress = preferences.getInt(
                GENERATION_THREADS_KEY,
                DEFAULT_GENERATION_THREADS
            ).coerceIn(MIN_THREADS, MAX_THREADS) - MIN_THREADS
        }

        val batchLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(75, 75, 75))
            setPadding(0, dp(8), 0, dp(2))
        }

        val batchSeekBar = android.widget.SeekBar(this).apply {
            max = MAX_THREADS - MIN_THREADS
            progress = preferences.getInt(
                BATCH_THREADS_KEY,
                DEFAULT_BATCH_THREADS
            ).coerceIn(MIN_THREADS, MAX_THREADS) - MIN_THREADS
        }

        fun updateLabels() {
            generationLabel.text = "Threads de geração: ${MIN_THREADS + generationSeekBar.progress}"
            batchLabel.text = "Threads de prompt/batch: ${MIN_THREADS + batchSeekBar.progress}"
        }

        generationSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) = updateLabels()
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
        })

        batchSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) = updateLabels()
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
        })

        updateLabels()

        container.addView(generationLabel, LinearLayout.LayoutParams(-1, -2))
        container.addView(generationSeekBar, LinearLayout.LayoutParams(-1, dp(48)))
        container.addView(batchLabel, LinearLayout.LayoutParams(-1, -2))
        container.addView(batchSeekBar, LinearLayout.LayoutParams(-1, dp(48)))

        val note = TextView(this).apply {
            text = "O valor de geração afeta o tok/s. O batch é usado principalmente no processamento do prompt. Recarregue o modelo para aplicar."
            textSize = 12f
            setTextColor(Color.rgb(120, 120, 120))
            setPadding(0, dp(8), 0, dp(4))
        }
        container.addView(note, LinearLayout.LayoutParams(-1, -2))

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Threads")
            .setView(container)
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Restaurar padrão", null)
            .setPositiveButton("Salvar", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                generationSeekBar.progress = DEFAULT_GENERATION_THREADS - MIN_THREADS
                batchSeekBar.progress = DEFAULT_BATCH_THREADS - MIN_THREADS
                updateLabels()
            }

            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val generation = MIN_THREADS + generationSeekBar.progress
                val batch = MIN_THREADS + batchSeekBar.progress

                preferences.edit()
                    .putInt(GENERATION_THREADS_KEY, generation)
                    .putInt(BATCH_THREADS_KEY, batch)
                    .apply()

                generationStatsText.text = "Tokens: 0  |  tok/s: —  |  threads: $generation"
                addStatusMessage(
                    "Threads salvas: geração=$generation, batch=$batch. Recarregue o modelo para aplicar.",
                    dark = false
                )
                AppLogger.write("Threads updated: generation=$generation batch=$batch")
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun showSamplingDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
        }

        fun addField(label: String, value: String): EditText {
            container.addView(TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(Color.rgb(75, 75, 75))
                setPadding(0, dp(6), 0, dp(2))
            })
            return EditText(this).apply {
                setText(value)
                textSize = 15f
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    InputType.TYPE_NUMBER_FLAG_SIGNED
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = roundedBackground(Color.rgb(245, 245, 245), 10f)
                container.addView(this, LinearLayout.LayoutParams(-1, dp(46)))
            }
        }

        val temperature = addField("Temperature (0 = determinístico)",
            preferences.getFloat(TEMPERATURE_KEY, DEFAULT_TEMPERATURE).toString())
        val minP = addField("Min-P (0 = desativado)",
            preferences.getFloat(MIN_P_KEY, DEFAULT_MIN_P).toString())
        val repeatPenalty = addField("Repetition penalty (1 = desativado)",
            preferences.getFloat(REPEAT_PENALTY_KEY, DEFAULT_REPEAT_PENALTY).toString())
        val topP = addField("Top-P (1 = desativado)",
            preferences.getFloat(TOP_P_KEY, DEFAULT_TOP_P).toString())
        val topK = addField("Top-K (0 = desativado)",
            preferences.getInt(TOP_K_KEY, DEFAULT_TOP_K).toString())

        val generationLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(75, 75, 75))
            setPadding(0, dp(10), 0, dp(2))
        }

        val generationSeekBar = android.widget.SeekBar(this).apply {
            max = (MAX_GENERATION_TOKENS - MIN_GENERATION_TOKENS) / GENERATION_TOKEN_STEP
            progress = (
                preferences.getInt(GENERATION_TOKENS_KEY, DEFAULT_GENERATION_TOKENS)
                    .coerceIn(MIN_GENERATION_TOKENS, MAX_GENERATION_TOKENS)
                    .let { (it - MIN_GENERATION_TOKENS) / GENERATION_TOKEN_STEP }
            )
        }

        fun updateGenerationLabel() {
            val value = MIN_GENERATION_TOKENS + generationSeekBar.progress * GENERATION_TOKEN_STEP
            generationLabel.text = "Tokens de geração: $value"
        }

        generationSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                updateGenerationLabel()
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
        })

        updateGenerationLabel()
        container.addView(generationLabel, LinearLayout.LayoutParams(-1, -2))
        container.addView(generationSeekBar, LinearLayout.LayoutParams(-1, dp(48)))

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Sampling")
            .setView(container)
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Restaurar padrão", null)
            .setPositiveButton("Salvar", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                temperature.setText(DEFAULT_TEMPERATURE.toString())
                minP.setText(DEFAULT_MIN_P.toString())
                repeatPenalty.setText(DEFAULT_REPEAT_PENALTY.toString())
                topP.setText(DEFAULT_TOP_P.toString())
                topK.setText(DEFAULT_TOP_K.toString())
                generationSeekBar.progress =
                    (DEFAULT_GENERATION_TOKENS - MIN_GENERATION_TOKENS) / GENERATION_TOKEN_STEP
                updateGenerationLabel()
            }

            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val tempValue = temperature.text.toString().toFloatOrNull()
                val minPValue = minP.text.toString().toFloatOrNull()
                val repeatValue = repeatPenalty.text.toString().toFloatOrNull()
                val topPValue = topP.text.toString().toFloatOrNull()
                val topKValue = topK.text.toString().toIntOrNull()
                val generationValue =
                    MIN_GENERATION_TOKENS + generationSeekBar.progress * GENERATION_TOKEN_STEP

                if (tempValue == null || tempValue < 0f || tempValue > 2f) {
                    temperature.error = "Use um valor entre 0 e 2."
                    return@setOnClickListener
                }
                if (minPValue == null || minPValue < 0f || minPValue > 1f) {
                    minP.error = "Use um valor entre 0 e 1."
                    return@setOnClickListener
                }
                if (repeatValue == null || repeatValue <= 0f || repeatValue > 2f) {
                    repeatPenalty.error = "Use um valor maior que 0 e até 2."
                    return@setOnClickListener
                }
                if (topPValue == null || topPValue <= 0f || topPValue > 1f) {
                    topP.error = "Use um valor maior que 0 e até 1."
                    return@setOnClickListener
                }
                if (topKValue == null || topKValue < 0 || topKValue > 1000) {
                    topK.error = "Use um inteiro entre 0 e 1000."
                    return@setOnClickListener
                }

                preferences.edit()
                    .putFloat(TEMPERATURE_KEY, tempValue)
                    .putFloat(MIN_P_KEY, minPValue)
                    .putFloat(REPEAT_PENALTY_KEY, repeatValue)
                    .putFloat(TOP_P_KEY, topPValue)
                    .putInt(TOP_K_KEY, topKValue)
                    .putInt(GENERATION_TOKENS_KEY, generationValue)
                    .apply()

                if (nativeLoaded) {
                    setSamplingParams(tempValue, minPValue, repeatValue, topPValue, topKValue)
                    setGenerationTokens(generationValue)
                }

                addStatusMessage("Sampling atualizado. Tokens de geração: $generationValue.", dark = false)
                AppLogger.write("Sampling updated: temp=$tempValue min_p=$minPValue repeat=$repeatValue top_p=$topPValue top_k=$topKValue generation_tokens=$generationValue")
                dialog.dismiss()
            }
        }

        dialog.show()
    }


    private fun showModelListDialog() {
        val modelsDir = File(filesDir, "models")
        val models = modelsDir.listFiles()
            ?.filter { it.isFile && it.name.lowercase().endsWith(".gguf") }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

        if (models.isEmpty()) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Modelos")
                .setMessage("Nenhum GGUF foi salvo no app ainda.")
                .setNegativeButton("Fechar", null)
                .setPositiveButton("Importar GGUF") { _, _ -> openModelPicker() }
                .show()
            return
        }

        val names = models.map { file ->
            val sizeMb = file.length() / (1024L * 1024L)
            "${file.name}  (${sizeMb} MB)"
        }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Recarregar modelos")
            .setItems(names) { _, which ->
                loadPrivateModel(models[which])
            }
            .setNeutralButton("Importar novo GGUF") { _, _ -> openModelPicker() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun loadPrivateModel(modelFile: File) {
        if (!modelFile.exists()) {
            addStatusMessage("ERRO: modelo não encontrado: ${modelFile.name}", dark = false)
            return
        }

        reloadModelsButton.isEnabled = false
        resetHistoryButton.isEnabled = false
        systemPromptButton.isEnabled = false
        samplingButton.isEnabled = false
        threadsButton.isEnabled = false
        sendButton.isEnabled = false
        input.isEnabled = false

        addStatusMessage("Carregando modelo: ${modelFile.name}", dark = false)
        AppLogger.write("Reloading private GGUF: " + modelFile.absolutePath)

        Thread {
            try {
                if (!nativeLoaded) {
                    System.loadLibrary("llmbt")
                    nativeLoaded = true
                    setSystemPrompt(
                        preferences.getString(SYSTEM_PROMPT_KEY, DEFAULT_SYSTEM_PROMPT)
                            ?: DEFAULT_SYSTEM_PROMPT
                    )
                    setSamplingParams(
                        preferences.getFloat(TEMPERATURE_KEY, DEFAULT_TEMPERATURE),
                        preferences.getFloat(MIN_P_KEY, DEFAULT_MIN_P),
                        preferences.getFloat(REPEAT_PENALTY_KEY, DEFAULT_REPEAT_PENALTY),
                        preferences.getFloat(TOP_P_KEY, DEFAULT_TOP_P),
                        preferences.getInt(TOP_K_KEY, DEFAULT_TOP_K)
                    )
                    setGenerationTokens(
                        preferences.getInt(GENERATION_TOKENS_KEY, DEFAULT_GENERATION_TOKENS)
                            .coerceIn(MIN_GENERATION_TOKENS, MAX_GENERATION_TOKENS)
                    )
                }

                val generationThreads = preferences.getInt(
                    GENERATION_THREADS_KEY,
                    DEFAULT_GENERATION_THREADS
                ).coerceIn(MIN_THREADS, MAX_THREADS)
                val batchThreads = preferences.getInt(
                    BATCH_THREADS_KEY,
                    DEFAULT_BATCH_THREADS
                ).coerceIn(MIN_THREADS, MAX_THREADS)
                setThreadConfig(generationThreads, batchThreads)

                val result = loadModel(modelFile.absolutePath)
                val loaded = result.startsWith("Modelo carregado!")
                modelLoaded = loaded
                if (loaded) {
                    currentModelFile = modelFile
                }

                AppLogger.write("Native reload result: " + result.replace("\n", " | "))

                runOnUiThread {
                    if (loaded) {
                        generationStatsText.text = "Tokens: 0  |  tok/s: —  |  threads: $generationThreads"
                    }
                    addStatusMessage(result, dark = loaded)
                    input.isEnabled = true
                    sendButton.isEnabled = true
                    systemPromptButton.isEnabled = true
                    samplingButton.isEnabled = true
                    threadsButton.isEnabled = true
                    reloadModelsButton.isEnabled = true
                    resetHistoryButton.isEnabled = true
                    scrollToBottom()
                }
            } catch (throwable: Throwable) {
                AppLogger.exception("MODEL RELOAD FAILED", throwable)
                runOnUiThread {
                    addStatusMessage(
                        "ERRO: " + (throwable.message ?: throwable.javaClass.simpleName),
                        dark = false
                    )
                    input.isEnabled = true
                    sendButton.isEnabled = true
                    systemPromptButton.isEnabled = true
                    samplingButton.isEnabled = true
                    threadsButton.isEnabled = true
                    reloadModelsButton.isEnabled = true
                    resetHistoryButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun resetChatHistory() {
        if (!nativeLoaded || !modelLoaded) {
            chat.removeAllViews()
            currentAssistantMessage = null
            streamingResponseStarted = false
            generationStatsText.text = "Tokens: 0  |  tok/s: —"
            addStatusMessage("Histórico visual resetado. Nenhum modelo carregado.", dark = false)
            return
        }

        resetHistoryButton.isEnabled = false
        sendButton.isEnabled = false
        input.isEnabled = false

        Thread {
            try {
                resetConversation()
                runOnUiThread {
                    chat.removeAllViews()
                    currentAssistantMessage = null
                    streamingResponseStarted = false
                    generationStatsText.text = "Tokens: 0  |  tok/s: —"
                    addStatusMessage("Histórico resetado. O modelo continua carregado.", dark = true)
                    input.isEnabled = true
                    sendButton.isEnabled = true
                    resetHistoryButton.isEnabled = true
                    currentChat?.messages?.clear()
                    saveChats()
                    refreshDrawerChats()
                    scrollToBottom()
                }
                AppLogger.write("Conversation history reset")
            } catch (throwable: Throwable) {
                AppLogger.exception("RESET HISTORY FAILED", throwable)
                runOnUiThread {
                    addStatusMessage(
                        "ERRO ao resetar histórico: " +
                            (throwable.message ?: throwable.javaClass.simpleName),
                        dark = false
                    )
                    input.isEnabled = true
                    sendButton.isEnabled = true
                    resetHistoryButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun openModelPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, PICK_MODEL)
        AppLogger.write("GGUF picker opened")
    }

    @Deprecated("Using legacy activity result for minSdk compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != PICK_MODEL || resultCode != RESULT_OK) {
            return
        }

        val uri = data?.data ?: return
        importAndLoadModel(uri)
    }

    private fun importAndLoadModel(uri: Uri) {
        addStatusMessage("Importando modelo GGUF...", dark = false)
        addStatusMessage("Copiando arquivo para o armazenamento privado do app...", dark = false)
        AppLogger.write("GGUF selected: $uri")

        Thread {
            try {
                val modelsDir = File(filesDir, "models").apply { mkdirs() }

                val displayName = queryDisplayName(uri)
                val safeName = (displayName ?: "model.gguf")
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                require(safeName.lowercase().endsWith(".gguf")) {
                    "Selecione um arquivo .gguf."
                }

                val modelFile = File(modelsDir, safeName)

                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Não foi possível abrir o arquivo selecionado." }
                    modelFile.outputStream().use { output ->
                        input.copyTo(output, 1024 * 1024)
                    }
                }

                runOnUiThread {
                    addStatusMessage("Arquivo copiado. Inicializando llama.cpp...", dark = false)
                }

                if (!nativeLoaded) {
                    AppLogger.write("Loading native library: llmbt")
                    System.loadLibrary("llmbt")
                    nativeLoaded = true
                    setSystemPrompt(preferences.getString(SYSTEM_PROMPT_KEY, DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT)
                    setSamplingParams(
                        preferences.getFloat(TEMPERATURE_KEY, DEFAULT_TEMPERATURE),
                        preferences.getFloat(MIN_P_KEY, DEFAULT_MIN_P),
                        preferences.getFloat(REPEAT_PENALTY_KEY, DEFAULT_REPEAT_PENALTY),
                        preferences.getFloat(TOP_P_KEY, DEFAULT_TOP_P),
                        preferences.getInt(TOP_K_KEY, DEFAULT_TOP_K)
                    )
                    setGenerationTokens(
                        preferences.getInt(GENERATION_TOKENS_KEY, DEFAULT_GENERATION_TOKENS)
                            .coerceIn(MIN_GENERATION_TOKENS, MAX_GENERATION_TOKENS)
                    )
                    AppLogger.write("Native library loaded successfully")
                }

                val generationThreads = preferences.getInt(
                    GENERATION_THREADS_KEY,
                    DEFAULT_GENERATION_THREADS
                ).coerceIn(MIN_THREADS, MAX_THREADS)
                val batchThreads = preferences.getInt(
                    BATCH_THREADS_KEY,
                    DEFAULT_BATCH_THREADS
                ).coerceIn(MIN_THREADS, MAX_THREADS)
                setThreadConfig(generationThreads, batchThreads)

                runOnUiThread {
                    addStatusMessage("Carregando modelo na memória...", dark = false)
                }

                AppLogger.write("Loading GGUF: " + modelFile.absolutePath)
                val result = loadModel(modelFile.absolutePath)
                AppLogger.write("Native load result: " + result.replace("\n", " | "))
                modelLoaded = result.startsWith("Modelo carregado!")
                if (modelLoaded) {
                    currentModelFile = modelFile
                    currentChat?.let {
                        it.modelName = modelFile.name
                        it.modelPath = modelFile.absolutePath
                        saveChats()
                    }
                }

                runOnUiThread {
                    if (modelLoaded) {
                        generationStatsText.text = "Tokens: 0  |  tok/s: —  |  threads: $generationThreads"
                    }
                    addStatusMessage(result, dark = modelLoaded)
                }
            } catch (throwable: Throwable) {
                AppLogger.exception("GGUF IMPORT/LOAD FAILED", throwable)
                runOnUiThread {
                    addStatusMessage(
                        "ERRO: " + (throwable.message ?: throwable.javaClass.simpleName),
                        dark = false
                    )
                }
            }
        }.start()
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return null
    }

    override fun onPause() {
        saveChats()
        super.onPause()
    }

    @Deprecated("Use OnBackPressedDispatcher on newer navigation flows")
    override fun onBackPressed() {
        if (::drawerPanel.isInitialized && drawerPanel.translationX >= 0f) {
            closeDrawer()
        } else {
            super.onBackPressed()
        }
    }

    private fun roundedBackground(color: Int, radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density

    private fun scrollToBottom() {
        scrollView.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun Float.roundToInt(): Int = kotlin.math.round(this).toInt()
}
