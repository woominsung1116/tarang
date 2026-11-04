package com.taraang.app.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taraang.app.R
import com.taraang.app.domain.model.Course
import com.taraang.app.domain.model.CourseSource
import com.taraang.app.domain.model.GoalRoute
import com.taraang.app.domain.model.LevelProgress
import com.taraang.app.domain.model.Skill
import com.taraang.app.ui.Format
import com.taraang.app.ui.components.Badge
import com.taraang.app.ui.components.EmptyState
import com.taraang.app.ui.components.ErrorState
import com.taraang.app.ui.components.LevelStepper
import com.taraang.app.ui.components.NavRow
import com.taraang.app.ui.components.NoteBox
import com.taraang.app.ui.components.PrimaryButton
import com.taraang.app.ui.components.SecondaryButton
import com.taraang.app.ui.components.SectionHeader
import com.taraang.app.ui.components.SkeletonBlock
import com.taraang.app.ui.components.SkillChips
import com.taraang.app.ui.components.TaraangCard
import androidx.navigation.toRoute
import com.taraang.app.ui.UiConfig
import com.taraang.app.ui.containerViewModel
import com.taraang.app.ui.navigation.HomeRoute
import com.taraang.app.ui.preview.PreviewData
import com.taraang.app.ui.theme.Graphite
import com.taraang.app.ui.theme.Graphite2
import com.taraang.app.ui.theme.OrangeText
import com.taraang.app.ui.theme.TaraangTheme
import com.taraang.app.ui.theme.White

@Composable
fun HomeDestination(
    onOpenCourse: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenGoal: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm = containerViewModel { c, h -> HomeViewModel(c, h.toRoute<HomeRoute>().level) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshProgress() }
    HomeScreen(
        state = state,
        onRetry = vm::retry,
        onOpenCourse = onOpenCourse,
        onOpenHistory = onOpenHistory,
        onOpenGoal = onOpenGoal,
        onOpenSettings = onOpenSettings,
    )
}

/** 가로 넓은 차량 화면은 왼쪽 요약 + 오른쪽 코스 목록, 좁은 화면은 한 줄로 쌓는다. */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onRetry: () -> Unit,
    onOpenCourse: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenGoal: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(White)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val wide = maxWidth >= UiConfig.WideLayoutMinWidth
        if (wide) {
            Row(Modifier.fillMaxSize().padding(horizontal = 40.dp)) {
                Column(
                    Modifier
                        .weight(0.42f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 32.dp, bottom = 32.dp, end = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    SummaryPanel(state, onOpenGoal, onOpenHistory, onOpenSettings)
                }
                LazyColumn(
                    Modifier.weight(0.58f).fillMaxHeight(),
                    contentPadding = PaddingValues(top = 32.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    courseSection(state, onRetry, onOpenCourse)
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        SummaryPanel(state, onOpenGoal, onOpenHistory, onOpenSettings)
                    }
                }
                courseSection(state, onRetry, onOpenCourse)
            }
        }
    }
}

@Composable
private fun SummaryPanel(
    state: HomeUiState,
    onOpenGoal: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painterResource(R.drawable.taraang_logo_mark),
            contentDescription = null,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(stringResource(R.string.home_greeting), style = MaterialTheme.typography.headlineMedium, color = Graphite)
            Text(stringResource(R.string.home_greeting_sub), style = MaterialTheme.typography.bodyMedium, color = Graphite2)
        }
    }
    if (!state.progressLoaded) {
        SkeletonBlock(150.dp)
    } else if (state.progress != null) {
        LevelCard(state.progress, state.level)
    }
    GoalCard(state.goal, onOpenGoal)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NavRow(Icons.Outlined.History, stringResource(R.string.home_history), onClick = onOpenHistory)
        NavRow(Icons.Outlined.Settings, stringResource(R.string.home_settings), onClick = onOpenSettings)
    }
}

@Composable
private fun LevelCard(progress: LevelProgress, level: Int) {
    TaraangCard(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.home_level_title, progress.currentLevel, Format.levelName(progress.currentLevel)),
            style = MaterialTheme.typography.titleLarge,
            color = Graphite,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (progress.canAdvance) stringResource(R.string.home_level_can_advance)
            else stringResource(R.string.home_level_count, progress.completedCoursesAtLevel, progress.requiredToAdvance),
            style = MaterialTheme.typography.bodyMedium,
            color = Graphite2,
        )
        Spacer(Modifier.height(20.dp))
        LevelStepper(progress)
        if (level > progress.currentLevel) {
            Spacer(Modifier.height(16.dp))
            Badge(stringResource(R.string.home_level_preview, level), emphasized = true)
        }
    }
}

