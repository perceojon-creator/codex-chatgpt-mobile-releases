package com.codex.chat.core.harvest

import android.content.Context
import android.util.Log
import com.codex.chat.LocalChatSession
import com.codex.chat.core.mcp.server.MemorySqliteStore
import com.codex.chat.core.metrics.TokenEstimator
import com.codex.chat.core.model.ChatMessage
import com.codex.chat.core.model.MessageRole
import java.util.Locale
import java.util.regex.Pattern

data class HarvestFact(
    val key: String,
    val value: String,
    val category: String,
    val sourceText: String
)

data class HarvestResult(
    val sessionId: String,
    val harvestedFacts: List<HarvestFact>,
    val tokensBefore: Int,
    val tokensAfter: Int,
    val prunedMessageCount: Int,
    val compactionSummary: String
)

/**
 * Recolector Anti-Amnesia Pre-Compactación.
 * Analiza el historial antes de podar o compactar mensajes para extraer hechos permanentes,
 * preferencias de usuario, directivas de arquitectura y decisiones técnicas,
 * persistiendo todo en SQLite FTS4 y generando un ancla contextual compacta.
 */
class AntiAmnesiaHarvester(
    private val context: Context,
    private val sqliteStore: MemorySqliteStore? = null
) {
    companion object {
        private const val TAG = "AntiAmnesiaHarvester"
        const val DEFAULT_THRESHOLD_TOKENS = 3500
        const val DEFAULT_TARGET_TOKENS = 1800

        private val EXTRACTION_RULES = listOf(
            Triple(
                Pattern.compile("(?i)(?:me llamo|mi nombre es|soy|i am|my name is)\\s+([a-zA-ZáéíóúÁÉÍÓÚñÑ0-9_\\-\\s]{2,40})"),
                "user_identity",
                "preferencias"
            ),
            Triple(
                Pattern.compile("(?i)(?:prefiero|mi preferencia es|preferencia:)\\s+([^.;\\n]{4,100})"),
                "user_preference",
                "preferencias"
            ),
            Triple(
                Pattern.compile("(?i)(?:recuerda que|no olvides que|directiva:|regla:)\\s+([^.;\\n]{5,150})"),
                "directive",
                "directivas"
            ),
            Triple(
                Pattern.compile("(?i)(?:arquitectura|stack|tecnología|framework):?\\s*([^.;\\n]{4,120})"),
                "tech_stack",
                "arquitectura"
            ),
            Triple(
                Pattern.compile("(?i)(?:decisión|acuerdo):?\\s*([^.;\\n]{5,150})"),
                "project_decision",
                "decisiones"
            )
        )
    }

    private val store: MemorySqliteStore by lazy {
        sqliteStore ?: MemorySqliteStore(context)
    }

    fun calculateSessionTokens(messages: List<ChatMessage>): Int {
        return messages.sumOf { TokenEstimator.estimateTokens(it.content) + TokenEstimator.estimateTokens(it.reasoningContent ?: "") }
    }

    fun shouldCompact(messages: List<ChatMessage>, thresholdTokens: Int = DEFAULT_THRESHOLD_TOKENS): Boolean {
        if (messages.size <= 4) return false
        val totalTokens = calculateSessionTokens(messages)
        return totalTokens >= thresholdTokens
    }

    fun harvestFacts(messages: List<ChatMessage>): List<HarvestFact> {
        val facts = mutableListOf<HarvestFact>()
        val seenKeys = mutableSetOf<String>()

        for (msg in messages) {
            val text = msg.content
            if (text.isBlank()) continue

            for ((pattern, keyPrefix, category) in EXTRACTION_RULES) {
                val matcher = pattern.matcher(text)
                var index = 1
                while (matcher.find()) {
                    val extracted = matcher.group(1)?.trim() ?: continue
                    if (extracted.length >= 2) {
                        val key = "${keyPrefix}_${facts.size + 1}"
                        if (seenKeys.add(extracted.lowercase(Locale.ROOT))) {
                            facts.add(
                                HarvestFact(
                                    key = key,
                                    value = extracted,
                                    category = category,
                                    sourceText = text.take(120)
                                )
                            )
                        }
                        index++
                    }
                }
            }
        }
        return facts
    }

    fun harvestAndCompact(
        session: LocalChatSession,
        thresholdTokens: Int = DEFAULT_THRESHOLD_TOKENS,
        targetTokens: Int = DEFAULT_TARGET_TOKENS
    ): HarvestResult {
        val originalMessages = synchronized(session.messages) { session.messages.toList() }
        val tokensBefore = calculateSessionTokens(originalMessages)

        if (originalMessages.size <= 4 || tokensBefore < thresholdTokens) {
            return HarvestResult(
                sessionId = session.id,
                harvestedFacts = emptyList(),
                tokensBefore = tokensBefore,
                tokensAfter = tokensBefore,
                prunedMessageCount = 0,
                compactionSummary = "No se requirió compactación (tokens: $tokensBefore < $thresholdTokens)"
            )
        }

        // Determinar qué mensajes podar: conservamos siempre los últimos N mensajes recientes
        val keepRecentCount = 3.coerceAtMost(originalMessages.size - 1)
        val messagesToCompact = originalMessages.dropLast(keepRecentCount)
        val recentMessages = originalMessages.takeLast(keepRecentCount)

        // 1. Cosechar hechos de los mensajes que serán podados
        val harvested = harvestFacts(messagesToCompact)

        // 2. Persistir hechos permanentemente en SQLite
        for (f in harvested) {
            try {
                store.save(f.key, f.value, f.category)
                Log.d(TAG, "Fact harvested and saved to SQLite: ${f.key} = ${f.value}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed saving harvested fact to SQLite", e)
            }
        }

        // 3. Crear ancla de memoria consolidada
        val summaryBuilder = StringBuilder()
        summaryBuilder.append("[📌 Ancla de Contexto Pre-Compactación & Memoria Persistente]\n")
        if (harvested.isNotEmpty()) {
            summaryBuilder.append("Datos clave preservados en memoria local:\n")
            for (f in harvested) {
                summaryBuilder.append("• [${f.category}] ${f.value}\n")
            }
        } else {
            summaryBuilder.append("Conversación resumida: se podaron ${messagesToCompact.size} turnos antiguos.\n")
        }

        val anchorMessage = ChatMessage(
            role = MessageRole.ASSISTANT,
            content = summaryBuilder.toString().trim()
        )

        val newMessages = mutableListOf<ChatMessage>()
        newMessages.add(anchorMessage)
        newMessages.addAll(recentMessages)

        val tokensAfter = calculateSessionTokens(newMessages)

        synchronized(session.messages) {
            session.messages.clear()
            session.messages.addAll(newMessages)
        }

        return HarvestResult(
            sessionId = session.id,
            harvestedFacts = harvested,
            tokensBefore = tokensBefore,
            tokensAfter = tokensAfter,
            prunedMessageCount = messagesToCompact.size,
            compactionSummary = "Compactados ${messagesToCompact.size} mensajes. Tokens: $tokensBefore -> $tokensAfter. Hechos cosechados: ${harvested.size}."
        )
    }
}
