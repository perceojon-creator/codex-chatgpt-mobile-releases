package com.codex.chat.compaction

import com.codex.chat.core.metrics.ContextMetricsCalculator
import com.codex.chat.core.model.ModelInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextWindowModelResolutionTest {

    @Test
    fun testGeminiAndSolModelsResolveTo1M() {
        val solModel = ModelInfo(id = "gpt-5.6-sol", displayName = "GPT-5.6 Sol", provider = "Antigravity")
        assertEquals(1_048_576, ContextMetricsCalculator.resolveContextWindow(solModel))

        val geminiHigh = ModelInfo(id = "gemini-3.8-flash-high", displayName = "Gemini 3.8", provider = "Antigravity")
        assertEquals(1_048_576, ContextMetricsCalculator.resolveContextWindow(geminiHigh))

        val geminiLite = ModelInfo(id = "gemini-3.5-flash-lite", displayName = "Gemini 3.5", provider = "Antigravity")
        assertEquals(1_048_576, ContextMetricsCalculator.resolveContextWindow(geminiLite))
    }

    @Test
    fun testClaudeAndAstraModelsResolveTo200K() {
        val astraModel = ModelInfo(id = "astra", displayName = "Astra", provider = "Antigravity")
        assertEquals(200_000, ContextMetricsCalculator.resolveContextWindow(astraModel))

        val gpt6Astra = ModelInfo(id = "gpt-6-astra", displayName = "GPT-6 Astra", provider = "Antigravity")
        assertEquals(200_000, ContextMetricsCalculator.resolveContextWindow(gpt6Astra))

        val sonnetModel = ModelInfo(id = "claude-sonnet-4-6", displayName = "Sonnet 4.6", provider = "Anthropic")
        assertEquals(200_000, ContextMetricsCalculator.resolveContextWindow(sonnetModel))

        val sonnet37 = ModelInfo(id = "claude-3-7-sonnet-20250219", displayName = "Sonnet 3.7", provider = "Antigravity")
        assertEquals(200_000, ContextMetricsCalculator.resolveContextWindow(sonnet37))
    }

    @Test
    fun testDeepSeekAndTerraModelsResolveTo128K() {
        val terraModel = ModelInfo(id = "gpt-5.6-terra", displayName = "Terra", provider = "DeepSeek")
        assertEquals(128_000, ContextMetricsCalculator.resolveContextWindow(terraModel))

        val deepseekFlash = ModelInfo(id = "deepseek-v4-flash", displayName = "DeepSeek Flash", provider = "b-ai-free")
        assertEquals(128_000, ContextMetricsCalculator.resolveContextWindow(deepseekFlash))
    }

    @Test
    fun testGlmAndLunaModelsResolveTo128K() {
        val lunaModel = ModelInfo(id = "gpt-5.6-luna", displayName = "Luna", provider = "B-AI Free")
        assertEquals(128_000, ContextMetricsCalculator.resolveContextWindow(lunaModel))

        val glmFlash = ModelInfo(id = "z-ai/glm-5.3-flash", displayName = "GLM Flash", provider = "Freebuff")
        assertEquals(128_000, ContextMetricsCalculator.resolveContextWindow(glmFlash))
    }

    @Test
    fun testMiniMaxModelResolvesTo1M() {
        val minimax = ModelInfo(id = "minimax/minimax-m3", displayName = "MiniMax M3", provider = "Freebuff")
        assertEquals(1_000_000, ContextMetricsCalculator.resolveContextWindow(minimax))
    }

    @Test
    fun testExplicitContextWindowPrecedence() {
        val customModel = ModelInfo(
            id = "custom-model",
            displayName = "Custom 500k",
            provider = "Custom",
            contextWindow = 500_000
        )
        assertEquals(500_000, ContextMetricsCalculator.resolveContextWindow(customModel))
    }
}