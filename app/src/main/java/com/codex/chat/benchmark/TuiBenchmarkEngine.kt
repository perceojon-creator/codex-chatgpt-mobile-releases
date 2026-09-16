package com.codex.chat.benchmark

data class BenchmarkResult(
    val name: String,
    val passed: Boolean,
    val durationMs: Long = 0L,
    val metrics: Map<String, Double> = emptyMap(),
    val logLines: List<String> = emptyList(),
    val errorMessage: String? = null
) {
    fun toAnsiOutput(): String {
        val statusTag = if (passed) "[PASS]" else "[FAIL]"
        val sb = StringBuilder()
        sb.append(statusTag).append(" ").append(name).append(" (").append(durationMs).append(" ms)\n")
        metrics.forEach { (k, v) ->
            sb.append("   * ").append(k).append(": ").append(String.format("%.2f", v)).append("\n")
        }
        logLines.forEach { line ->
            sb.append("     > ").append(line).append("\n")
        }
        if (errorMessage != null) {
            sb.append("   ! Error: ").append(errorMessage).append("\n")
        }
        return sb.toString()
    }
}

class TuiBenchmarkEngine {
    private val benchmarks = mutableMapOf<String, () -> BenchmarkResult>()

    fun registerBenchmark(name: String, block: () -> BenchmarkResult) {
        benchmarks[name] = block
    }

    fun listBenchmarks(): List<String> = benchmarks.keys.toList()

    fun run(name: String): BenchmarkResult {
        val benchmark = benchmarks[name] ?: return BenchmarkResult(
            name = name,
            passed = false,
            durationMs = 0L,
            errorMessage = "Benchmark '$name' no encontrado en el registro"
        )
        val start = System.currentTimeMillis()
        return try {
            val res = benchmark()
            res.copy(durationMs = System.currentTimeMillis() - start)
        } catch (e: Throwable) {
            BenchmarkResult(
                name = name,
                passed = false,
                durationMs = System.currentTimeMillis() - start,
                errorMessage = e.message ?: e.javaClass.simpleName
            )
        }
    }

    fun runAll(): List<BenchmarkResult> {
        return benchmarks.keys.map { run(it) }
    }
}
