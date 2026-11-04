package com.taraang.app.ui.home

import com.taraang.app.domain.model.Skill
import com.taraang.app.core.AppError
import com.taraang.app.core.AppResult
import com.taraang.app.domain.model.CourseSource
import com.taraang.app.domain.model.GoalRoute
import com.taraang.app.domain.repository.GoalRepository
import com.taraang.app.ui.EmptyGoalRepository
import com.taraang.app.ui.MainDispatcherRule
import com.taraang.app.ui.RecordingCourseRepository
import com.taraang.app.ui.ScriptedDriveRepository
import com.taraang.app.ui.TestContainer
import com.taraang.app.ui.preview.PreviewData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val courses = RecordingCourseRepository()

    @Test
    fun `recommends from the goal origin at the current level`() {
        val vm = HomeViewModel(TestContainer(courseRepository = courses), requestedLevel = 0)
        assertEquals(listOf(PreviewData.goal.origin to PreviewData.progress.currentLevel), courses.calls)
        assertEquals(CoursesState.Loaded(listOf(PreviewData.course)), vm.state.value.courses)
        assertTrue(vm.state.value.progressLoaded)
        assertFalse(vm.state.value.showingSavedCourses)
    }

    @Test
    fun `requested level overrides progress only when in range`() {
        HomeViewModel(TestContainer(courseRepository = courses), requestedLevel = 3)
        HomeViewModel(TestContainer(courseRepository = courses), requestedLevel = 9)
        assertEquals(listOf(3, PreviewData.progress.currentLevel), courses.calls.map { it.second })
    }

    @Test
    fun `without a goal it uses the demo origin`() {
        HomeViewModel(TestContainer(courseRepository = courses, goalRepository = EmptyGoalRepository()), 0)
        assertEquals(courses.demoOrigin(), courses.calls.single().first)
    }

    @Test
    fun `failing goal flow falls back to the demo origin`() {
        val broken = object : GoalRepository by EmptyGoalRepository() {
            override val goals: Flow<List<GoalRoute>> = flow { throw IllegalStateException("db") }
        }
        val vm = HomeViewModel(TestContainer(courseRepository = courses, goalRepository = broken), 0)
        assertNull(vm.state.value.goal)
        assertEquals(courses.demoOrigin(), courses.calls.single().first)
    }

    @Test
    fun `progress read failure still shows level 1 courses`() {
        val drive = ScriptedDriveRepository(progress = { throw IllegalStateException("db") })
        val vm = HomeViewModel(TestContainer(courseRepository = courses, driveRepository = drive), 0)
        assertNull(vm.state.value.progress)
        assertTrue(vm.state.value.progressLoaded)
        assertEquals(1, vm.state.value.level)
        assertTrue(vm.state.value.courses is CoursesState.Loaded)
    }

    @Test
    fun `no course is empty with a message, other errors are retryable errors`() {
        courses.result = AppResult.Err(AppError.NoCourse("none"))
        val vm = HomeViewModel(TestContainer(courseRepository = courses), 0)
        assertTrue((vm.state.value.courses as CoursesState.Empty).message != null)

        courses.result = AppResult.Err(AppError.Server(500, "INTERNAL", "boom"))
        vm.retry()
        assertTrue(vm.state.value.courses is CoursesState.Error)

        courses.result = AppResult.Ok(listOf(PreviewData.course))
        vm.retry()
        assertTrue(vm.state.value.courses is CoursesState.Loaded)
    }

    @Test
    fun `empty list is empty without a message`() {
        courses.result = AppResult.Ok(emptyList())
        val vm = HomeViewModel(TestContainer(courseRepository = courses), 0)
        assertEquals(CoursesState.Empty(null), vm.state.value.courses)
    }

    @Test
    fun `duplicate course ids are dropped`() {
        courses.result = AppResult.Ok(listOf(PreviewData.course, PreviewData.course.copy(title = "dup")))
        val vm = HomeViewModel(TestContainer(courseRepository = courses), 0)
        assertEquals(1, (vm.state.value.courses as CoursesState.Loaded).courses.size)
    }

    @Test
    fun `cached or bundled courses raise the saved courses notice`() {
        courses.result = AppResult.Ok(listOf(PreviewData.course.copy(source = CourseSource.BUNDLED)))
        val vm = HomeViewModel(TestContainer(courseRepository = courses), 0)
        assertTrue(vm.state.value.showingSavedCourses)
    }

    @Test
    fun `goal skills are passed as the recommendation focus`() {
        HomeViewModel(TestContainer(courseRepository = courses), 0)
        assertEquals(listOf(PreviewData.goal.skills.keys), courses.focuses)
    }

    @Test
    fun `without a goal the focus is empty`() {
        HomeViewModel(TestContainer(courseRepository = courses, goalRepository = EmptyGoalRepository()), 0)
        assertEquals(listOf(emptySet<Skill>()), courses.focuses)
    }

    @Test
    fun `changing goal skills reloads with the new focus, renaming does not`() {
        val goals = EmptyGoalRepository().apply { state.value = listOf(PreviewData.goal) }
        HomeViewModel(TestContainer(courseRepository = courses, goalRepository = goals), 0)
        goals.state.value = listOf(PreviewData.goal.copy(name = "다른 이름"))
        assertEquals(1, courses.focuses.size)
        goals.state.value = listOf(PreviewData.goal.copy(skills = mapOf(Skill.U_TURN to 1, Skill.MERGE to 0)))
        assertEquals(setOf(Skill.U_TURN), courses.focuses.last())
    }

    @Test
    fun `goalSkillsIn lists the goal skills the course practises in screen order`() {
        val vm = HomeViewModel(TestContainer(courseRepository = courses), 0)
        // 목표: 신호 좌회전·합류·지하주차장 / 코스: 신호 좌회전·우회전·어린이보호구역
        assertEquals(listOf(Skill.LEFT_TURN_SIGNALIZED), vm.state.value.goalSkillsIn(PreviewData.course))
        assertTrue(HomeUiState().goalSkillsIn(PreviewData.course).isEmpty())
    }
}