@Composable
private fun GoalCard(goal: GoalRoute?, onOpenGoal: () -> Unit) {
    TaraangCard(Modifier.fillMaxWidth(), onClick = onOpenGoal) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Flag, contentDescription = null, tint = Graphite, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                goal?.name ?: stringResource(R.string.home_goal_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = Graphite,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Graphite2, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(12.dp))
        if (goal == null) {
            Text(stringResource(R.string.home_goal_empty_body), style = MaterialTheme.typography.bodyMedium, color = Graphite2)
        } else {
            if (goal.skills.values.any { it > 0 }) {
                Text(stringResource(R.string.home_goal_skills), style = MaterialTheme.typography.bodySmall, color = Graphite2)
                Spacer(Modifier.height(8.dp))
                SkillChips(goal.skills)
            }
            if (goal.hardSpots.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.home_goal_hard_spots, goal.hardSpots.joinToString(", ") { it.label }),
                    style = MaterialTheme.typography.bodySmall,
                    color = Graphite2,
                )
            }
        }
    }
}

private fun LazyListScope.courseSection(
    state: HomeUiState,
    onRetry: () -> Unit,
    onOpenCourse: (String) -> Unit,
) {
    item(key = "header") {
        SectionHeader(stringResource(R.string.home_courses_title, state.level, Format.levelName(state.level)))
    }
    when (val courses = state.courses) {
        CoursesState.Loading -> items(3, key = { "skeleton$it" }) { SkeletonBlock(220.dp) }
        is CoursesState.Error -> item(key = "error") { ErrorState(courses.message, onRetry) }
        is CoursesState.Empty -> item(key = "empty") {
            EmptyState(
                title = courses.message ?: stringResource(R.string.home_courses_empty_title),
                body = stringResource(R.string.home_courses_empty_body),
                action = { SecondaryButton(stringResource(R.string.action_retry), onRetry) },
            )
        }
        is CoursesState.Loaded -> {
            if (state.showingSavedCourses) {
                item(key = "saved") {
                    NoteBox(listOf(stringResource(R.string.home_saved_courses_notice)))
                }
            }
            items(courses.courses, key = { "course:${it.id}" }) { course ->
                CourseCard(course, onClick = { onOpenCourse(course.id) }, goalSkills = state.goalSkillsIn(course))
            }
            item(key = "note") {
                Text(
                    stringResource(R.string.course_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = Graphite2,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
fun CourseCard(course: Course, onClick: () -> Unit, modifier: Modifier = Modifier, goalSkills: List<Skill> = emptyList()) {
    TaraangCard(modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    course.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Graphite,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.course_meta, Format.distance(course.distanceM), course.maneuvers.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Graphite2,
                )
            }
            if (course.source != CourseSource.SERVER) {
                Spacer(Modifier.width(12.dp))
                Badge(stringResource(R.string.course_source_saved))
            }
        }
        if (goalSkills.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            val names = goalSkills.joinToString("·") { it.label }
            Text(
                stringResource(R.string.home_course_goal_focus, names + Format.objectParticle(names)),
                style = MaterialTheme.typography.bodyMedium,
                color = OrangeText,
            )
        }
        if (course.skillCounts.values.any { it > 0 }) {
            Spacer(Modifier.height(16.dp))
            SkillChips(course.skillCounts)
        }
        if (course.warnings.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            NoteBox(course.warnings, title = stringResource(R.string.course_warnings_title))
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton(stringResource(R.string.course_open), onClick, modifier = Modifier.fillMaxWidth())
    }
}

@Preview(widthDp = 1408, heightDp = 792)
@Composable
private fun HomeWidePreview() {
    TaraangTheme {
        HomeScreen(
            state = HomeUiState(
                progress = PreviewData.progress,
                progressLoaded = true,
                level = 2,
                goal = PreviewData.goal,
                courses = CoursesState.Loaded(listOf(PreviewData.course, PreviewData.course.copy(id = "b", source = CourseSource.BUNDLED))),
            ),
            onRetry = {}, onOpenCourse = {}, onOpenHistory = {}, onOpenGoal = {}, onOpenSettings = {},
        )
    }
}

@Preview(widthDp = 720, heightDp = 1200)
@Composable
private fun HomeNarrowLoadingPreview() {
    TaraangTheme {
        HomeScreen(
            state = HomeUiState(progress = PreviewData.progress, progressLoaded = true, level = 2, goal = null, courses = CoursesState.Loading),
            onRetry = {}, onOpenCourse = {}, onOpenHistory = {}, onOpenGoal = {}, onOpenSettings = {},
        )
    }
}
