package com.unique.visionmate

internal object VoiceCommandSelector {
    val wakeWordVariants = setOf("mitra", "mi tra", "mithra")

    private val commandKeywords = listOf(
        "open", "launch", "call", "send", "reply", "navigate", "go to", "go home", "play",
        "read", "take", "close", "back", "home", "start", "stop", "sleep", "battery",
        "time", "date", "repeat", "hey", "hello", "hi", "mitra", "exit", "message", "whatsapp"
    )

    fun pickBestCandidate(candidates: List<String>): String {
        if (candidates.isEmpty()) return ""
        for (candidate in candidates) {
            val normalized = normalizeSpeechText(candidate.lowercase())
            if (containsWakeWord(normalized)) return candidate.lowercase().trim()
        }
        for (candidate in candidates) {
            val normalized = normalizeSpeechText(candidate.lowercase())
            if (commandKeywords.any { normalized.contains(it) }) return candidate.lowercase().trim()
        }
        return candidates[0].lowercase().trim()
    }

    fun normalizeSpeechText(text: String): String =
        text.lowercase().replace(Regex("\\s+"), " ").trim()

    fun containsWakeWord(text: String): Boolean {
        return wakeWordVariants.any { variant -> wakeWordRegex(variant).containsMatchIn(text) }
    }

    fun removeWakeWord(text: String): String {
        var result = text
        for (variant in wakeWordVariants) result = wakeWordRegex(variant).replace(result, " ").trim()
        return result.replace(Regex("\\s+"), " ").trim()
    }

    private fun wakeWordRegex(variant: String): Regex =
        Regex("(^|[^a-z])${Regex.escape(variant)}(?=[^a-z]|$)")
}
