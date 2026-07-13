package com.steply.app.remote

import com.steply.app.ui.screens.remote.REMOTE_CAMERA_FRAME_INTERVAL_MS
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteCameraFrameContractTest {
    @Test
    fun `REQ-S6-4 camera cadence is nominal 30fps`() {
        assertEquals(33L, REMOTE_CAMERA_FRAME_INTERVAL_MS)
    }

    @Test
    fun `REQ-S6-4 frame metadata preserves capture order and timestamps`() {
        val metadata = JSONObject(buildCameraFrameMetadata(
            mobileSequence = 42L,
            capturedAtUptimeMs = 1_234L,
            sentAtEpochMs = 1_700_000_000_000L,
            byteLength = 48_321,
        ))

        assertEquals("camera-frame-meta", metadata.getString("type"))
        assertEquals(42L, metadata.getLong("mobileSequence"))
        assertEquals(1_234L, metadata.getLong("capturedAtUptimeMs"))
        assertEquals(1_700_000_000_000L, metadata.getLong("sentAtEpochMs"))
        assertEquals(48_321, metadata.getInt("byteLength"))
    }

    @Test
    fun `REQ-CAMERA-ACK parser preserves browser preview acknowledgement identity`() {
        val ack = parseRemoteCameraFrameAck(
            JSONObject()
                .put("type", "remote-camera-frame-ack")
                .put("sequence", 12L)
                .put("mobileSequence", 9L)
                .put("source", "camera-preview")
                .put("receivedAt", 1_700_000_000_000L)
                .put("analyzedAt", JSONObject.NULL),
        )

        requireNotNull(ack)
        assertEquals(12L, ack.sequence)
        assertEquals(9L, ack.mobileSequence)
        assertEquals("camera-preview", ack.source)
        assertEquals(1_700_000_000_000L, ack.receivedAt)
        assertNull(ack.analyzedAt)
    }

    @Test
    fun `REQ-CAMERA-ACK parser rejects unrelated or unidentifiable messages`() {
        assertNull(parseRemoteCameraFrameAck(JSONObject().put("type", "session")))
        assertNull(
            parseRemoteCameraFrameAck(
                JSONObject()
                    .put("type", "remote-camera-frame-ack")
                    .put("source", "camera-preview"),
            ),
        )
    }
}
