package com.codex.chat.core.parser

data class AstraFollowUp(
    val label: String,
    val prompt: String
)

data class AstraCodeComment(
    val title: String,
    val body: String,
    val file: String,
    val startLine: Int = 1,
    val endLine: Int = 1,
    val priority: Int = 2
)

data class ParsedAstraDirectives(
    val cleanContent: String,
    val followUps: List<AstraFollowUp>,
    val codeComments: List<AstraCodeComment>
)

/**
 * Parser de directivas interactivas avanzadas del agente OpenAI Codex GPT-6 Astra:
 *
 * 1. Chips de seguimiento dinámico:
 *    `- :codex-followup[Frase visible]{prompt="Instrucción ejecutable"}`
 *
 * 2. Comentarios y anotaciones estructuradas de código:
 *    `::code-comment{title="Título" body="Explicación" file="ruta" start=10 end=12 priority=1}`
 */
object AstraDirectivesParser {

    // Regex para capturar follow-up chips: `- :codex-followup[label]{prompt="prompt"}`
    private val FOLLOW_UP_REGEX = Regex(
        """(?:^|\n)\s*[-*]?\s*:codex-followup\[([^\]]+)\]\{prompt=["']([^"']+)["']\}""",
        RegexOption.MULTILINE
    )

    // Regex para capturar ::code-comment{...}
    private val CODE_COMMENT_REGEX = Regex(
        """::code-comment\{([^\}]+)\}"""
    )

    private val ATTR_REGEX = Regex("""([a-zA-Z0-9_]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s,}]+))""")

    fun parse(content: String): ParsedAstraDirectives {
        if (!content.contains(":codex-followup") && !content.contains("::code-comment")) {
            return ParsedAstraDirectives(
                cleanContent = content,
                followUps = emptyList(),
                codeComments = emptyList()
            )
        }

        val followUps = mutableListOf<AstraFollowUp>()
        for (match in FOLLOW_UP_REGEX.findAll(content)) {
            val label = match.groupValues[1].trim()
            val prompt = match.groupValues[2].trim()
            if (label.isNotBlank() && prompt.isNotBlank()) {
                followUps.add(AstraFollowUp(label = label, prompt = prompt))
            }
        }

        val codeComments = mutableListOf<AstraCodeComment>()
        for (match in CODE_COMMENT_REGEX.findAll(content)) {
            val attrsString = match.groupValues[1]
            val attrs = parseAttributes(attrsString)
            val title = attrs["title"].orEmpty()
            val body = attrs["body"].orEmpty()
            val file = attrs["file"].orEmpty()
            val start = attrs["start"]?.toIntOrNull() ?: 1
            val end = attrs["end"]?.toIntOrNull() ?: start
            val priority = attrs["priority"]?.toIntOrNull() ?: 2

            if (title.isNotBlank() || body.isNotBlank()) {
                codeComments.add(
                    AstraCodeComment(
                        title = title.ifBlank { "Comentario de código" },
                        body = body,
                        file = file,
                        startLine = start,
                        endLine = end,
                        priority = priority
                    )
                )
            }
        }

        // Limpiar el contenido eliminando las directivas crudas para que no contaminen la lectura
        var cleaned = FOLLOW_UP_REGEX.replace(content, "")
        cleaned = CODE_COMMENT_REGEX.replace(cleaned, "")
        cleaned = cleaned.trimEnd()

        return ParsedAstraDirectives(
            cleanContent = cleaned,
            followUps = followUps,
            codeComments = codeComments
        )
    }

    private fun parseAttributes(input: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (match in ATTR_REGEX.findAll(input)) {
            val key = match.groupValues[1].trim()
            val valDq = match.groupValues[2]
            val valSq = match.groupValues[3]
            val valBare = match.groupValues[4]
            val value = when {
                valDq.isNotEmpty() -> valDq
                valSq.isNotEmpty() -> valSq
                else -> valBare
            }
            result[key] = value
        }
        return result
    }
}
