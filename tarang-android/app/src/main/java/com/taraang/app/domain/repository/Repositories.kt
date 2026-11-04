package com.taraang.app.domain.repository

import com.taraang.app.core.AppResult
import com.taraang.app.core.LatLng
import com.taraang.app.domain.model.Course
import com.taraang.app.domain.model.DriveEvent
import com.taraang.app.domain.model.DriveSession
import com.taraang.app.domain.model.DriveSummary
import com.taraang.app.domain.model.FeedbackMessage
import com.taraang.app.domain.model.FeedbackSettings
import com.taraang.app.domain.model.GoalRoute
import com.taraang.app.domain.model.LevelProgress
import com.taraang.app.domain.model.SegmentFeeling
import com.taraang.app.domain.model.SignalMode
import com.taraang.app.domain.model.Skill
import com.taraang.app.domain.signal.NaviSnapshot
import com.taraang.app.domain.signal.SignalAvailability
import com.taraang.app.domain.signal.VehicleSnapshot
import com.taraang.app.domain.signal.WarningType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface CourseRepository {
    /** 서버 → 캐시 → 번들 순으로 대체한다. 결과의 Course.source로 출처를 알 수 있다. */
    suspend fun recommend(origin: LatLng, level: Int, focus: Set<Skill> = emptySet()): AppResult<List<Course>>

    /** 목표 경로(출발→도착)를 운전 기술 단위로 분해한다(IDEA 3.1). 서버 `/v1/route-skills`. 결과는 저장하지 않아도 되는 계산값. */
    suspend fun analyzeRoute(origin: LatLng, destination: LatLng): AppResult<Map<Skill, Int>>
    suspend fun get(courseId: String): Course?
    /** 번들 코스의 출발지. 시연·오프라인 기본값. */
    fun demoOrigin(): LatLng
}

interface GoalRepository {
    val goals: Flow<List<GoalRoute>>
    suspend fun save(goal: GoalRoute): AppResult<Unit>
    suspend fun delete(goalId: String): AppResult<Unit>
}

interface DriveRepository {
    val sessions: Flow<List<DriveSession>>
    suspend fun startSession(courseId: String, mode: SignalMode): AppResult<DriveSession>
    suspend fun appendEvent(event: DriveEvent): AppResult<Unit>
    /**
     * [drivenAlongCourseM]: 코스 경로를 따라 실제로 진행한 거리(단계 진행 판정용). 모르면 null.
     * [interrupted]: 정상 종료가 아니다(시작 중 취소·주행 루프 오류). 단계 진행과 "지난 주행" 비교에서 빠진다.
     */
    suspend fun finishSession(sessionId: String, drivenAlongCourseM: Double? = null, interrupted: Boolean = false): AppResult<DriveSession>
    suspend fun saveFeelings(feelings: List<SegmentFeeling>): AppResult<Unit>
    suspend fun summary(sessionId: String): AppResult<DriveSummary>
    suspend fun levelProgress(): LevelProgress
    /** 앱 시작 시 끝나지 않은 세션을 "중단됨"으로 닫는다. */
    suspend fun closeInterruptedSessions(): Int
    /** 사용자 요청으로 주행 기록(세션·이벤트·느낌)을 모두 지운다. 목표 경로와 설정은 남긴다. 진행 중인 주행이 있으면 Err. */
    suspend fun deleteAllHistory(): AppResult<Unit>
}

interface SettingsRepository {
    val settings: Flow<FeedbackSettings>
    suspend fun update(transform: (FeedbackSettings) -> FeedbackSettings)
}

/** 화면이 구독하는 진행 중 주행 상태. */
data class LiveDriveState(
    val session: DriveSession? = null,
    val vehicle: VehicleSnapshot = VehicleSnapshot(),
    val navi: NaviSnapshot = NaviSnapshot(),
    val availability: SignalAvailability = SignalAvailability(),
    val estimatedSpeedKmh: Double? = null,
    val lastFeedback: FeedbackMessage? = null,
    val eventCount: Int = 0,
    val finished: Boolean = false,
    /** 주행 중 새로 켜졌고 아직 켜진 경고. 비어 있지 않은 동안 화면은 경고를 보여 준다(ARCHITECTURE.md 5장). */
    val newWarnings: Set<WarningType> = emptySet(),
)

/**
 * 주행 세션 오케스트레이션: 신호 소스 시작 → 피드백 엔진 → 이벤트 저장 → 음성 출력.
 * ViewModel은 이것만 호출한다.
 */
interface DriveController {
    val state: StateFlow<LiveDriveState>
    suspend fun start(course: Course, mode: SignalMode, replayAsset: String? = null): AppResult<DriveSession>

    /**
     * 실차 주행을 차량 내비로 넘기기 직전(앱이 아직 앞에 있을 때) 부른다. 주행 유지 장치(포그라운드 서비스)를 미리 켠다.
     * Android 12+는 배경에서 포그라운드 서비스를 시작할 수 없는데, 내비가 앞에 온 뒤에야 [start]가 불리기 때문이다.
     * [start]가 이어지지 않으면 [cancelPreparedStart]로 되돌린다.
     */
    fun prepareLiveStart()

    /** [prepareLiveStart] 뒤 주행을 시작하지 않았을 때 부른다. 주행이 이미 시작됐으면 아무것도 안 한다. */
    fun cancelPreparedStart()
    suspend fun stop(): AppResult<DriveSession?>
    /** [courseId] 코스에서 녹화한 시연 로그 이름. 다른 코스의 로그는 회전 판정·코스 이탈 기록을 틀리게 만든다. */
    suspend fun replayAssets(courseId: String): List<String>
    /** 시연 로그가 있는 코스 id. 홈에서 시연 가능한 코스를 앞에 두고 표시한다. 한 번 읽어 캐시한다. */
    suspend fun demoCourseIds(): Set<String>
}
