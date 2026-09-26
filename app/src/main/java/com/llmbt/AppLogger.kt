package com.llmbt

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private lateinit var privateLogFile: File
    private var publicUri: android.net.Uri? = null

    fun init(application: Application) {
        try {
            val dir = File(application.filesDir, "crash_logs")
            dir.mkdirs()
            privateLogFile = File(dir, "startup.log")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, "LLM-BT-startup-$stamp.log")
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                        put(MediaStore.Downloads.RELATIVE_PATH, "Download/LLM-BT")
                    }
                    publicUri = application.contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        values
                    )
                } catch (_: Throwable) {
                    publicUri = null
                }
            }

            write("=== APP START ===")
            write("time=" + now())
            write("android=" + Build.VERSION.RELEASE + " sdk=" + Build.VERSION.SDK_INT)
            write("device=" + Build.MANUFACTURER + " " + Build.MODEL)
            write("abi=" + Build.SUPPORTED_ABIS.joinToString())
            write("filesDir=" + application.filesDir.absolutePath)
            write("publicLog=" + (publicUri != null))
        } catch (throwable: Throwable) {
            try {
                val fallback = File(application.cacheDir, "startup-fallback.log")
                fallback.appendText("LOGGER INIT FAILURE: " + throwable.stackTraceToString() + "\n")
            } catch (_: Throwable) {}
        }
    }

    @Synchronized
    fun write(message: String) {
        val line = "[" + now() + "] " + message + "\n"
        try {
            if (::privateLogFile.isInitialized) {
                privateLogFile.appendText(line)
            }
        } catch (_: Throwable) {}

        try {
            val uri = publicUri
            if (uri != null) {
                // MediaStore supports append mode for this provider.
                val app = LlmBtApplication.instance
                app.contentResolver.openOutputStream(uri, "wa")?.use {
                    it.write(line.toByteArray(Charsets.UTF_8))
                }
            }
        } catch (_: Throwable) {}
    }

    @Synchronized
    fun exception(tag: String, throwable: Throwable) {
        write(tag + ": " + throwable.javaClass.name + ": " + throwable.message)
        write(throwable.stackTraceToString())
    }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}

class LlmBtApplication : Application() {
    companion object {
        lateinit var instance: LlmBtApplication
            private set
    }

    override fun onCreate() {
        instance = this
        AppLogger.init(this)

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLogger.exception(
                "UNCAUGHT EXCEPTION thread=" + thread.name,
                throwable
            )
            previousHandler?.uncaughtException(thread, throwable)
        }

        AppLogger.write("Application.onCreate completed")
        super.onCreate()
    }
}
