package com.llmbt

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var chat: TextView
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppLogger.write("MainActivity.onCreate started")
        AppLogger.write("DIAGNOSTIC MODE: native library load disabled")

        chat = TextView(this).apply {
            text = "LLM BT\n\nV0.1 diagnóstico\n\n" +
                "O app iniciou sem carregar o motor nativo.\n" +
                "Se esta tela aparecer, o problema está no carregamento do llama.cpp."
            textSize = 16f
            setPadding(24, 24, 24, 24)
        }

        input = EditText(this).apply {
            hint = "Digite uma mensagem..."
            isSingleLine = false
            minLines = 1
            maxLines = 4
        }

        val send = Button(this).apply {
            text = "Enviar"
            setOnClickListener {
                val message = input.text.toString().trim()
                if (message.isEmpty()) return@setOnClickListener

                chat.append(
                    "\n\nVocê: " + message +
                    "\nLLM: motor nativo desativado nesta build de diagnóstico."
                )
                input.text.clear()
                AppLogger.write("Message sent: " + message)
            }
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 8, 16, 16)
            addView(input, LinearLayout.LayoutParams(0, -2, 1f))
            addView(send, LinearLayout.LayoutParams(-2, -2))
        }

        val root = LinearLayout(this).apply {
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

        setContentView(root)
        AppLogger.write("MainActivity.onCreate completed")
    }
}
