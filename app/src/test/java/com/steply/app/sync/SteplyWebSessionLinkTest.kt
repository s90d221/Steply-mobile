package com.steply.app.sync

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SteplyWebSessionLinkTest {
    @After
    fun resetReplayCache() {
        SteplyWebSessionLink.clearConsumedTokensForTests()
    }

    @Test
    fun `strict JSON qr payload builds wss websocket url`() {
        val payload = SteplyWebSessionLink.parse(validJsonPayload(serverUrl = "https://192.168.0.12:3000"), nowEpochMs = NOW)

        assertNotNull(payload)
        requireNotNull(payload)
        assertEquals("https://192.168.0.12:3000", payload.apiBaseUrl)
        assertEquals("wss://192.168.0.12:3000/ws?sessionId=session-123&role=mobile", payload.webSocketUrl)
        assertEquals(TEST_CERT_SHA256, payload.tlsCertSha256)
    }

    @Test
    fun `web json qr payload is accepted`() {
        val payload = SteplyWebSessionLink.parse(validJsonPayload(), nowEpochMs = NOW)

        assertNotNull(payload)
        requireNotNull(payload)
        assertEquals("session-123", payload.sessionId)
        assertEquals("https://10.189.36.119:3000", payload.serverUrl)
        assertEquals(
            listOf(
                "https://10.189.36.119:3000",
                "https://192.168.0.99:3000",
                "https://192.168.0.12:3000",
            ),
            payload.candidateServerUrls,
        )
        assertEquals("wss://10.189.36.119:3000/ws?sessionId=session-123&role=mobile", payload.webSocketUrl)
        assertEquals(TEST_CERT_SHA256, payload.tlsCertSha256)
    }

    @Test
    fun `cleartext server url is rejected`() {
        val payload = SteplyWebSessionLink.parse(
            validJsonPayload(serverUrl = "http://192.168.0.12:3000"),
            nowEpochMs = NOW,
        )

        assertNull(payload)
    }

    @Test
    fun `expired qr payload is rejected`() {
        val payload = SteplyWebSessionLink.parse(
            validJsonPayload(expiresAtEpochMs = NOW - 1_000L),
            nowEpochMs = NOW,
        )

        assertNull(payload)
    }

    @Test
    fun `missing one time pairing token is rejected`() {
        val payload = SteplyWebSessionLink.parse(
            validJsonPayload(pairingToken = ""),
            nowEpochMs = NOW,
        )

        assertNull(payload)
    }

    @Test
    fun `invalid tls certificate pin is rejected`() {
        val payload = SteplyWebSessionLink.parse(
            validJsonPayload(tlsCertSha256 = "not-a-sha256"),
            nowEpochMs = NOW,
        )

        assertNull(payload)
    }

    @Test
    fun `consumed pairing token is rejected`() {
        val payload = SteplyWebSessionLink.parse(validJsonPayload(), nowEpochMs = NOW)
        requireNotNull(payload)

        SteplyWebSessionLink.markConsumed(payload)

        assertNull(SteplyWebSessionLink.parse(validJsonPayload(), nowEpochMs = NOW))
    }

    @Test
    fun `URL query qr and unknown JSON fields are rejected`() {
        assertNull(
            SteplyWebSessionLink.parse(
                "steply://web-session?sessionId=session-123&serverUrl=https%3A%2F%2F192.168.0.12%3A3000",
                nowEpochMs = NOW,
            ),
        )
        assertNull(
            SteplyWebSessionLink.parse(
                validJsonPayload().replace("\"type\":", "\"unexpected\": true, \"type\":"),
                nowEpochMs = NOW,
            ),
        )
    }

    private fun validJsonPayload(
        serverUrl: String = "https://10.189.36.119:3000",
        expiresAtEpochMs: Long = NOW + 60_000L,
        pairingToken: String = "0123456789abcdef",
        tlsCertSha256: String = TEST_CERT_SHA256,
    ): String {
        return """
            {
              "type": "steply-web-session",
              "version": 3,
              "connectionSessionId": "session-123",
              "sessionId": "session-123",
              "assessmentSessionSchemaVersion": "assessment_session.v2",
              "serverUrl": "$serverUrl",
              "serverUrls": [
                "$serverUrl",
                "https://192.168.0.99:3000",
                "https://192.168.0.12:3000"
              ],
              "expiresAt": "${java.time.Instant.ofEpochMilli(expiresAtEpochMs)}",
              "expiresAtEpochMs": $expiresAtEpochMs,
              "pairingToken": "$pairingToken",
              "tlsCertSha256": "$tlsCertSha256"
            }
        """.trimIndent()
    }

    private companion object {
        const val NOW = 1_783_420_800_000L
        const val TEST_CERT_SHA256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
