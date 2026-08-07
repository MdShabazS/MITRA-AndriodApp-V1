package com.unique.visionmate

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

object MitraLogRecorder {
    private const val TAG = "MITRA_LOGS"
    private const val LOG_DIR_NAME = "LOGS"

    @Volatile private var started = false
    @Volatile private var logcatProcess: java.lang.Process? = null

    fun start(context: Context) {
        if (started) return
        started = true

        val appContext = context.applicationContext
        thread(name = "mitra-log-recorder", isDaemon = true) {
            val logFile = createLogFile(appContext)
            try {
                writeHeader(logFile)
                val pid = Process.myPid().toString()
                val process = ProcessBuilder("logcat", "--pid=$pid", "-v", "threadtime")
                    .redirectErrorStream(true)
                    .start()
                logcatProcess = process
                Log.i(TAG, "saving run logs to ${logFile.absolutePath}")
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    FileOutputStream(logFile, true).bufferedWriter().use { writer ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            writer.appendLine(line)
                            writer.flush()
                        }
                    }
                }
            } catch (e: Exception) {
                try {
                    logFile.appendText("LOG RECORDER ERROR: ${e.message}\n")
                } catch (_: Exception) {
                }
                Log.w(TAG, "log recorder failed: ${e.message}")
            }
        }
    }

    private fun createLogFile(context: Context): File {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(root, LOG_DIR_NAME).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        return File(dir, "mitra_run_$stamp.txt")
    }

    private fun writeHeader(file: File) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        file.writeText(
            buildString {
                appendLine("MITRA app run log")
                appendLine("Started: $stamp")
                appendLine("Process ID: ${Process.myPid()}")
                appendLine("Package: com.unique.visionmate")
                appendLine("------------------------------------------------------------")
            }
        )
    }
}
