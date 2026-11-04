package com.taraang.app.ui

import androidx.compose.ui.unit.dp

/** 화면 쪽 시간·크기·입력 한도. 판정 임계값은 FeedbackConfig(feedback/)에 있고 여기에 두지 않는다. */
object UiConfig {
    /** 이 너비 이상이면 2단 배치. Pleos Connect 앱 영역은 약 1700×1160dp. */
    val WideLayoutMinWidth = 900.dp

    const val SPLASH_FADE_MS = 500
    const val SPLASH_TOTAL_MS = 1_200L
    /** 스플래시 로고·워드마크 조합 너비. Pleos 창(약 1700dp 폭)의 1/4 정도. */
    val SplashLockupWidth = 420.dp

    /** 주행 화면에서 피드백 문장을 크게 보여주는 시간. 이후 기본 문장으로 돌아간다. */
    const val FEEDBACK_VISIBLE_MS = 10_000L

    /** 다음 회전 거리 표시 단위. 틱마다 다시 그리지 않도록 반올림한다. */
    const val TURN_DISTANCE_STEP_M = 10

    /** 지도 타일이 이 시간 안에 한 장도 안 오면 단순 그림으로 바꾼다. */
    const val TILE_GRACE_MS = 6_000L

    /** 복기 화면 궤적 재생 길이. */
    const val REPLAY_MS = 10_000

    const val GOAL_NAME_MAX = 40
    const val SPOT_LABEL_MAX = 30
    const val HARD_SPOTS_MAX = 10
    const val SKILL_COUNT_MAX = 9
    const val SERVER_URL_MAX = 200

    /** 평문 HTTP를 허용하는 호스트(debug 빌드만, 주소 편집도 debug만). src/debug network_security_config, ServerUrlPolicy와 같아야 한다. */
    val PLAINTEXT_HOSTS = setOf("10.0.2.2", "localhost")
}
