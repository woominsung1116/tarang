package com.taraang.app.ui.goal

import kotlinx.coroutines.CompletableDeferred
import com.taraang.app.ui.RecordingCourseRepository
import com.taraang.app.core.userMessage
import com.taraang.app.core.LatLng
import com.taraang.app.core.AppResult
import com.taraang.app.core.AppError
import androidx.lifecycle.SavedStateHandle
import com.taraang.app.core.Clock
import com.taraang.app.domain.model.Skill
import com.taraang.app.ui.EmptyGoalRepository
import com.taraang.app.ui.MainDispatcherRule
import com.taraang.app.ui.TestContainer
import com.taraang.app.ui.UiConfig
import com.taraang.app.ui.goal.GoalEditViewModel.Companion.parseCoordinate
import com.taraang.app.ui.goal.GoalEditViewModel.Companion.validate
import com.taraang.app.ui.preview.PreviewData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GoalEditViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val valid = GoalForm(
        name = "회사 가기",
        originLat = "35.231", originLon = "129.084",
        destLat = "35.2205", destLon = "129.0868",
        hardSpots = listOf(HardSpotForm(key = "a", label = "입구", lat = "35.22", lon = "129.08")),
    )

    @Test
    fun `valid form has no errors`() {
        assertTrue(validate(valid).isEmpty())
    }

    @Test
    fun `name is required and length limited after trimming`() {
        assertEquals(GoalFieldError.NAME_REQUIRED, validate(valid.copy(name = "  "))["name"])
        assertEquals(GoalFieldError.TOO_LONG, validate(valid.copy(name = "가".repeat(UiConfig.GOAL_NAME_MAX + 1)))["name"])
        assertNull(validate(valid.copy(name = "  " + "가".repeat(UiConfig.GOAL_NAME_MAX) + "  "))["name"])
    }

    @Test
    fun `coordinates must be finite numbers inside Korea`() {
        assertNull(parseCoordinate("NaN"))
        assertNull(parseCoordinate("Infinity"))
        assertNull(parseCoordinate("abc"))
        assertEquals(35.2, parseCoordinate(" 35.2 ")!!, 0.0)
        assertEquals(GoalFieldError.NOT_A_NUMBER, validate(valid.copy(originLat = "NaN"))["originLat"])
        val outside = validate(valid.copy(destLon = "200"))
        assertEquals(GoalFieldError.OUTSIDE_KOREA, outside["destLat"])
        assertEquals(GoalFieldError.OUTSIDE_KOREA, outside["destLon"])
    }

    @Test
    fun `hard spot label is required and length limited`() {
        val blank = valid.copy(hardSpots = listOf(HardSpotForm(key = "a", label = "", lat = "35.22", lon = "129.08")))
        assertEquals(GoalFieldError.LABEL_REQUIRED, validate(blank)["spot:a:label"])
        val long = valid.copy(hardSpots = listOf(HardSpotForm(key = "a", label = "가".repeat(UiConfig.SPOT_LABEL_MAX + 1), lat = "35.22", lon = "129.08")))
        assertEquals(GoalFieldError.TOO_LONG, validate(long)["spot:a:label"])
    }

    @Test
    fun `existing goal is loaded into the form`() {
        val vm = GoalEditViewModel(TestContainer(), SavedStateHandle())
        assertFalse(vm.state.value.loading)
        assertEquals(PreviewData.goal.id, vm.state.value.existingId)
        assertEquals(PreviewData.goal.name, vm.state.value.form.name)
    }

    @Test
    fun `draft survives process death and beats the stored goal`() {
        val handle = SavedStateHandle()
        GoalEditViewModel(TestContainer(), handle).edit { it.copy(name = "고치는 중") }
        val restored = GoalEditViewModel(TestContainer(), handle)
        assertEquals("고치는 중", restored.state.value.form.name)
    }

    @Test
    fun `corrupt draft is discarded`() {
        val handle = SavedStateHandle(mapOf("goalDraft" to "{not json"))
        val vm = GoalEditViewModel(TestContainer(), handle)
        assertEquals(PreviewData.goal.name, vm.state.value.form.name)
    }

    @Test
    fun `save with errors shows them and does not write`() {
        val repo = EmptyGoalRepository()
        val vm = GoalEditViewModel(TestContainer(goalRepository = repo), SavedStateHandle())
        vm.save()
        assertTrue(vm.state.value.errors.containsKey("name"))
        assertTrue(repo.state.value.isEmpty())
    }

    @Test
    fun `new goal is saved with the injected clock and the draft is cleared`() = runTest {
        val repo = EmptyGoalRepository()
        val handle = SavedStateHandle()
        val vm = GoalEditViewModel(TestContainer(goalRepository = repo), handle, Clock { 42L })
        vm.edit { valid }
        vm.save()
        val saved = repo.goals.first().single()
        assertEquals(42L, saved.createdAtMs)
        assertEquals("회사 가기", saved.name)
        assertEquals("입구", saved.hardSpots.single().label)
        assertEquals(GoalEditEvent.Done, vm.events.first())
        assertFalse("goalDraft" in handle.keys())
    }

    @Test
    fun `skill counts are capped and zero removes the skill`() {
        val vm = GoalEditViewModel(TestContainer(goalRepository = EmptyGoalRepository()), SavedStateHandle())
        vm.setSkillCount(Skill.MERGE, 99)
        assertEquals(UiConfig.SKILL_COUNT_MAX, vm.state.value.form.skills[Skill.MERGE])
        vm.setSkillCount(Skill.MERGE, 0)
        assertFalse(Skill.MERGE in vm.state.value.form.skills)
    }

    @Test
    fun `hard spots are capped`() {
        val vm = GoalEditViewModel(TestContainer(goalRepository = EmptyGoalRepository()), SavedStateHandle())
        repeat(UiConfig.HARD_SPOTS_MAX + 3) { vm.addHardSpot() }
        assertEquals(UiConfig.HARD_SPOTS_MAX, vm.state.value.form.hardSpots.size)
        assertFalse(vm.state.value.canAddSpot)
    }

    private fun analysisVm(courses: RecordingCourseRepository, goals: EmptyGoalRepository = EmptyGoalRepository()) =
        GoalEditViewModel(TestContainer(courseRepository = courses, goalRepository = goals), SavedStateHandle()).also { it.edit { valid } }

    @Test
    fun `analysis fills skills from the route and drops zero counts`() {
        val courses = RecordingCourseRepository(analysis = { AppResult.Ok(mapOf(Skill.LEFT_TURN_SIGNALIZED to 2, Skill.U_TURN to 1, Skill.MERGE to 0)) })
        val vm = analysisVm(courses)
        vm.analyzeRoute()
        assertEquals(listOf(LatLng(35.231, 129.084) to LatLng(35.2205, 129.0868)), courses.analyzed)
        assertEquals(RouteAnalysis.Done, vm.state.value.analysis)
        assertEquals(mapOf(Skill.LEFT_TURN_SIGNALIZED to 2, Skill.U_TURN to 1), vm.state.value.form.skills)
        assertFalse(vm.state.value.manualSkillsEnabled)
    }

    @Test
    fun `analysis failure shows the error message and opens manual entry`() {
        val error = AppError.Network("down")
        val courses = RecordingCourseRepository(analysis = { AppResult.Err(error) })
        val vm = analysisVm(courses)
        vm.analyzeRoute()
        assertEquals(RouteAnalysis.Failed(error.userMessage()), vm.state.value.analysis)
        assertTrue(vm.state.value.manualSkillsEnabled)
        assertTrue(vm.state.value.form.skills.isEmpty())
        vm.setSkillCount(Skill.MERGE, 1)
        assertEquals(1, vm.state.value.form.skills[Skill.MERGE])
    }

    @Test
    fun `analysis that throws is a failure, not a crash`() {
        val vm = analysisVm(RecordingCourseRepository(analysis = { throw IllegalStateException("boom") }))
        vm.analyzeRoute()
        assertTrue(vm.state.value.analysis is RouteAnalysis.Failed)
    }

    @Test
    fun `invalid coordinates are flagged and the repository is not called`() {
        val courses = RecordingCourseRepository()
        val vm = analysisVm(courses)
        vm.edit { it.copy(destLon = "200") }
        vm.analyzeRoute()
        assertTrue(courses.analyzed.isEmpty())
        assertEquals(GoalFieldError.OUTSIDE_KOREA, vm.state.value.errors["destLon"])
        assertEquals(RouteAnalysis.NotRun, vm.state.value.analysis)
    }

    @Test
    fun `changing origin or destination marks the analysis stale, renaming does not`() {
        val vm = analysisVm(RecordingCourseRepository())
        vm.analyzeRoute()
        vm.edit { it.copy(name = "새 이름") }
        assertFalse(vm.state.value.analysisStale)
        vm.edit { it.copy(destLat = "35.3") }
        assertTrue(vm.state.value.analysisStale)
        vm.analyzeRoute()
        assertFalse(vm.state.value.analysisStale)
    }

    @Test
    fun `second analyze tap while running is ignored`() {
        val gate = CompletableDeferred<AppResult<Map<Skill, Int>>>()
        val courses = RecordingCourseRepository(analysis = { gate.await() })
        val vm = analysisVm(courses)
        vm.analyzeRoute()
        vm.analyzeRoute()
        assertEquals(RouteAnalysis.Running, vm.state.value.analysis)
        gate.complete(AppResult.Ok(mapOf(Skill.RIGHT_TURN to 1)))
        assertEquals(1, courses.analyzed.size)
        assertEquals(RouteAnalysis.Done, vm.state.value.analysis)
    }

    @Test
    fun `demo fill runs the analysis`() {
        val courses = RecordingCourseRepository()
        val vm = GoalEditViewModel(TestContainer(courseRepository = courses, goalRepository = EmptyGoalRepository()), SavedStateHandle())
        vm.fillDemo()
        assertEquals(1, courses.analyzed.size)
        assertEquals(PreviewData.goal.skills, vm.state.value.form.skills)
    }

    @Test
    fun `stored goal with skills starts as analysed`() {
        val vm = GoalEditViewModel(TestContainer(), SavedStateHandle())
        assertEquals(RouteAnalysis.Done, vm.state.value.analysis)
    }

    @Test
    fun `analysed skills are saved into the goal`() = runTest {
        val goals = EmptyGoalRepository()
        val vm = analysisVm(RecordingCourseRepository(analysis = { AppResult.Ok(mapOf(Skill.ROUNDABOUT to 3)) }), goals)
        vm.analyzeRoute()
        vm.save()
        assertEquals(mapOf(Skill.ROUNDABOUT to 3), goals.goals.first().single().skills)
    }
}
