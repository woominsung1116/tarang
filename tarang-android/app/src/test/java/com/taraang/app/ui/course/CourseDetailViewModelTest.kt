package com.taraang.app.ui.course

import com.taraang.app.core.AppError
import com.taraang.app.core.AppResult
import com.taraang.app.core.LatLng
import com.taraang.app.core.userMessage
import com.taraang.app.domain.model.LevelProgress
import com.taraang.app.domain.model.SignalMode
import com.taraang.app.domain.signal.NaviStart
import com.taraang.app.domain.signal.NavigationLauncher
import com.taraang.app.ui.MainDispatcherRule
import com.taraang.app.ui.ScriptedDriveController
import com.taraang.app.ui.ScriptedDriveRepository
import com.taraang.app.ui.TestContainer
import com.taraang.app.ui.navi
import com.taraang.app.ui.naviOk
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

    private fun vm(navi: NavigationLauncher = navi { _, _, _ -> naviOk() }) =
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
        val vm = vm(navi { _, waypoints, _ -> sent = waypoints; naviOk() })
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
    fun `navigation that never answers still starts the drive and tells the driver to check the navi`() = runTest {
        val vm = vm(navi { _, _, _ -> AppResult.Err(AppError.Navigation(AppError.Navigation.NO_RESPONSE, "timeout")) })
        vm.startWithNavigation()
        assertEquals(listOf(Triple(courseId, SignalMode.LIVE, null)), controller.starts)
        val notice = vm.events.first() as CourseDetailEvent.Notice
        assertEquals(AppError.Navigation(AppError.Navigation.NO_RESPONSE, "").userMessage(), notice.message)
        assertTrue(notice.message != AppError.Navigation("NO_NAVI", "").userMessage())
        assertEquals(CourseDetailEvent.DriveStarted, vm.events.first())
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun `foreground service is held before handing off to the navi, and released if no drive starts`() = runTest {
        var callsAtNavi: List<String>? = null
        val vm = vm(navi { _, _, _ -> callsAtNavi = controller.keepAliveCalls.toList(); naviOk() })
        vm.startWithNavigation()
        // 내비가 앞에 오기 전(앱이 아직 앞에 있을 때) 서비스를 켠다.
        assertEquals(listOf("prepare"), callsAtNavi)
        assertEquals(listOf("prepare", "cancel"), controller.keepAliveCalls) // 시작 성공 뒤 cancel은 컨트롤러에서 아무 일도 안 한다

        val failing = ScriptedDriveController()
        val vm2 = CourseDetailViewModel(
            TestContainer(driveController = failing, navigationLauncher = navi { _, _, _ -> AppResult.Err(AppError.Navigation("NO_NAVI", "x")) }),
            courseId, main.dispatcher,
        )
        vm2.startWithNavigation()
        assertEquals(listOf("prepare", "cancel"), failing.keepAliveCalls)
        assertTrue(failing.starts.isEmpty())
    }

    @Test
    fun `start far from the course origin is announced but still starts`() = runTest {
        val here = LatLng(PreviewData.course.origin.lat + 0.05, PreviewData.course.origin.lon) // 약 5.6km 북쪽
        val vm = vm(navi { _, _, _ -> naviOk(here) })
        vm.startWithNavigation()
        val far = vm.events.first() as CourseDetailEvent.FarFromStart
        assertTrue(far.distanceM in 5_000.0..6_000.0)
        assertEquals(CourseDetailEvent.DriveStarted, vm.events.first())
        assertEquals(1, controller.starts.size)

        // 가까우면 알리지 않는다.
        val near = vm(navi { _, _, _ -> naviOk(PreviewData.course.origin) })
        near.startWithNavigation()
        assertEquals(CourseDetailEvent.DriveStarted, near.events.first())
    }

    @Test
    fun `locked level course can be browsed and replayed but not driven live`() = runTest {
        val drive = ScriptedDriveRepository(progress = { LevelProgress(1, 0, 3) }) // 코스는 2단계
        var naviCalls = 0
        val vm = CourseDetailViewModel(
            TestContainer(driveController = controller, driveRepository = drive, navigationLauncher = navi { _, _, _ -> naviCalls++; naviOk() }),
            courseId, main.dispatcher,
        )
        assertTrue(vm.state.value.locked)
        vm.startWithNavigation()
        assertEquals(0, naviCalls)
        assertTrue(controller.starts.isEmpty())
        vm.startReplay()
        assertEquals(SignalMode.REPLAY, controller.starts.single().second)

        val ready = ScriptedDriveRepository(progress = { LevelProgress(1, 3, 3) }) // 올라갈 준비가 되면 다음 단계는 열린다
        assertFalse(CourseDetailViewModel(TestContainer(driveRepository = ready), courseId, main.dispatcher).state.value.locked)
    }

    @Test
    fun `second tap while starting is ignored`() {
        val gate = CompletableDeferred<AppResult<NaviStart>>()
        val vm = vm(navi { _, _, _ -> gate.await() })
        vm.startWithNavigation()
        assertEquals(StartAction.NAVI, vm.state.value.starting)
        vm.startWithNavigation()
        vm.startReplay()
        gate.complete(naviOk())
        assertEquals(1, controller.starts.size)
    }

    @Test
    fun `replay with one asset starts, several assets ask, none says missing`() {
        val vm = vm()
        vm.startReplay()
        assertEquals(SignalMode.REPLAY, controller.starts.single().second)
        assertEquals(listOf(courseId), controller.replayQueries)

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
