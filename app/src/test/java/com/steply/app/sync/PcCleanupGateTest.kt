package com.steply.app.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcCleanupGateTest {
    @Test
    fun `REQ-S5-CLEANUP-01 success is exactly once`() {
        val gate = PcCleanupGate()

        assertTrue(gate.begin())
        assertFalse(gate.begin())
        gate.succeeded()
        assertFalse(gate.begin())
    }

    @Test
    fun `REQ-S5-CLEANUP-02 failure remains on screen and permits one retry`() {
        val gate = PcCleanupGate()

        assertTrue(gate.begin())
        gate.failed()
        assertTrue(gate.begin())
        gate.succeeded()
        assertFalse(gate.begin())
    }
}
