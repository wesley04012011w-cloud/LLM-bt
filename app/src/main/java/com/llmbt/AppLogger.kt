package com.llmbt

import android.app.Application
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private lateinit var logFile: File

    fun init(application: Application) {
        val dir = File(application.filesDir, "crash_logs")
        dir.mkdirs()
        logFile = File(dir, "startup.log")
        write("=== APP START ===")
        write("time=" + now())
        write("android=" + Build.VERSION.RELEASE + " sdk=" + Build.VERSION.SDK_INT)
        write("device=" + Build.MANUFACTURER + " " + Build.MODEL)
        write("abi=" + Build.SUPPORTED_ABIS.joinToString())
        write("filesDir=" + application.filesDir.absolutePath)
    }

    @Synchronized fun write(message: String) {
        try { if (!::logFile.isInitialized) return; logFile.appendText("[" + now() + "] " + message + "\n") } catch (_: Throwable) {}
    }

    @Synchronized fun exception(tag: String, throwable: Throwable) {
        write(tag + ": " + throwable.javaClass.name + ": " + throwable.message)
        write(throwable.stackTraceToString())
    }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}

class LlmBtApplication : Application() {
    override fun onCreate() {
        AppLogger.init(this)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLogger.exception("UNCAUGHT EXCEPTION thread=" + thread.name, throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }
        AppLogger.write("Application.onCreate completed")
        super.onCreate()
    }
}