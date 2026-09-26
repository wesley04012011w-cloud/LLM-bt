package com.llmbt

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
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
    private lateinit var chat: TextView
    private lateinit var input: EditText
    private lateinit var root: LinearLayout
    private var nativeLoaded = false
    private var modelLoaded = false
    @Volatile private var streamingResponseStarted = false

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

        chat = TextView(this).apply {
            text = "LLM BT\n\nMotor nativo: pronto\nNenhum modelo carregado."
            textSize = 16f
            setPadding(24, 24, 24, 24)
        }

        input = EditText(this).apply {
            hint = "Digite uma mensagem..."
            isSingleLine = false
            minLines = 1
            maxLines = 4
        }

        val importButton = Button(this).apply {
            text = "Importar GGUF"
            setOnClickListener {
                openModelPicker()
            }
        }

        val send = Button(this).apply {
            text = "Enviar"
            setOnClickListener { sendMessage() }
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 16)

            val buttons = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(importButton, LinearLayout.LayoutParams(0, -2, 1f))
                addView(send, LinearLayout.LayoutParams(0, -2, 1f))
            }

            addView(input, LinearLayout.LayoutParams(-1, -2))
            addView(buttons, LinearLayout.LayoutParams(-1, -2))
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())

            addView(
                ScrollView(this@MainActivity).apply {
                    addView(chat)
                },
                LinearLayout.LayoutParams(-1, 0, 1f)
            )

            addView(controls)
        }

        // Android 15/16 uses edge-to-edge by default. Apply system-bar insets
        // so the app content stays visually between the status and navigation bars.
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
            chat.append("\n\nVocê: $message\nLLM: importe um modelo GGUF primeiro.")
            input.text.clear()
            return
        }

        input.isEnabled = false
        streamingResponseStarted = false
        chat.append("\n\nVocê: $message\nLLM: gerando...")
        input.text.clear()
        AppLogger.write("Generation requested")

        Thread {
            try {
                val result = generateText(message)
                AppLogger.write("Generation result: " + result.replace("\n", " | "))
                runOnUiThread {
                    if (!streamingResponseStarted) {
                        chat.text = chat.text.toString().replace("LLM: gerando...", "LLM: $result")
                    }
                    input.isEnabled = true
                }
            } catch (throwable: Throwable) {
                AppLogger.exception("TEXT GENERATION FAILED", throwable)
                runOnUiThread {
                    chat.append("\nERRO: " + (throwable.message ?: throwable.javaClass.simpleName))
                    input.isEnabled = true
                }
            }
        }.start()
    }

    fun appendGeneratedToken(piece: String) {
        runOnUiThread {
            if (!streamingResponseStarted) {
                chat.text = chat.text.toString().replace("LLM: gerando...", "LLM:")
                streamingResponseStarted = true
            }
            chat.append(piece)
        }
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
        chat.append("\n\nImportando modelo GGUF...\nCopiando arquivo para o armazenamento privado do app...")
        AppLogger.write("GGUF selected: $uri")

        Thread {
            try {
                val modelsDir = File(filesDir, "models").apply { mkdirs() }

                val displayName = queryDisplayName(uri)
                val safeName = (displayName ?: "model.gguf")
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                val modelFile = File(modelsDir, safeName)

                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Não foi possível abrir o arquivo selecionado." }
                    modelFile.outputStream().use { output ->
                        input.copyTo(output, 1024 * 1024)
                    }
                }

                runOnUiThread {
                    chat.append("\nArquivo copiado. Inicializando llama.cpp...")
                }

                if (!nativeLoaded) {
                    AppLogger.write("Loading native library: llmbt")
                    System.loadLibrary("llmbt")
                    nativeLoaded = true
                    AppLogger.write("Native library loaded successfully")
                }

                runOnUiThread {
                    chat.append("\nCarregando modelo na memória...")
                }

                AppLogger.write("Loading GGUF: " + modelFile.absolutePath)
                val result = loadModel(modelFile.absolutePath)
                AppLogger.write("Native load result: " + result.replace("\n", " | "))
                modelLoaded = result.startsWith("Modelo carregado!")

                runOnUiThread {
                    chat.append("\n\n$result")
                }
            } catch (throwable: Throwable) {
                AppLogger.exception("GGUF IMPORT/LOAD FAILED", throwable)
                runOnUiThread {
                    chat.append("\n\nERRO:\n" + (throwable.message ?: throwable.javaClass.simpleName))
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
}
