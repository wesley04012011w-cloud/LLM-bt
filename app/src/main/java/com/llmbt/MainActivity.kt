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

class MainActivity : AppCompatActivity() {
    private lateinit var chat: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private lateinit var root: LinearLayout
    private var nativeLoaded = false
    private var modelLoaded = false
    @Volatile private var streamingResponseStarted = false
    private var currentAssistantMessage: TextView? = null

    private external fun stringFromNative(): String
    private external fun loadModel(path: String): String
    private external fun generateText(prompt: String): String

    companion object {
        private const val PICK_MODEL = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppLogger.write("MainActivity.onCreate started")
        AppLogger.write("Native engine will be loaded on demand")

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
        topBar.addView(
            importButton,
            LinearLayout.LayoutParams(dp(150), dp(48))
        )

        chat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(20))
        }

        addStatusMessage("Motor nativo: pronto", dark = true)
        addStatusMessage("Nenhum modelo carregado.", dark = false)

        scrollView = ScrollView(this).apply {
            isFillViewport = true
            addView(
                chat,
                ScrollView.LayoutParams(-1, -2)
            )
        }

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
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom
            )
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
        streamingResponseStarted = false
        currentAssistantMessage = addAssistantMessage("LLM: gerando...", loading = true)
        addUserMessage(message)
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
