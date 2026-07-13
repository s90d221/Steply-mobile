package com.steply.app.sync

import com.steply.app.domain.model.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SteplyConnectRequestTest {
    @Test
    fun `REQ-S5-X01 connect request has no duplicate legacy profile fields`() {
        val assessment = assessmentFixture().session
        val profile = UserProfile(
            "profile-1", "Tester", 1950, "FEMALE", null,
            "private movement note", "private safety note", 1, 2, null,
        )
        val contract = SteplyDataContractBuilder.build(profile, emptyList(), 3)
        val request = buildConnectRequestBody(
            SteplyWebSessionPayload(
                sessionId = "connection-1",
                serverUrl = "https://127.0.0.1:3000",
                expiresAtEpochMs = Long.MAX_VALUE,
                pairingToken = "token",
            ),
            assessment,
            contract,
        )

        assertEquals(
            setOf("connectionSessionId", "sessionId", "pairingToken", "assessmentSession", "dataContract"),
            request.keys().asSequence().toSet(),
        )
        assertFalse(request.has("profile"))
        assertFalse(request.has("name"))
        assertFalse(request.has("title"))
        assertFalse(request.getJSONObject("dataContract").toString().contains("private movement note"))
        assertFalse(request.getJSONObject("dataContract").toString().contains("private safety note"))
    }
}
