package com.codex.chat.core.root

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class RootCommandResult(
    val isRooted: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val success: Boolean
)

object RootShellExecutor {

    private val ROOT_PATHS = arrayOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/local/su",
        "/su/bin/su"
    )

    fun isSuBinaryPresent(): Boolean {
        for (path in ROOT_PATHS) {
            try {
                val f = File(path)
                if (f.exists() && f.canExecute()) return true
            } catch (_: Throwable) {
                // Ignore security check denials
            }
        }
        // Fallback: check PATH
        val pathEnv = System.getenv("PATH") ?: ""
        for (dir in pathEnv.split(File.pathSeparator)) {
            val candidate = File(dir, "su")
            if (candidate.exists() && candidate.canExecute()) return true
        }
        return false
    }

    fun checkRootAccess(): RootCommandResult {
        if (!isSuBinaryPresent()) {
            return RootCommandResult(
                isRooted = false,
                exitCode = -1,
                stdout = "",
                stderr = "No se encontró el binario 'su'. Dispositivo actualmente no rooteado.",
                success = false
            )
        }
        return executeSu("id")
    }

    fun executeSu(command: String, timeoutSec: Long = 10): RootCommandResult {
        return try {
            val process = ProcessBuilder("su")
                .redirectErrorStream(false)
                .start()

            val stdin = process.outputStream.bufferedWriter()
            stdin.write(command)
            stdin.newLine()
            stdin.write("exit")
            stdin.newLine()
            stdin.flush()

            val stdoutSb = StringBuilder()
            val stderrSb = StringBuilder()

            val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
            val stderrReader = BufferedReader(InputStreamReader(process.errorStream))

            val stdoutThread = Thread {
                try {
                    var line: String?
                    while (stdoutReader.readLine().also { line = it } != null) {
                        stdoutSb.appendLine(line)
                    }
                } catch (_: Throwable) {}
            }

            val stderrThread = Thread {
                try {
                    var line: String?
                    while (stderrReader.readLine().also { line = it } != null) {
                        stderrSb.appendLine(line)
                    }
                } catch (_: Throwable) {}
            }

            stdoutThread.start()
            stderrThread.start()

            val completed = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return RootCommandResult(
                    isRooted = true,
                    exitCode = -2,
                    stdout = stdoutSb.toString().trim(),
                    stderr = "Comando 'su' excedió el tiempo límite de $timeoutSec segundos.",
                    success = false
                )
            }

            stdoutThread.join(1000)
            stderrThread.join(1000)

            val exit = process.exitValue()
            val out = stdoutSb.toString().trim()
            val err = stderrSb.toString().trim()

            RootCommandResult(
                isRooted = exit == 0 || out.contains("uid=0"),
                exitCode = exit,
                stdout = out,
                stderr = err,
                success = exit == 0
            )
        } catch (e: Throwable) {
            RootCommandResult(
                isRooted = false,
                exitCode = -1,
                stdout = "",
                stderr = "Error ejecutando su: " + (e.message ?: e.javaClass.simpleName),
                success = false
            )
        }
    }
}
