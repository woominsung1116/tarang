package com.taraang.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.taraang.app.core.Logger
import com.taraang.app.domain.model.FeedbackMessage
import com.taraang.app.domain.model.FeedbackType
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * 안드로이드 TTS(한국어) + 타입별 알림음. 차량 내비 음성과 겹치지 않게
 * 내비 안내 용도(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)로 잠깐 포커스를 받아 다른 소리를 낮춘다.
 * TTS를 못 쓰면(엔진 없음·한국어 미지원) 알림음만 내고 한 번만 기록한다.
 * 모든 호출은 메인 스레드로 넘겨 처리한다.
 */
class VoiceFeedbackPlayer(context: Context, private val logger: Logger) : FeedbackSpeaker {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { }
        .build()

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsUnavailableLogged = false
    private var toneGenerator: ToneGenerator? = null
    private val utteranceSeq = AtomicInteger()
    private var releaseFocus: Runnable? = null
    /** 말하는 중인 새 경고(우선순위 1) 문장 id. 다른 문장이 이것을 끊지 못하게 한다. 메인 스레드에서만 쓴다. */
    private var speakingWarningId: String? = null

    init {
        main.post { initTts() }
    }

    override fun speak(message: FeedbackMessage, voiceEnabled: Boolean) {
        main.post {
            try {
                play(message, voiceEnabled)
            } catch (e: Exception) {
                logger.e(TAG, "피드백 재생 실패", e)
                abandonFocus()
            }
        }
    }

    override fun release() {
        main.post {
            runCatching { tts?.stop(); tts?.shutdown() }
            tts = null
            ttsReady = false
            runCatching { toneGenerator?.release() }
            toneGenerator = null
            abandonFocus()
        }
    }

    private fun initTts() {
        tts = TextToSpeech(appContext) { status ->
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null) {
                logTtsUnavailable("init status=$status")
                return@TextToSpeech
            }
            val lang = engine.setLanguage(Locale.KOREAN)
            if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                logTtsUnavailable("korean=$lang")
                return@TextToSpeech
            }
            engine.setAudioAttributes(attributes)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    if (utteranceId?.endsWith(GAP_SUFFIX) == true) return
                    main.post {
                        if (utteranceId == speakingWarningId) speakingWarningId = null
                        abandonFocus()
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    main.post {
                        if (utteranceId == speakingWarningId) speakingWarningId = null
                        abandonFocus()
                    }
                }
            })
            ttsReady = true
        }
    }

    private fun play(message: FeedbackMessage, voiceEnabled: Boolean) {
        val focused = audioManager?.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!focused) logger.w(TAG, "오디오 포커스 거부됨, 그대로 재생")
        playEarcon(message.type)

        val engine = tts
        if (voiceEnabled && ttsReady && engine != null) {
            val warning = message.type.priority == 1
            val queue = queueModeFor(warning, warningSpeaking = speakingWarningId != null)
            val id = "fb-${utteranceSeq.incrementAndGet()}"
            if (warning) speakingWarningId = id
            // 알림음이 끝난 뒤 말하도록 짧은 무음을 둔다.
            engine.playSilentUtterance(EARCON_MS.toLong(), queue, "$id$GAP_SUFFIX")
            val result = engine.speak(message.text, TextToSpeech.QUEUE_ADD, null, id)
            if (result == TextToSpeech.SUCCESS) {
                scheduleFocusRelease(FOCUS_SAFETY_MS)
                return
            }
            logger.w(TAG, "TTS speak 실패: $result")
        } else if (voiceEnabled) {
            logTtsUnavailable("not ready")
        }
        scheduleFocusRelease(EARCON_MS + 200L)
    }

    private fun playEarcon(type: FeedbackType) {
        val generator = toneGenerator ?: runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, EARCON_VOLUME) }
            .onFailure { logger.w(TAG, "ToneGenerator 생성 실패", it) }
            .getOrNull()
            ?.also { toneGenerator = it }
            ?: return
        generator.startTone(toneFor(type), EARCON_MS)
    }

    /** 타입마다 다른 소리로, 익숙해지면 소리만 듣고도 무슨 피드백인지 알게 한다. */
    private fun toneFor(type: FeedbackType): Int = when (type) {
        FeedbackType.NEW_WARNING -> ToneGenerator.TONE_PROP_BEEP2
        FeedbackType.SCHOOL_ZONE_AHEAD, FeedbackType.SCHOOL_ZONE_SPEED -> ToneGenerator.TONE_CDMA_ALERT_INCALL_LITE
        FeedbackType.SPEED_LIMIT_SUSTAINED -> ToneGenerator.TONE_PROP_ACK
        FeedbackType.TURN_SIGNAL_MISSING -> ToneGenerator.TONE_PROP_BEEP
        FeedbackType.SKILL_PREVIEW -> ToneGenerator.TONE_PROP_PROMPT
        FeedbackType.HARD_BRAKE_REPEAT -> ToneGenerator.TONE_CDMA_PIP
    }

    /** onDone이 안 오는 엔진도 있어 일정 시간 뒤에는 포커스를 반드시 돌려준다. */
    private fun scheduleFocusRelease(afterMs: Long) {
        releaseFocus?.let(main::removeCallbacks)
        val r = Runnable { abandonFocus() }
        releaseFocus = r
        main.postDelayed(r, afterMs)
    }

    private fun abandonFocus() {
        releaseFocus?.let(main::removeCallbacks)
        releaseFocus = null
        audioManager?.abandonAudioFocusRequest(focusRequest)
    }

    private fun logTtsUnavailable(reason: String) {
        if (ttsUnavailableLogged) return
        ttsUnavailableLogged = true
        logger.w(TAG, "TTS 사용 불가($reason), 알림음만 냅니다")
    }

    internal companion object {
        /** 새 경고는 이전 문장을 끊고 바로 말한다. 단 말하는 중인 경고는 끊지 않고 뒤에 잇는다. */
        fun queueModeFor(warning: Boolean, warningSpeaking: Boolean): Int =
            if (warning && !warningSpeaking) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD

        const val TAG = "VoicePlayer"
        const val EARCON_MS = 250
        const val EARCON_VOLUME = 80
        const val FOCUS_SAFETY_MS = 10_000L
        const val GAP_SUFFIX = "-gap"
    }
}
