package com.steply.app.ui.screens.remote

import com.steply.app.remote.RemoteCameraFrameAck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraStreamingLifecycleTest {
    @Test
    fun `REQ-CAMERA-LIFECYCLE recomposition cannot close the currently owned streamer`() {
        val owner = CameraStreamerOwner<FakeStreamer>()
        val current = FakeStreamer()
        owner.replace(current)

        owner.replace(current)
        assertFalse(owner.closeIfOwned(FakeStreamer()))

        assertTrue(owner.owns(current))
        assertEquals(0, current.closeCount)

        owner.closeCurrent()
        owner.closeCurrent()
        assertEquals(1, current.closeCount)
    }

    @Test
    fun `REQ-CAMERA-LIFECYCLE replacement closes only the previous streamer`() {
        val owner = CameraStreamerOwner<FakeStreamer>()
        val previous = FakeStreamer()
        val current = FakeStreamer()

        owner.replace(previous)
        owner.replace(current)

        assertEquals(1, previous.closeCount)
        assertEquals(0, current.closeCount)
        assertFalse(owner.closeIfOwned(previous))
        assertEquals(0, current.closeCount)
    }

    @Test
    fun `REQ-CAMERA-AUTOSTART permission starts once and user stop suppresses automatic restart`() {
        val gate = CameraAutoStartGate()

        assertFalse(gate.consumeIfAllowed(hasCameraPermission = false, assessmentIncomplete = true))
        assertTrue(gate.consumeIfAllowed(hasCameraPermission = true, assessmentIncomplete = true))
        assertFalse(gate.consumeIfAllowed(hasCameraPermission = true, assessmentIncomplete = true))

        val stoppedBeforePermission = CameraAutoStartGate()
        stoppedBeforePermission.markStoppedByUser()
        assertFalse(stoppedBeforePermission.consumeIfAllowed(hasCameraPermission = true, assessmentIncomplete = true))

        val manuallyStarted = CameraAutoStartGate()
        manuallyStarted.markStarted()
        assertFalse(manuallyStarted.consumeIfAllowed(hasCameraPermission = true, assessmentIncomplete = true))

        val completedAssessment = CameraAutoStartGate()
        assertFalse(completedAssessment.consumeIfAllowed(hasCameraPermission = true, assessmentIncomplete = false))
    }

    @Test
    fun `REQ-CAMERA-ACK only unique camera preview acknowledgements count as PC confirmed`() {
        val tracker = CameraPreviewAckTracker()
        val first = ack(mobileSequence = 1L, source = "camera-preview")

        assertTrue(tracker.record(first))
        assertFalse(tracker.record(first))
        assertFalse(tracker.record(ack(mobileSequence = 2L, source = "pose-frame")))
        assertTrue(tracker.record(ack(mobileSequence = 2L, source = "camera-preview")))
        assertEquals(2, tracker.confirmedFrameCount)

        tracker.reset()
        assertEquals(0, tracker.confirmedFrameCount)
        assertTrue(tracker.record(first))
    }

    private fun ack(mobileSequence: Long, source: String) = RemoteCameraFrameAck(
        sequence = mobileSequence,
        mobileSequence = mobileSequence,
        source = source,
        receivedAt = 1_000L,
        analyzedAt = 1_001L,
    )

    private class FakeStreamer : AutoCloseable {
        var closeCount = 0
            private set

        override fun close() {
            closeCount += 1
        }
    }
}
