package com.taraang.app.ui.drive

import com.taraang.app.domain.model.FeedbackMessage
import com.taraang.app.domain.model.FeedbackType
import com.taraang.app.domain.repository.LiveDriveState
import com.taraang.app.domain.signal.WarningType
import com.taraang.app.ui.MainDispatcherRule
import com.taraang.app.ui.ScriptedDriveController
import com.taraang.app.ui.TestContainer
import com.taraang.app.ui.preview.PreviewData
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DriveViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val controller = ScriptedDriveController(live = MutableStateFlow(LiveDriveState(session = PreviewData.session.copy(endedAtMs = null))))
    // 규칙(MainDispatcherRule)이 Main을 바꾼 뒤에 만든다.
    private val vm by lazy { DriveViewModel(TestContainer(driveController = controller)) }

    private fun emit(type: FeedbackType, text: String, at: Long, warnings: Set<WarningType>) {
        controller.live.value = controller.live.value.copy(lastFeedback = FeedbackMessage(type, text, at), newWarnings = warnings)
    }

    @Test
    fun `an active new warning owns the message slot until it turns off`() {
        vm.state.value
        emit(FeedbackType.NEW_WARNING, "타이어 공기압 경고가 켜졌어요.", 1, setOf(WarningType.TIRE_PRESSURE))
        assertEquals("타이어 공기압 경고가 켜졌어요.", vm.state.value.message)
        assertTrue(vm.state.value.messageIsWarning)

        // 경고가 켜진 동안 들어온 코칭은 음성으로만 나가고 화면은 경고를 유지한다.
        emit(FeedbackType.SKILL_PREVIEW, "곧 좌회전이에요.", 2, setOf(WarningType.TIRE_PRESSURE))
        assertEquals("타이어 공기압 경고가 켜졌어요.", vm.state.value.message)

        // 경고가 꺼지면 내린다.
        controller.live.value = controller.live.value.copy(newWarnings = emptySet())
        assertFalse(vm.state.value.messageIsWarning)
        assertEquals("곧 좌회전이에요.", vm.state.value.message)
    }

    @Test
    fun `a warning that is already off when spoken is not shown`() {
        vm.state.value
        emit(FeedbackType.NEW_WARNING, "엔진 경고가 켜졌어요.", 1, emptySet())
        assertFalse(vm.state.value.messageIsWarning)
    }
}
