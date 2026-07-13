package com.steply.app.ui.screens.remote

import com.steply.app.remote.RemoteCameraFrameAck

/** Owns exactly one screen-scoped streamer and never closes a newer replacement from a stale callback. */
internal class CameraStreamerOwner<T : AutoCloseable> {
    private var owned: T? = null

    fun replace(next: T) {
        if (owned === next) return
        val previous = owned
        owned = next
        previous?.close()
    }

    fun owns(candidate: T): Boolean = owned === candidate

    fun closeIfOwned(candidate: T): Boolean {
        if (owned !== candidate) return false
        owned = null
        candidate.close()
        return true
    }

    fun closeCurrent() {
        val current = owned ?: return
        owned = null
        current.close()
    }
}

/** Session-scoped one-shot gate for automatic streaming after camera permission is available. */
internal class CameraAutoStartGate {
    private var startConsumed = false
    private var stoppedByUser = false

    fun consumeIfAllowed(
        hasCameraPermission: Boolean,
        assessmentIncomplete: Boolean,
    ): Boolean {
        if (!hasCameraPermission || !assessmentIncomplete || startConsumed || stoppedByUser) return false
        startConsumed = true
        return true
    }

    fun markStarted() {
        startConsumed = true
    }

    fun markStoppedByUser() {
        stoppedByUser = true
        startConsumed = true
    }
}

/** Counts unique browser-preview acknowledgements, not local WebSocket enqueue success. */
internal class CameraPreviewAckTracker {
    private var lastMobileSequence: Long? = null
    private var lastServerSequence: Long? = null
    var confirmedFrameCount: Int = 0
        private set

    fun reset() {
        lastMobileSequence = null
        lastServerSequence = null
        confirmedFrameCount = 0
    }

    fun record(ack: RemoteCameraFrameAck): Boolean {
        if (ack.source != CAMERA_PREVIEW_ACK_SOURCE) return false
        val isNew = ack.mobileSequence?.let { sequence ->
            val previous = lastMobileSequence
            if (previous != null && sequence <= previous) false else {
                lastMobileSequence = sequence
                true
            }
        } ?: ack.sequence?.let { sequence ->
            val previous = lastServerSequence
            if (previous != null && sequence <= previous) false else {
                lastServerSequence = sequence
                true
            }
        } ?: false
        if (isNew) confirmedFrameCount += 1
        return isNew
    }

    private companion object {
        const val CAMERA_PREVIEW_ACK_SOURCE = "camera-preview"
    }
}
