package com.taraang.app.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.taraang.app.ui.course.CourseDetailDestination
import com.taraang.app.ui.drive.DriveDestination
import com.taraang.app.ui.goal.GoalEditDestination
import com.taraang.app.ui.history.HistoryDestination
import com.taraang.app.ui.home.HomeDestination
import com.taraang.app.ui.review.ReviewDestination
import com.taraang.app.ui.settings.SettingsDestination
import com.taraang.app.ui.splash.SplashScreen

/**
 * Splash → Home ─┬→ CourseDetail → Drive → Review ─→ Home(level)
 *                ├→ History → Review
 *                ├→ GoalEdit
 *                └→ Settings
 *
 * 사용자가 누르는 이동은 화면이 RESUMED일 때만 받는다(dropUnlessResumed). 전환 애니메이션 중 두 번 누르면
 * 같은 화면이 두 번 쌓이거나 뒤로가기가 두 번 일어나 Home까지 빠지는 것을 막는다.
 * 타이머·작업 완료 같은 이벤트로 일어나는 이동은 막지 않는다. 백그라운드에서 끝나도 버려지면 안 된다.
 */
@Composable
fun TaraangNavHost(modifier: Modifier = Modifier, navController: NavHostController = rememberNavController()) {
    NavHost(
        navController = navController,
        startDestination = SplashRoute,
        modifier = modifier,
        enterTransition = { fadeIn(tween(220)) },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { fadeOut(tween(180)) },
    ) {
        composable<SplashRoute> {
            SplashScreen(onFinished = {
                navController.navigate(HomeRoute()) { popUpTo<SplashRoute> { inclusive = true } }
            })
        }
        composable<HomeRoute> { entry ->
            HomeDestination(
                onOpenCourse = { id -> entry.ifResumed { navController.navigate(CourseDetailRoute(id)) } },
                onOpenHistory = dropUnlessResumed { navController.navigate(HistoryRoute) },
                onOpenGoal = dropUnlessResumed { navController.navigate(GoalEditRoute) },
                onOpenSettings = dropUnlessResumed { navController.navigate(SettingsRoute) },
            )
        }
        composable<CourseDetailRoute> {
            CourseDetailDestination(
                onBack = dropUnlessResumed { navController.popBackStack() },
                onDriveStarted = { navController.navigate(DriveRoute) { launchSingleTop = true } },
            )
        }
        composable<DriveRoute> {
            DriveDestination(
                onFinished = { sessionId ->
                    if (sessionId != null) {
                        navController.navigate(ReviewRoute(sessionId)) { popUpTo<HomeRoute>() }
                    } else {
                        navController.popBackStack<HomeRoute>(inclusive = false)
                    }
                },
            )
        }
        composable<ReviewRoute> { entry ->
            ReviewDestination(
                onBack = dropUnlessResumed { navController.popBackStack() },
                onPracticeLevel = { level ->
                    entry.ifResumed {
                        navController.navigate(HomeRoute(level)) { popUpTo<HomeRoute> { inclusive = true } }
                    }
                },
            )
        }
        composable<HistoryRoute> { entry ->
            HistoryDestination(
                onBack = dropUnlessResumed { navController.popBackStack() },
                onOpenSession = { id -> entry.ifResumed { navController.navigate(ReviewRoute(id)) } },
            )
        }
        composable<GoalEditRoute> {
            GoalEditDestination(
                onBack = dropUnlessResumed { navController.popBackStack() },
                onSaved = { navController.popBackStack<GoalEditRoute>(inclusive = true) },
            )
        }
        composable<SettingsRoute> {
            SettingsDestination(onBack = dropUnlessResumed { navController.popBackStack() })
        }
    }
}

/** 인자가 있는 콜백용 [dropUnlessResumed]. 같은 기준(이 화면 항목이 RESUMED)으로 거른다. */
private inline fun NavBackStackEntry.ifResumed(block: () -> Unit) {
    if (lifecycle.currentState == Lifecycle.State.RESUMED) block()
}
