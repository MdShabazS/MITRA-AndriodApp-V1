package com.unique.visionmate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandSelectorTest {

    @Test
    fun pickBestCandidate_prefersWakeWordCandidateOverEarlierCommandKeywordCandidate() {
        val selected = VoiceCommandSelector.pickBestCandidate(
            listOf("Mathura time", "Mitra time", "mitrata time")
        )

        assertEquals("mitra time", selected)
    }

    @Test
    fun pickBestCandidate_keepsWakeWordWithOpenCommand() {
        val selected = VoiceCommandSelector.pickBestCandidate(
            listOf("open youtube", "mitra open youtube")
        )

        assertEquals("mitra open youtube", selected)
    }

    @Test
    fun containsWakeWord_acceptsCanonicalAndSpacedVariants() {
        assertTrue(VoiceCommandSelector.containsWakeWord("mitra battery"))
        assertTrue(VoiceCommandSelector.containsWakeWord("mi tra date"))
        assertTrue(VoiceCommandSelector.containsWakeWord("mithra open youtube"))
    }

    @Test
    fun containsWakeWord_rejectsNearMissesSeenInSpeechRecognition() {
        assertFalse(VoiceCommandSelector.containsWakeWord("mathura time"))
        assertFalse(VoiceCommandSelector.containsWakeWord("myntra battery"))
        assertFalse(VoiceCommandSelector.containsWakeWord("mitrata date"))
    }

    @Test
    fun removeWakeWord_returnsCommandAfterWakeWord() {
        assertEquals("open youtube", VoiceCommandSelector.removeWakeWord("mitra open youtube"))
        assertEquals("battery", VoiceCommandSelector.removeWakeWord("mi tra battery"))
        assertEquals("", VoiceCommandSelector.removeWakeWord("mithra"))
    }
}
