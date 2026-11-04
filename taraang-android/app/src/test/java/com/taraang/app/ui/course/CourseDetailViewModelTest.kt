package com.taraang.app.ui.course

import com.taraang.app.core.AppError
import com.taraang.app.core.AppResult
import com.taraang.app.core.LatLng
import com.taraang.app.domain.model.SignalMode
import com.taraang.app.domain.signal.NavigationLauncher
import com.taraang.app.ui.MainDispatcherRule
import com.taraang.app.ui.ScriptedDriveController
import com.taraang.app.ui.TestContainer
import com.taraang.app.ui.navi
import com.taraang.app.ui.preview.PreviewData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CourseDetailViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val controller = ScriptedDriveController()
    private val courseId = PreviewData.course.id

    private fun vm(navi: NavigationLauncher = navi { _, _, _ -> AppResult.Ok(Unit) }) =
        CourseDetailViewModel(TestContainer(driveController = controller, navigationLauncher = navi), courseId, main.dispatcher)

    @Test
    fun `loads the course or reports not found`() {
        assertEquals(PreviewData.course, vm().state.value.course)
        val missing = CourseDetailViewModel(TestContainer(), "nope", main.dispatcher)
        assertFalse(missing.state.value.loading)
        assertNull(missing.state.value.course)
    }

    @Test
    fun `navigation start sends the course and starts a live drive`() = runTest {
        var sent: List<LatLng>? = null
        val vm = vm(navi { _, waypoints, _ -> sent = waypoints; AppResult.Ok(Unit) })
        vm.startWithNavigation()
        assertEquals(PreviewData.course.waypoints, sent)
        assertEquals(listOf(Triple(courseId, SignalMode.LIVE, null)), controller.starts)
        assertEquals(CourseDetailEvent.DriveStarted, vm.events.first())
        assertNull(vm.state.value.starting)
    }

    @Test
    fun `navigation failure does not start a drive and offers replay`() {
        val vm = vm(navi { _, _, _ -> AppResult.Err(AppError.Navigation("NO_NAVI", "x")) })
        vm.startWithNavigation()
        assertTrue(controller.starts.isEmpty())
        assertTrue(vm.state.value.offerReplay)
        assertNotNull(vm.state.value.errorMessage)

        vm.startFallbackReplay()
        assertEquals(listOf(Triple(courseId, SignalMode.REPLAY, "replay/demo_l2.json")), controller.starts)
    }

    @Test
    fun `second tap while starting is ignored`() {
        val gate = CompletableDeferred<AppResult<Unit>>()
        val vm = vm(navi { _, _, _ -> gate.await() })
        vm.startWithNavigation()
        assertEquals(StartAction.NAVI, vm.state.value.starting)
        vm.startWithNavigation()
        vm.startReplay()
        gate.complete(AppResult.Ok(Unit))
        assertEquals(1, controller.starts.size)
    }

    @Test
    fun `replay with one asset starts, several assets ask, none says missing`() {
        val vm = vm()
        vm.startReplay()
        assertEquals(SignalMode.REPLAY, controller.starts.single().second)

        controller.assets = { listOf("replay/a.json", "replay/b.json") }
        vm.startReplay()
        assertEquals(listOf("replay/a.json", "replay/b.json"), vm.state.value.replayChoices)
        vm.chooseReplay("replay/b.json")
        assertEquals("replay/b.json", controller.starts.last().third)
        assertTrue(vm.state.value.replayChoices.isEmpty())

        controller.assets = { emptyList() }
        vm.startReplay()
        assertTrue(vm.state.value.replayMissing)
        assertNull(vm.state.value.starting)
    }

    @Test
    fun `asset listing failure counts as no replay instead of crashing`() {
        controller.assets = { throw IllegalStateException("assets") }
        val vm = vm()
        vm.startReplay()
        assertTrue(vm.state.value.replayMissing)
    }

    @Test
    fun `drive start failure that is not about the car does not offer replay`() {
        controller.startResult = AppResult.Err(AppError.Storage("db"))
        val vm = vm()
        vm.startReplay()
        assertNotNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.offerReplay)
        vm.dismissError()
        assertNull(vm.state.value.errorMessage)
    }
}
