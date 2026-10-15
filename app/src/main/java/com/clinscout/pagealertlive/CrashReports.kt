package com.clinscout.pagealertlive

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Stored only in private app storage, never sent over the network. */
object CrashReports {
    private fun file(context: Context) = File(context.filesDir, "last-crash.txt")

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler() ?: return
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                @Suppress("DEPRECATION")
                val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
                file(context).writeText("PageAlert Live $version\n${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\nThread: ${thread.name}\n\n$trace".take(32768))
            } catch (_: Throwable) {
                // Diagnostic storage must never interfere with Android's crash handler.
            } finally {
                previous.uncaughtException(thread, error)
            }
        }
    }

    fun read(context: Context): String? = try {
        file(context).takeIf { it.isFile }?.readText()?.take(32768)
    } catch (_: Exception) { null }
}
