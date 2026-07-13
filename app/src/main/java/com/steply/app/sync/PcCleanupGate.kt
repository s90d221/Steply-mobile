package com.steply.app.sync

/** Allows one successful cleanup while permitting a failed request to be retried. */
class PcCleanupGate {
    private enum class State { READY, IN_FLIGHT, SUCCEEDED }
    private var state = State.READY

    @Synchronized
    fun begin(): Boolean {
        if (state != State.READY) return false
        state = State.IN_FLIGHT
        return true
    }

    @Synchronized
    fun failed() {
        if (state == State.IN_FLIGHT) state = State.READY
    }

    @Synchronized
    fun succeeded() {
        check(state == State.IN_FLIGHT)
        state = State.SUCCEEDED
    }
}
