package com.taraang.app.audio

import com.taraang.app.domain.model.FeedbackMessage

/** 피드백 출력. 실패해도 예외를 던지지 않는다(주행 루프를 멈추지 않게). */
interface FeedbackSpeaker {
    /** [voiceEnabled]가 false면 알림음만 낸다. */
    fun speak(message: FeedbackMessage, voiceEnabled: Boolean)
    fun release()
}
