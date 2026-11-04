package com.taraang.app.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
data object SplashRoute

/** [level] 0이면 LevelProgress의 현재 단계를 쓴다. 복기 화면의 "다음 단계"가 값을 넘긴다. */
@Serializable
data class HomeRoute(val level: Int = 0)

@Serializable
data class CourseDetailRoute(val courseId: String)

/** 진행 중 주행은 DriveController.state 하나뿐이라 인자가 없다. */
@Serializable
data object DriveRoute

@Serializable
data class ReviewRoute(val sessionId: String)

@Serializable
data object HistoryRoute

@Serializable
data object GoalEditRoute

@Serializable
data object SettingsRoute
