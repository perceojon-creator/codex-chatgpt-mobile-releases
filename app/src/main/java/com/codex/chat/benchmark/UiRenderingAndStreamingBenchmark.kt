package com.codex.chat.benchmark

import com.codex.chat.StreamingTextPayload
import com.codex.chat.core.concurrency.StreamBuffer
import com.codex.chat.core.media.VisualMediaParser
import com.codex.chat.core.metrics.ContextMetricsCalculator
import com.codex.chat.core.model.ModelInfo
import com.codex.chat.core.parser.SseStreamParser
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class UiRenderingAndStreamingBenchmark {

    fun execute(): BenchmarkResult {
        val logs = mutableListOf<String>()
        val start = System.currentTimeMillis()

        // 1. Simulación de streaming de alta frecuencia (100 tokens/s) en StreamBuffer
        val streamBuffer = StreamBuffer()
        val streamLatch = CountDownLatch(1)
        val readCount = AtomicInteger(0)
        var tearingErrors = 0

        val producer = Thread {
            for (i in 1..200) {
                streamBuffer.appendContent(" token_$i")
                if (i % 5 == 0) {
                    streamBuffer.appendReasoning(" paso_${i / 5};")
                }
                try { Thread.sleep(2) } catch (ignored: Exception) {}
            }
            streamLatch.countDown()
        }

        val consumer = Thread {
            while (streamLatch.count > 0) {
                val snap = streamBuffer.getSnapshot()
                readCount.incrementAndGet()
                if (snap.version > 0 && snap.content.isEmpty() && snap.reasoning.isNotEmpty()) {
                    tearingErrors++
                }
                try { Thread.sleep(5) } catch (ignored: Exception) {}
            }
        }

        producer.start()
        consumer.start()
        producer.join(4000)
        consumer.join(1000)

        logs.add("StreamBuffer: 200 deltas producidos, ${readCount.get()} snapshots leídos, tearingErrors=$tearingErrors")

        // 2. Benchmark de DFA SSE extractDeltaFast sobre 500 chunks
        val sseChunk = "data: {\"choices\":[{\"delta\":{\"content\":\"Texto rápido para benchmark de render\"}}]}\n"
        val fastExtractStart = System.nanoTime()
        var extractedTokens = 0
        for (i in 1..500) {
            val res = SseStreamParser.extractDeltaFast(sseChunk)
            if (res != null) extractedTokens++
        }
        val fastExtractDurationMs = (System.nanoTime() - fastExtractStart) / 1_000_000.0
        logs.add("SseStreamParser: $extractedTokens / 500 extraídos en ${String.format("%.2f", fastExtractDurationMs)} ms")

        // 3. Benchmark de ContextMetricsCalculator con caché
        val model = ModelInfo(id = "gpt-4o", displayName = "GPT-4o", provider = "openai")
        val metricsStart = System.nanoTime()
        for (i in 1..100) {
            ContextMetricsCalculator.fastWordCount("Palabra de prueba repetida para benchmark rápido de métricas")
        }
        val metricsDurationMs = (System.nanoTime() - metricsStart) / 1_000_000.0
        logs.add("MetricsCalculator: 100 cálculos en ${String.format("%.2f", metricsDurationMs)} ms")

        // 4. Payload model fast streaming check
        val payload = StreamingTextPayload("Respuesta finalizada", "Razonamiento completo")
        val payloadOk = payload.text.isNotEmpty() && payload.reasoningText.isNotEmpty()

        val totalDuration = System.currentTimeMillis() - start
        val passed = (tearingErrors == 0) && (extractedTokens == 500) && (fastExtractDurationMs < 50.0) && payloadOk

        return BenchmarkResult(
            name = "UI_RENDERING_AND_STREAMING",
            passed = passed,
            durationMs = totalDuration,
            metrics = mapOf(
                "fast_extract_ms" to fastExtractDurationMs,
                "read_snapshots_count" to readCount.get().toDouble(),
                "tearing_errors" to tearingErrors.toDouble(),
                "metrics_duration_ms" to metricsDurationMs
            ),
            logLines = logs,
            errorMessage = if (!passed) "Fallo en benchmark UI rendering (tearing=$tearingErrors, tokens=$extractedTokens, extractMs=$fastExtractDurationMs)" else null
        )
    }
}
