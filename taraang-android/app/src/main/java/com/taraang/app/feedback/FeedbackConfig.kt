package com.taraang.app.feedback

/**
 * 피드백 판정 임계값. ARCHITECTURE.md 5장의 값을 한곳에 모은다.
 * 각 값 옆에 근거를 적는다. 출처: hyundai/docs/research/FACT_CHECK_20261022.md.
 * "코칭 가설값"은 법·공단 근거가 없는 값이라 시험 주행 로그를 리플레이해 조정한다.
 */
data class FeedbackConfig(
    // 발화 제한
    val minGapMs: Long = 20_000, // 코칭 가설값
    val maxMessagesPerDrive: Int = 12, // 코칭 가설값
    val sameTypeCooldownMs: Long = 60_000, // 코칭 가설값
    val silentNearTurnM: Double = 20.0, // 코칭 가설값: 회전 조작 중에는 말하지 않는다

    // 방향지시등
    val turnSignalNagDistanceM: Double = 50.0, // 코칭 가설값: 기록 판정(40m)보다 먼저 한 번 알려 줄 여유
    /** 법: 교차로 가장자리 30m 전(도로교통법 시행령 별표 2). 코스 회전 지점은 교차로 중심 노드라 반폭 약 10m를 더한다. */
    val turnSignalJudgeDistanceM: Double = 40.0,
    /** 앞 회전에서 이 거리 안에 붙은 회전은 판정 거리 전에 신호를 넣을 수 없으니 판정하지 않는다(판정 거리와 같은 근거). */
    val turnSignalMinSpacingM: Double = 40.0,
    /** 다음 회전까지 거리가 이만큼 늘면 다른 회전으로 본다(TBT가 다음 회전으로 넘어감). */
    val newTurnDistanceJumpM: Double = 25.0,

    // 어린이보호구역
    val schoolZoneAheadM: Int = 300, // 코칭 가설값: 예고 거리
    /** 지점 제한속도를 모를 때만 쓴다. 도로교통법 제12조 "시속 30km 이내", 구간·시간대별로 다를 수 있다(시행규칙 제9조). */
    val schoolZoneFallbackLimitKmh: Double = 30.0,
    val schoolZoneSustainMs: Long = 3_000, // 코칭 가설값

    // 제한속도
    /** 코칭 가설값: 공단 eTAS 과속 기준(+20km/h)보다 초보용으로 엄격하게 잡았다. 법이 정한 '지속 초과' 기준은 없다. */
    val speedOverLimitKmh: Double = 10.0,
    val speedSustainMs: Long = 5_000, // 코칭 가설값(eTAS 장기과속은 +20이 3분)
    /** 초과 상태가 끝났다고 보는 여유. 경계에서 흔들릴 때 중복 기록을 막는다. */
    val speedHysteresisKmh: Double = 3.0,

    // 연습 기술 예고
    val skillPreviewMaxM: Double = 150.0, // 코칭 가설값
    val skillPreviewMinM: Double = 50.0, // 코칭 가설값
    val maneuverReachedM: Double = 25.0,
    val maneuverMatchM: Double = 80.0,

    // 급제동: 한국교통안전공단 eTAS 위험운전행동 급감속 택시 기준 준용(승용차 공식 기준 없음)
    /** 감속을 시작한 속도가 이 값 이하면 [hardBrakeDropLowKmh], 초과면 [hardBrakeDropHighKmh]를 쓴다. eTAS 택시 30km/h 구분. */
    val hardBrakeSplitKmh: Double = 30.0,
    val hardBrakeDropLowKmh: Double = 14.0, // eTAS 택시: 30km/h 이하에서 초당 14km/h 이상 감속
    val hardBrakeDropHighKmh: Double = 15.0, // eTAS 택시: 30km/h 초과에서 초당 15km/h 이상 감속
    /** eTAS: 감속 후 속도 6km/h 이상이어야 급감속. 5km/h 이하로 서면 급정지로 따로 분류하고 여기서는 정상 정지로 보고 세지 않는다. */
    val hardBrakeMinEndKmh: Double = 6.0,
    /** 감속 시작 시점의 평활 속도가 이보다 낮으면 세지 않는다. 신호 대기 중 위치 튐(짧은 창 속도가 튐)을 급제동으로 보지 않게. 코칭 가설값. */
    val hardBrakeMinStartSmoothedKmh: Double = 10.0,
    val hardBrakeWindowMs: Long = 1_000, // eTAS: "초당" 감속
    /** 감속 판정용 속도 창. 평활한 표시 속도로는 1초 감속이 깎여 잡히지 않아 평활 없이 짧게 잰다(리플레이 프레임 500ms와 같다). */
    val hardBrakeSpeedWindowMs: Long = 500,
    val hardBrakeRepeatCount: Int = 3, // 코칭 가설값
    /** 급제동 직후가 아니라 안정 구간에서 말한다. */
    val hardBrakeQuietAfterMs: Long = 5_000,

    // 코스 이탈
    val offCourseM: Double = 60.0,
    val offCourseSustainMs: Long = 5_000,

    // 속도 추정
    val speedWindowMs: Long = 1_500,
    val speedSmoothingAlpha: Double = 0.6,
    val stationaryBelowKmh: Double = 3.0,
    val maxPlausibleKmh: Double = 180.0,
    /** 연속으로 이만큼 튀면 튄 게 아니라 실제 이동(리플레이 재시작 등)으로 보고 다시 시작한다. */
    val jumpResetCount: Int = 3,
    val speedStaleMs: Long = 5_000,
)
