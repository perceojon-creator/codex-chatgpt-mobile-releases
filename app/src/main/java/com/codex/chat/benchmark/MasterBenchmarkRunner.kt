package com.codex.chat.benchmark

import java.io.File

data class SuiteReport(
    val timestamp: Long,
    val totalCount: Int,
    val passedCount: Int,
    val failedCount: Int,
    val totalDurationMs: Long,
    val results: List<BenchmarkResult>
) {
    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"timestamp\": ").append(timestamp).append(",\n")
        sb.append("  \"totalCount\": ").append(totalCount).append(",\n")
        sb.append("  \"passedCount\": ").append(passedCount).append(",\n")
        sb.append("  \"failedCount\": ").append(failedCount).append(",\n")
        sb.append("  \"totalDurationMs\": ").append(totalDurationMs).append(",\n")
        sb.append("  \"results\": [\n")
        for (i in results.indices) {
            val r = results[i]
            sb.append("    {\"name\": \"").append(r.name).append("\", ")
            sb.append("\"passed\": ").append(r.passed).append(", ")
            sb.append("\"durationMs\": ").append(r.durationMs).append("}")
            if (i < results.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ]\n")
        sb.append("}")
        return sb.toString()
    }

    fun toAnsiSummary(): String {
        val sb = StringBuilder()
        sb.append("\n==================================================\n")
        sb.append("        RESUMEN MAESTRO DE SUITE DE BENCHMARKS     \n")
        sb.append("==================================================\n")
        sb.append("Total ejecutados: ").append(totalCount).append("\n")
        sb.append("Aprobados       : \u001B[32m").append(passedCount).append("\u001B[0m\n")
        sb.append("Fallidos        : ").append(if (failedCount > 0) "\u001B[31m$failedCount\u001B[0m" else "0").append("\n")
        sb.append("Duración Total  : ").append(totalDurationMs).append(" ms\n")
        sb.append("==================================================\n")
        return sb.toString()
    }
}

class MasterBenchmarkRunner(
    private val engine: TuiBenchmarkEngine,
    private val reportDestinationFile: File? = null
) {
    fun runAll(onProgress: ((BenchmarkResult) -> Unit)? = null): SuiteReport {
        val start = System.currentTimeMillis()
        val results = mutableListOf<BenchmarkResult>()

        val rawResults = engine.runAll()
        for (r in rawResults) {
            results.add(r)
            onProgress?.invoke(r)
        }

        val duration = System.currentTimeMillis() - start
        val passed = results.count { it.passed }
        val failed = results.count { !it.passed }

        val report = SuiteReport(
            timestamp = System.currentTimeMillis(),
            totalCount = results.size,
            passedCount = passed,
            failedCount = failed,
            totalDurationMs = duration,
            results = results
        )

        try {
            reportDestinationFile?.let { file ->
                file.parentFile?.mkdirs()
                file.writeText(report.toJson())
            }
        } catch (ignored: Exception) {}

        return report
    }
}
