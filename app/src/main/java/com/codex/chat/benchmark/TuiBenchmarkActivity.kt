package com.codex.chat.benchmark

import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.codex.chat.R
import java.io.File
import kotlin.concurrent.thread

class TuiBenchmarkActivity : AppCompatActivity() {

    private val engine = TuiBenchmarkEngine()
    private lateinit var consoleTextView: TextView
    private lateinit var scrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tui_benchmark)

        consoleTextView = findViewById(R.id.tvBenchmarkConsole)
        scrollView = findViewById(R.id.scrollBenchmarkConsole)

        findViewById<Button>(R.id.btnClearLogs).setOnClickListener {
            consoleTextView.text = ""
        }

        findViewById<Button>(R.id.btnRunAll).setOnClickListener {
            runAllBenchmarksAsync()
        }

        setupBenchmarks()

        // Soporte de ejecución automatizada headless vía adb:
        // adb shell am start -n com.codex.chat/.benchmark.TuiBenchmarkActivity --es suite "all"
        val suiteArg = intent.getStringExtra("suite")
        if (!suiteArg.isNullOrBlank()) {
            runAllBenchmarksAsync(headless = true)
        }
    }

    private fun setupBenchmarks() {
        val integrityDir = File(filesDir, "benchmark_data_integrity").apply { if (!exists()) mkdirs() }
        engine.registerBenchmark("DATA_INTEGRITY_STRESS") {
            DataIntegrityBenchmark(integrityDir).execute(500)
        }

        val cutoverDir = File(filesDir, "benchmark_storage_cutover").apply { if (!exists()) mkdirs() }
        engine.registerBenchmark("STORAGE_CUTOVER_MIGRATION") {
            MigrationCutoverBenchmark(cutoverDir).execute(100)
        }

        engine.registerBenchmark("CONCURRENCY_AND_MCP_STRESS") {
            ConcurrencyAndMcpBenchmark(applicationContext).execute()
        }

        engine.registerBenchmark("UI_RENDERING_AND_STREAMING") {
            UiRenderingAndStreamingBenchmark().execute()
        }
    }

    private fun runAllBenchmarksAsync(headless: Boolean = false) {
        logToConsole("\n=== INICIANDO EJECUCIÓN DE SUITE DE BENCHMARKS ===\n")
        thread(name = "tui-benchmark-runner") {
            val results = engine.runAll()
            val reportSb = StringBuilder()
            reportSb.append("{\n  \"timestamp\": ").append(System.currentTimeMillis()).append(",\n  \"results\": [\n")

            for (idx in results.indices) {
                val r = results[idx]
                logToConsole(r.toAnsiOutput())
                reportSb.append("    {\"name\": \"").append(r.name).append("\", \"passed\": ").append(r.passed)
                    .append(", \"durationMs\": ").append(r.durationMs).append("}")
                if (idx < results.size - 1) reportSb.append(",")
                reportSb.append("\n")
            }
            reportSb.append("  ]\n}")

            try {
                val reportFile = File(filesDir, "benchmark_report.json")
                reportFile.writeText(reportSb.toString())
                logToConsole("Reporte guardado en: ${reportFile.absolutePath}\n")
            } catch (e: Exception) {
                logToConsole("Error guardando reporte: ${e.message}\n")
            }

            logToConsole("=== SUITE FINALIZADA: ${results.count { it.passed }}/${results.size} PASADOS ===\n")

            if (headless) {
                Thread.sleep(1000)
                finish()
            }
        }
    }

    private fun logToConsole(text: String) {
        runOnUiThread {
            consoleTextView.append(text)
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}
