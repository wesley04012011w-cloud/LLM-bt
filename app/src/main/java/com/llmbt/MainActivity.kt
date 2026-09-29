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
import androidx.appcompat.app.AppCompatDelegate
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
    private var currentAssistantStatsText: TextView? = null
    private var currentThinkingContainer: LinearLayout? = null
    private var currentThinkingMessage: TextView? = null
    private var thinkingActive = false
    private val thinkingParseBuffer = StringBuilder()

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
        private const val DARK_MODE_KEY = "dark_mode"
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
        private const val DEFAULT_SYSTEM_PROMPT = ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppLogger.write("MainActivity.onCreate started")
        AppLogger.write("Native engine will be loaded on demand")
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        delegate.localNightMode = if (preferences.getBoolean(DARK_MODE_KEY, false)) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }
        applySystemBarTheme()

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
            setTextColor(primaryTextColor())
            contentDescription = "Abrir chats"
            setOnClickListener { toggleDrawer() }
        }

        val title = TextView(this).apply {
            text = ""
            textSize = 20f
            setTextColor(primaryTextColor())
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val engineButton = TextView(this).apply {
            text = "⚙"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(primaryTextColor())
            contentDescription = "Abrir engine"
            setOnClickListener { showEngineMenu() }
        }

        titleRow.addView(menuButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        titleRow.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        titleRow.addView(engineButton, LinearLayout.LayoutParams(dp(48), dp(48)))

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

        chat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(20))
        }

        scrollView = ScrollView(this).apply {
            isFillViewport = true
            addView(chat)
        }


        input = EditText(this).apply {
            hint = "Digite uma mensagem..."
            textSize = 16f
            setTextColor(primaryTextColor())
            setHintTextColor(hintTextColor())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            minLines = 1
            maxLines = 4
            setPadding(dp(16), dp(10), dp(8), dp(10))
            background = roundedBackground(inputSurfaceColor(), 24f)
        }

        sendButton = Button(this).apply {
            text = "➤"
            textSize = 22f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = roundedBackground(sendButtonColor(), 22f)
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
            background = roundedBackground(inputSurfaceColor(), 28f)

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
            setBackgroundColor(backgroundColor())

            addView(topBar, LinearLayout.LayoutParams(-1, -2))
            addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(controls, LinearLayout.LayoutParams(-1, -2))
        }

        root = FrameLayout(this).apply {
            setBackgroundColor(backgroundColor())
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

    private fun backgroundColor(): Int = if (isDarkMode()) Color.rgb(18, 18, 18) else Color.WHITE
    private fun primaryTextColor(): Int = if (isDarkMode()) Color.rgb(238, 238, 238) else Color.rgb(32, 33, 36)
    private fun secondaryTextColor(): Int = if (isDarkMode()) Color.rgb(170, 170, 170) else Color.rgb(110, 110, 110)
    private fun mutedTextColor(): Int = if (isDarkMode()) Color.rgb(145, 145, 145) else Color.rgb(145, 145, 145)
    private fun hintTextColor(): Int = if (isDarkMode()) Color.rgb(150, 150, 150) else Color.rgb(125, 125, 125)
    private fun inputSurfaceColor(): Int = if (isDarkMode()) Color.BLACK else Color.rgb(245, 245, 245)
    private fun sendButtonColor(): Int = if (isDarkMode()) Color.rgb(80, 80, 80) else Color.rgb(70, 70, 70)
    private fun userBubbleColor(): Int = if (isDarkMode()) Color.rgb(45, 45, 45) else Color.rgb(232, 232, 232)
    private fun selectedChatColor(): Int = if (isDarkMode()) Color.rgb(48, 48, 48) else Color.rgb(238, 238, 238)
    private fun isDarkMode(): Boolean = preferences.getBoolean(DARK_MODE_KEY, false)

    private fun applySystemBarTheme() {
        val dark = isDarkMode()
        window.statusBarColor = backgroundColor()
        window.navigationBarColor = backgroundColor()
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (dark) {
            0
        } else {
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private fun showEngineMenu() {
        lateinit var dialog: androidx.appcompat.app.AlertDialog

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        fun addAction(
            label: String,
            enabled: Boolean,
            action: () -> Unit
        ) {
            val button = Button(this).apply {
                text = label
                textSize = 13f
                isAllCaps = false
                isEnabled = enabled
                setOnClickListener { action() }
            }

            container.addView(
                button,
                LinearLayout.LayoutParams(-1, dp(48)).apply {
                    bottomMargin = dp(4)
                }
            )
        }

        addAction(if (isDarkMode()) "Modo claro" else "Modo escuro", true) {
            preferences.edit().putBoolean(DARK_MODE_KEY, !isDarkMode()).apply()
            delegate.localNightMode = if (isDarkMode()) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
            applySystemBarTheme()
            dialog.dismiss()
            recreate()
        }

        addAction("Importar GGUF", true) {
            dialog.dismiss()
            openModelPicker()
        }
        addAction("System", systemPromptButton.isEnabled) {
            dialog.dismiss()
            showSystemPromptDialog()
        }
        addAction("Sampling", samplingButton.isEnabled) {
            dialog.dismiss()
            showSamplingDialog()
        }
        addAction("Threads", threadsButton.isEnabled) {
            dialog.dismiss()
            showThreadsDialog()
        }
        addAction("Recarregar modelos", reloadModelsButton.isEnabled) {
            dialog.dismiss()
            showModelListDialog()
        }
        addAction("Resetar histórico", resetHistoryButton.isEnabled) {
            dialog.dismiss()
            resetChatHistory()
        }

        dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Engine")
            .setView(container)
            .setNegativeButton("Fechar", null)
            .create()

        dialog.show()
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
            setBackgroundColor(backgroundColor())
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
            setTextColor(primaryTextColor())
        }

        val closeButton = TextView(this).apply {
            text = "×"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(secondaryTextColor())
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
        currentAssistantStatsText = null
        currentThinkingContainer = null
        currentThinkingMessage = null
        thinkingActive = false
        thinkingParseBuffer.clear()
        streamingResponseStarted = false
        chat.removeAllViews()
        showTransientCard("Novo chat")
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
                setTextColor(secondaryTextColor())
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
                    if (session.id == currentChat?.id) selectedChatColor() else Color.TRANSPARENT,
                    12f
                )
                setOnClickListener { selectChat(session) }
            }

            val title = TextView(this).apply {
                text = session.title
                textSize = 15f
                setTextColor(primaryTextColor())
                maxLines = 2
            }

            val model = TextView(this).apply {
                text = if (session.modelName.isBlank()) "Modelo não disponível" else session.modelName
                textSize = 12f
                setTextColor(secondaryTextColor())
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
            showTransientCard("Modelo deste chat não está mais disponível.")
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
                    showTransientCard("ERRO ao abrir chat: " + throwable.message ?: throwable.javaClass.simpleName)
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
        currentAssistantStatsText = null
        currentThinkingContainer = null
        currentThinkingMessage = null
        thinkingActive = false
        streamingResponseStarted = false
        streamingText.clear()

        for (message in session.messages) {
            if (message.role == "user") {
                addUserMessage(message.content)
            } else if (message.role == "assistant") {
                val parsed = splitThinking(message.content)
                if (parsed.first.isNotBlank()) addThinkingMessage(parsed.first, false)
                addAssistantMessage(parsed.second, loading = false)
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
            showTransientCard("Importe um modelo GGUF primeiro.")
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
        currentAssistantMessage = addAssistantMessage("Reading...", loading = true)
        input.text.clear()
        AppLogger.write("Generation requested")

        Thread {
            try {
                val result = generateText(message)
                AppLogger.write("Generation result: " + result.replace("\n", " | "))
                runOnUiThread {
                    if (!streamingResponseStarted) {
                        val cleanResult = result.substringBefore("\n\n[perf]").trim()
                        val parsed = splitThinking(cleanResult)
                        if (parsed.first.isNotBlank()) addThinkingMessage(parsed.first, false)
                        currentAssistantMessage?.apply {
                            text = parsed.second
                            setTextColor(primaryTextColor())
                        }
                    }

                    val assistantContent = if (streamingResponseStarted) {
                        streamingText.toString()
                    } else {
                        splitThinking(result.substringBefore("\n\n[perf]").trim()).second
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
                        setTextColor(Color.rgb(235, 90, 90))
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
            }
        }.start()
    }



    fun appendGeneratedToken(piece: String) {
        runOnUiThread {
            processGeneratedPiece(piece)
            scrollToBottom()
        }
    }

    private fun processGeneratedPiece(piece: String) {
        thinkingParseBuffer.append(piece)

        while (thinkingParseBuffer.isNotEmpty()) {
            val text = thinkingParseBuffer.toString()

            if (thinkingActive) {
                val end = text.indexOf("</think>", ignoreCase = true)
                if (end >= 0) {
                    appendThinkingText(text.substring(0, end))
                    thinkingParseBuffer.delete(0, end + 8)
                    finishThinking()
                    continue
                }

                val keep = longestTagPrefixSuffix(text, "</think>")
                if (keep > 0) {
                    appendThinkingText(text.dropLast(keep))
                    thinkingParseBuffer.delete(0, text.length - keep)
                } else {
                    appendThinkingText(text)
                    thinkingParseBuffer.clear()
                }
                return
            }

            val start = text.indexOf("<think>", ignoreCase = true)
            if (start >= 0) {
                appendAnswerText(text.substring(0, start))
                thinkingParseBuffer.delete(0, start + 7)
                startThinking()
                continue
            }

            val keepOpen = longestTagPrefixSuffix(text, "<think>")
            val keepClose = longestTagPrefixSuffix(text, "</think>")
            val keep = max(keepOpen, keepClose)

            if (keep > 0) {
                appendAnswerText(text.dropLast(keep))
                thinkingParseBuffer.delete(0, text.length - keep)
            } else {
                appendAnswerText(text)
                thinkingParseBuffer.clear()
            }
            return
        }
    }

    private fun longestTagPrefixSuffix(text: String, tag: String): Int {
        val maxLength = minOf(text.length, tag.length - 1)
        for (length in maxLength downTo 1) {
            if (text.takeLast(length).equals(tag.take(length), ignoreCase = true)) {
                return length
            }
        }
        return 0
    }

    private fun startThinking() {
        thinkingActive = true
        if (currentThinkingContainer == null) addThinkingMessage("", true)
    }

    private fun appendThinkingText(text: String) {
        if (text.isNotEmpty()) currentThinkingMessage?.append(text)
    }

    private fun finishThinking() {
        thinkingActive = false
        currentThinkingMessage?.visibility = View.GONE
        currentThinkingContainer?.getChildAt(0)?.let { header ->
            (header as TextView).text = "Thinking"
        }
    }

    private fun appendAnswerText(text: String) {
        if (text.isEmpty()) return
        streamingText.append(text)
        currentAssistantMessage?.let { messageView ->
            if (!streamingResponseStarted) {
                messageView.text = ""
                messageView.setTextColor(primaryTextColor())
                streamingResponseStarted = true
            }
            messageView.append(text)
        }
    }

    private fun addThinkingMessage(message: String, expanded: Boolean) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBackground(inputSurfaceColor(), 14f)
        }
        val header = TextView(this).apply {
            text = if (expanded) "Thinking · aberto" else "Thinking"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(secondaryTextColor())
        }
        val body = TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(secondaryTextColor())
            setPadding(0, dp(8), 0, 0)
            visibility = if (expanded) View.VISIBLE else View.GONE
        }
        header.setOnClickListener {
            body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            header.text = if (body.visibility == View.VISIBLE) "Thinking · aberto" else "Thinking"
        }
        container.addView(header)
        container.addView(body)
        chat.addView(container, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(6)
            bottomMargin = dp(4)
        })
        currentThinkingContainer = container
        currentThinkingMessage = body
    }

    private fun splitThinking(content: String): Pair<String, String> {
        val start = content.indexOf("<think>", ignoreCase = true)
        if (start < 0) return "" to content
        val end = content.indexOf("</think>", start + 7, ignoreCase = true)
        if (end < 0) return content.substring(start + 7) to content.substring(0, start).trim()
        return content.substring(start + 7, end) to
            content.removeRange(start, end + 8).trim()
    }

    fun updateGenerationState(state: String) {
        runOnUiThread {
            currentAssistantMessage?.apply {
                if (state.isNotEmpty() && !streamingResponseStarted) {
                    text = state
                    setTextColor(hintTextColor())
                }
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
            currentAssistantStatsText?.apply {
                text = "Tokens: $tokens  ·  $speed tok/s"
                visibility = View.VISIBLE
            }
        }
    }

    private fun addUserMessage(message: String) {
        val bubble = TextView(this).apply {
            text = "Você\n$message"
            textSize = 16f
            setTextColor(primaryTextColor())
            setPadding(dp(16), dp(11), dp(16), dp(11))
            background = roundedBackground(userBubbleColor(), 18f)
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
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val view = TextView(this).apply {
            text = message
            textSize = 16f
            setTextColor(
                if (loading) hintTextColor()
                else primaryTextColor()
            )
            setPadding(dp(4), dp(8), dp(4), dp(2))
        }

        val stats = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(secondaryTextColor())
            setPadding(dp(4), dp(0), dp(4), dp(6))
            visibility = if (loading) View.VISIBLE else View.GONE
        }

        container.addView(view, LinearLayout.LayoutParams(-1, -2))
        container.addView(stats, LinearLayout.LayoutParams(-1, -2))

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(6)
            bottomMargin = dp(2)
        }

        chat.addView(container, params)
        if (loading) {
            currentAssistantStatsText = stats
        }
        scrollToBottom()
        return view
    }

    private fun showTransientCard(message: String) {
        val card = TextView(this).apply {
            text = message
            textSize = 13f
            setTextColor(primaryTextColor())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedBackground(inputSurfaceColor(), 14f)
            alpha = 0f
            elevation = dp(2f)
        }
        chat.addView(card, LinearLayout.LayoutParams(-1, dp(44)).apply {
            topMargin = dp(6)
            bottomMargin = dp(2)
        })
        card.animate().alpha(1f).setDuration(140).withEndAction {
            card.postDelayed({
                card.animate().alpha(0f).setDuration(180).withEndAction {
                    chat.removeView(card)
                }.start()
            }, 2000)
        }.start()
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
            background = roundedBackground(inputSurfaceColor(), 12f)
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
                preferences.edit().putString(SYSTEM_PROMPT_KEY, prompt).apply()

                if (nativeLoaded) {
                    setSystemPrompt(prompt)
                }

                showTransientCard(if (prompt.isEmpty()) "System prompt desativado." else "System prompt atualizado.")
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
            setTextColor(secondaryTextColor())
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
            setTextColor(secondaryTextColor())
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
            setTextColor(secondaryTextColor())
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

                showTransientCard("Threads salvas. Recarregue o modelo para aplicar.")
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
                setTextColor(secondaryTextColor())
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
                background = roundedBackground(inputSurfaceColor(), 10f)
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
            setTextColor(secondaryTextColor())
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

                showTransientCard("Sampling atualizado. Tokens: $generationValue.")
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
            showTransientCard("ERRO: modelo não encontrado.")
            return
        }

        reloadModelsButton.isEnabled = false
        resetHistoryButton.isEnabled = false
        systemPromptButton.isEnabled = false
        samplingButton.isEnabled = false
        threadsButton.isEnabled = false
        sendButton.isEnabled = false
        input.isEnabled = false

        showTransientCard("Carregando modelo...")
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
                    }
                    showTransientCard(if (loaded) "Modelo carregado." else result)
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
                    showTransientCard("ERRO: " + (throwable.message ?: throwable.javaClass.simpleName))
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
            currentAssistantStatsText = null
            streamingResponseStarted = false
            showTransientCard("Histórico visual resetado.")
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
                    currentAssistantStatsText = null
                    streamingResponseStarted = false
                    showTransientCard("Histórico resetado.")
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
                    showTransientCard("ERRO ao resetar histórico: " + (throwable.message ?: throwable.javaClass.simpleName))
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
        showTransientCard("Importando modelo GGUF...")
        showTransientCard("Copiando modelo...")
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
                    showTransientCard("Arquivo copiado. Inicializando llama.cpp...")
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
                    showTransientCard("Carregando modelo...")
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
                    }
                    showTransientCard(if (modelLoaded) "Modelo carregado." else result)
                }
            } catch (throwable: Throwable) {
                AppLogger.exception("GGUF IMPORT/LOAD FAILED", throwable)
                runOnUiThread {
                    showTransientCard("ERRO: " + (throwable.message ?: throwable.javaClass.simpleName))
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
