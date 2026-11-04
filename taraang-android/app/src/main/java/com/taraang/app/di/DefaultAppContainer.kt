package com.taraang.app.di

import android.content.Context
import android.os.SystemClock
import com.taraang.app.BuildConfig
import androidx.datastore.preferences.preferencesDataStore
import com.taraang.app.audio.VoiceFeedbackPlayer
import com.taraang.app.core.Clock
import com.taraang.app.core.Logger
import com.taraang.app.drive.DriveControllerImpl
import com.taraang.app.drive.ForegroundServiceKeepAlive
import com.taraang.app.data.DriveRepositoryImpl
import com.taraang.app.data.GoalRepositoryImpl
import com.taraang.app.data.SettingsRepositoryImpl
import com.taraang.app.data.course.AssetBundledCourses
import com.taraang.app.data.course.CourseApiClient
import com.taraang.app.data.course.CourseRepositoryImpl
import com.taraang.app.data.course.FileCourseCache
import com.taraang.app.data.db.TaraangDatabase
import com.taraang.app.data.db.transactionRunner
import com.taraang.app.domain.model.SignalMode
import com.taraang.app.domain.repository.DriveController
import com.taraang.app.domain.repository.DriveRepository
import com.taraang.app.domain.repository.GoalRepository
import com.taraang.app.domain.repository.SettingsRepository
import com.taraang.app.domain.signal.NavigationLauncher
import com.taraang.app.domain.signal.SignalSource
import com.taraang.app.vehicle.AssetReplayAssets
import com.taraang.app.vehicle.PleosNavigationLauncher
import com.taraang.app.vehicle.PleosSignalSource
import com.taraang.app.vehicle.ReplaySignalSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import java.io.File

private val Context.settingsStore by preferencesDataStore(name = "feedback_settings")

/**
 * 앱 전체 의존성. Application이 한 번 만든다. 무거운 객체(DB, TTS, OkHttp)는 처음 쓸 때 만든다.
 * [appScope]는 Application 수명이라 주행 루프가 화면 회전·이동에도 살아 있다.
 */
class DefaultAppContainer(
    context: Context,
    val appScope: CoroutineScope,
    override val logger: Logger,
    /** 벽시계: 저장되는 시각(세션·이벤트·캐시). */
    private val clock: Clock = Clock.System,
    /** 단조 시계: 엔진 틱·신호 시각·리플레이 진행. 벽시계 변경(시간대·NTP)에 흔들리지 않는다. */
    private val tickClock: Clock = Clock { SystemClock.elapsedRealtime() },
) : AppContainer {

    private val appContext = context.applicationContext

    private val database by lazy { TaraangDatabase.create(appContext) }
    private val replayAssets by lazy { AssetReplayAssets(appContext.assets) }

    override val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(
            appContext.settingsStore,
            logger,
            defaultServerUrl = BuildConfig.COURSE_SERVER_URL,
            allowCleartext = BuildConfig.DEBUG,
        )
    }

    val courseRepositoryImpl: CourseRepositoryImpl by lazy {
        val api = CourseApiClient(
            client = CourseApiClient.defaultHttpClient(),
            baseUrl = { settingsRepository.settings.first().courseServerUrl },
            logger = logger,
            allowCleartext = BuildConfig.DEBUG,
        )
        CourseRepositoryImpl(
            remote = api,
            cache = FileCourseCache(File(appContext.filesDir, "course_cache")),
            bundled = AssetBundledCourses(appContext.assets),
            logger = logger,
        )
    }
    override val courseRepository get() = courseRepositoryImpl

    override val goalRepository: GoalRepository by lazy { GoalRepositoryImpl(database.goalDao(), logger) }

    override val driveRepository: DriveRepository by lazy {
        DriveRepositoryImpl(
            database.sessionDao(), database.eventDao(), database.feelingDao(), courseRepository, clock, logger,
            tx = database.transactionRunner(),
        )
    }

    override val navigationLauncher: NavigationLauncher by lazy { PleosNavigationLauncher(appContext, logger) }

    override val driveController: DriveController by lazy {
        DriveControllerImpl(
            driveRepository = driveRepository,
            settingsRepository = settingsRepository,
            signalSourceFactory = ::createSignalSource,
            replayAssetNames = replayAssets::list,
            speaker = VoiceFeedbackPlayer(appContext, logger),
            clock = clock,
            logger = logger,
            scope = appScope,
            tickClock = tickClock,
            keepAlive = ForegroundServiceKeepAlive(appContext, logger),
        )
    }

    private fun createSignalSource(mode: SignalMode, replayAsset: String?): SignalSource = when (mode) {
        SignalMode.LIVE -> PleosSignalSource(appContext, tickClock, logger)
        SignalMode.REPLAY -> ReplaySignalSource(
            assets = replayAssets,
            assetName = requireNotNull(replayAsset) { "REPLAY에는 로그 이름이 필요" },
            clock = tickClock,
            scope = appScope,
            logger = logger,
        )
    }
}
