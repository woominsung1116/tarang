package com.taraang.app

import android.app.Application
import com.taraang.app.di.AppContainer
import com.taraang.app.di.AppContainerOwner
import com.taraang.app.di.DefaultAppContainer
import com.taraang.app.di.LogcatLogger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TaraangApp : Application(), AppContainerOwner {

    private val logger = LogcatLogger()

    /** 앱 수명 스코프. 한 작업의 실패가 다른 작업(주행 루프 등)을 끊지 않도록 SupervisorJob. */
    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> logger.e(TAG, "처리되지 않은 오류", e) },
    )

    private lateinit var defaultContainer: DefaultAppContainer
    override val container: AppContainer get() = defaultContainer

    override fun onCreate() {
        super.onCreate()
        defaultContainer = DefaultAppContainer(this, appScope, logger)
        appScope.launch {
            val closed = defaultContainer.driveRepository.closeInterruptedSessions()
            if (closed > 0) logger.w(TAG, "중단된 주행 ${closed}건을 닫았습니다")
        }
        appScope.launch { defaultContainer.courseRepositoryImpl.warmUp() }
    }

    private companion object {
        const val TAG = "App"
    }
}
