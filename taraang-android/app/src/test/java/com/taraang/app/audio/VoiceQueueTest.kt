package com.taraang.app.audio

import android.speech.tts.TextToSpeech
import org.junit.Assert.assertEquals
import org.junit.Test

/** 새 경고는 이전 문장을 끊되, 말하는 중인 다른 경고는 끊지 않는다(코드 리뷰 M3). */
class VoiceQueueTest {
    @Test
    fun `warning flushes ordinary speech but queues behind another warning`() {
        assertEquals(TextToSpeech.QUEUE_FLUSH, VoiceFeedbackPlayer.queueModeFor(warning = true, warningSpeaking = false))
        assertEquals(TextToSpeech.QUEUE_ADD, VoiceFeedbackPlayer.queueModeFor(warning = true, warningSpeaking = true))
        assertEquals(TextToSpeech.QUEUE_ADD, VoiceFeedbackPlayer.queueModeFor(warning = false, warningSpeaking = false))
        assertEquals(TextToSpeech.QUEUE_ADD, VoiceFeedbackPlayer.queueModeFor(warning = false, warningSpeaking = true))
    }
}
