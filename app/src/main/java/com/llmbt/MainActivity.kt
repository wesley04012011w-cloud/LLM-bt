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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import java.io.File
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private lateinit var chat: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private lateinit var systemPromptButton: Button
    private lateinit var root: LinearLayout
    private lateinit var preferences: android.content.SharedPreferences
    private var nativeLoaded = false
    private var modelLoaded = false
    @Volatile private var streamingResponseStarted = false
    private var currentAssistantMessage: TextView? = null

    private external fun stringFromNative(): String
    private external fun loadModel(path: String): String
    private external fun generateText(prompt: String): String
    private external fun setSystemPrompt(prompt: String)

    companion object {
        private const val PICK_MODEL = 1001
        private const val PREFS_NAME = "llm_bt_settings"
        private const val SYSTEM_PROMPT_KEY = "system_prompt"
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
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(8))
        }

        val title = TextView(this).apply {
            text = "LLM BT"
            textSize = 20f
            setTextColor(Color.rgb(32, 33, 36))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val importButton = Button(this).apply {
            text = "Carregar modelo"
            textSize = 13f
            isAllCaps = false
            setOnClickListener { openModelPicker() }
        }

        topBar.addView(
            title,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )
        systemPromptButton = Button(this).apply {
            text = "System"
            textSize = 13f
            isAllCaps = false
            setOnClickListener { showSystemPromptDialog() }
        }

        topBar.addView(
            systemPromptButton,
            LinearLayout.LayoutParams(dp(88), dp(48)).apply {
                marginEnd = dp(6)
            }
        )
        topBar.addView(
            importButton,
            LinearLayout.LayoutParams(dp(150), dp(48))
        )

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

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)

            addView(
                topBar,
                LinearLayout.LayoutParams(-1, -2)
            )
            addView(
                scrollView,
                LinearLayout.LayoutParams(-1, 0, 1f)
            )
            addView(
                controls,
                LinearLayout.LayoutParams(-1, -2)
            )
        }

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

        AppLogger.write("MainActivity.onCreate completed")
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

        input.isEnabled = false
        sendButton.isEnabled = false
        systemPromptButton.isEnabled = false
        streamingResponseStarted = false
        addUserMessage(message)
        currentAssistantMessage = addAssistantMessage("LLM: gerando...", loading = true)
        input.text.clear()
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
                    input.isEnabled = true
                    sendButton.isEnabled = true
                    systemPromptButton.isEnabled = true
                    scrollToBottom()
                }
            } catch (throwable: Throwable) {
                AppLogger.exception("TEXT GENERATION FAILED", throwable)
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
                    scrollToBottom()
                }
            }
        }.start()
    }

    fun appendGeneratedToken(piece: String) {
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
                    AppLogger.write("Native library loaded successfully")
                }

                runOnUiThread {
                    addStatusMessage("Carregando modelo na memória...", dark = false)
                }

                AppLogger.write("Loading GGUF: " + modelFile.absolutePath)
                val result = loadModel(modelFile.absolutePath)
                AppLogger.write("Native load result: " + result.replace("\n", " | "))
                modelLoaded = result.startsWith("Modelo carregado!")

                runOnUiThread {
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
