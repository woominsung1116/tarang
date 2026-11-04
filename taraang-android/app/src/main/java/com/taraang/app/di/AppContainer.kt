package com.taraang.app.di

import com.taraang.app.core.Logger
import com.taraang.app.domain.repository.CourseRepository
import com.taraang.app.domain.repository.DriveController
import com.taraang.app.domain.repository.DriveRepository
import com.taraang.app.domain.repository.GoalRepository
import com.taraang.app.domain.repository.SettingsRepository
import com.taraang.app.domain.signal.NavigationLauncher

/** UI가 아는 유일한 의존성 묶음. 구현은 DefaultAppContainer(core 담당). */
interface AppContainer {
    val logger: Logger
    val courseRepository: CourseRepository
    val goalRepository: GoalRepository
    val driveRepository: DriveRepository
    val settingsRepository: SettingsRepository
    val driveController: DriveController
    val navigationLauncher: NavigationLauncher
}

/** Application이 구현한다. UI는 `(context.applicationContext as AppContainerOwner).container`로 얻는다. */
interface AppContainerOwner {
    val container: AppContainer
}
