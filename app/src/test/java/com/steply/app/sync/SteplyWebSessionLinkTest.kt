package com.steply.app.sync

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class SteplyWebSessionLinkTest {
    @After
    fun resetReplayCache() {
        SteplyWebSessionLink.clearConsumedTokensForTests()
    }

    @Test
    fun `secure qr payload builds wss websocket url`() {
        val payload = SteplyWebSessionLink.parse(validPayload(), nowEpochMs = NOW)

        assertNotNull(payload)
        requireNotNull(payload)
        assertEquals("https://192.168.0.12:3000", payload.apiBaseUrl)
        assertEquals("wss://192.168.0.12:3000/ws?sessionId=session-123&role=mobile", payload.webSocketUrl)
        assertEquals(TEST_CERT_SHA256, payload.tlsCertSha256)
    }

    @Test
    fun `cleartext server url is rejected`() {
        val payload = SteplyWebSessionLink.parse(
            validPayload(serverUrl = "http://192.168.0.12:3000"),
            nowEpochMs = NOW,
        )

        assertNull(payload)
    }

    @Test
    fun `expired qr payload is rejected`() {
        val payload = SteplyWebSessionLink.parse(
            validPayload(expiresAtEpochMs = NOW - 1_000L),
            nowEpochMs = NOW,
        )

        assertNull(payload)
    }

    @Test
    fun `missing one time pairing token is rejected`() {
        val payload = SteplyWebSessionLink.parse(
            validPayload(pairingToken = ""),
            nowEpochMs = NOW,
        )

        assertNull(payload)
    }

    @Test
    fun `invalid tls certificate pin is rejected`() {
        val payload = SteplyWebSessionLink.parse(
            validPayload(tlsCertSha256 = "not-a-sha256"),
            nowEpochMs = NOW,
        )

        assertNull(payload)
    }

    @Test
    fun `consumed pairing token is rejected`() {
        val payload = SteplyWebSessionLink.parse(validPayload(), nowEpochMs = NOW)
        requireNotNull(payload)

        SteplyWebSessionLink.markConsumed(payload)

        assertNull(SteplyWebSessionLink.parse(validPayload(), nowEpochMs = NOW))
    }

    private fun validPayload(
        serverUrl: String = "https://192.168.0.12:3000",
        expiresAtEpochMs: Long = NOW + 60_000L,
        pairingToken: String = "0123456789abcdef",
        tlsCertSha256: String = TEST_CERT_SHA256,
    ): String {
        return "steply://web-session" +
            "?sessionId=${"session-123".urlEncode()}" +
            "&serverUrl=${serverUrl.urlEncode()}" +
            "&expiresAtEpochMs=$expiresAtEpochMs" +
            "&pairingToken=${pairingToken.urlEncode()}" +
            "&tlsCertSha256=${tlsCertSha256.urlEncode()}"
    }

    private fun String.urlEncode(): String {
        return URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }

    private companion object {
        const val NOW = 1_783_420_800_000L
        const val TEST_CERT_SHA256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
