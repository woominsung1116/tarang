package com.taraang.app.ui.review

import com.taraang.app.core.AppError
import com.taraang.app.core.AppResult
import com.taraang.app.domain.model.Feeling
import com.taraang.app.domain.model.SegmentFeeling
import com.taraang.app.ui.MainDispatcherRule
import com.taraang.app.ui.ScriptedDriveRepository
import com.taraang.app.ui.TestContainer
import com.taraang.app.ui.preview.PreviewData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReviewViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val drive = ScriptedDriveRepository()
    private val sessionId = PreviewData.session.id

    private fun vm() = ReviewViewModel(TestContainer(driveRepository = drive), sessionId)

    @Test
    fun `loads summary, saved feelings and progress`() {
        val vm = vm()
        val s = vm.state.value
        assertFalse(s.loading)
        assertEquals(PreviewData.summary, s.summary)
        assertEquals(mapOf(1 to Feeling.TENSE), s.feelings)
        assertEquals(PreviewData.progress, s.progress)
    }

    @Test
    fun `summary failure shows an error and retry recovers`() {
        drive.summaryResult = AppResult.Err(AppError.Storage("db"))
        val vm = vm()
        assertNull(vm.state.value.summary)
        assertNotNull(vm.state.value.loadError)

        drive.summaryResult = AppResult.Ok(PreviewData.summary)
        vm.load()
        assertNull(vm.state.value.loadError)
        assertEquals(PreviewData.summary, vm.state.value.summary)
    }

    @Test
    fun `progress failure keeps the summary`() {
        drive.progress = { throw IllegalStateException("db") }
        val vm = vm()
        assertNull(vm.state.value.progress)
        assertEquals(PreviewData.summary, vm.state.value.summary)
    }

    @Test
    fun `feeling is saved as the whole list and repeating it is a no-op`() {
        val vm = vm()
        vm.setFeeling(3, Feeling.OK)
        vm.setFeeling(3, Feeling.OK)
        assertEquals(1, drive.saved.size)
        assertEquals(
            setOf(SegmentFeeling(sessionId, 1, Feeling.TENSE), SegmentFeeling(sessionId, 3, Feeling.OK)),
            drive.saved.single().toSet(),
        )
    }

    @Test
    fun `save failure keeps the choice on screen and shows a message`() {
        drive.saveResult = AppResult.Err(AppError.Storage("db"))
        val vm = vm()
        vm.setFeeling(0, Feeling.MISTAKE)
        assertEquals(Feeling.MISTAKE, vm.state.value.feelings[0])
        assertNotNull(vm.state.value.saveError)
        assertTrue(drive.saved.isNotEmpty())
    }
}
