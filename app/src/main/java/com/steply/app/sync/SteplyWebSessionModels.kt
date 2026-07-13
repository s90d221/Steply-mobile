package com.steply.app.sync

import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Collections

/**
 * Payload encoded in the QR code shown by Steply-Web.
 *
 * Supported QR JSON:
 * {
 *   "type": "steply-web-session",
 *   "version": 3,
 *   "connectionSessionId": "...",
 *   "sessionId": "...",
 *   "assessmentSessionSchemaVersion": "assessment_session.v2",
 *   "serverUrl": "https://192.168.0.12:3000",
 *   "serverUrls": ["https://192.168.0.12:3000", "https://192.168.0.12:5173"],
 *   "expiresAt": "ISO_8601_UTC_EXPIRY",
 *   "expiresAtEpochMs": 1234567890000,
 *   "pairingToken": "one-time-random-token",
 *   "tlsCertSha256": "optional-lowercase-hex-der-leaf-cert-sha256"
 * }
 */
data class SteplyWebSessionPayload(
    val sessionId: String,
    val serverUrl: String,
    val candidateServerUrls: List<String> = listOf(serverUrl),
    val expiresAtEpochMs: Long = Long.MAX_VALUE,
    val pairingToken: String = "",
    val tlsCertSha256: String? = null,
) {
    val apiBaseUrl: String = serverUrl.trimEnd('/')
    val webSocketUrl: String = "${apiBaseUrl.toWebSocketBaseUrl()}/ws" +
        "?sessionId=${sessionId.urlEncode()}&role=mobile"

    fun withServerUrl(url: String): SteplyWebSessionPayload {
        val normalized = normalizeUrl(url)
        return copy(
            serverUrl = normalized,
            candidateServerUrls = listOf(normalized) + candidateServerUrls.filter { normalizeUrl(it) != normalized },
        )
    }

    fun isExpired(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        return expiresAtEpochMs <= nowEpochMs
    }

    companion object {
        fun normalizeUrl(value: String): String = value.trim().trimEnd('/')

        fun isSecureApiUrl(value: String): Boolean {
            return runCatching {
                val uri = URI(normalizeUrl(value))
                uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
            }.getOrDefault(false)
        }

        fun normalizeTlsCertSha256(value: String): String? {
            val normalized = value
                .trim()
                .lowercase()
                .replace(":", "")
                .replace("-", "")
            return normalized.takeIf { it.matches(TLS_CERT_SHA256_REGEX) }
        }
    }
}

object SteplyWebSessionLink {
    fun parse(
        rawValue: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): SteplyWebSessionPayload? {
        val trimmed = rawValue.trim()
        if (trimmed.isBlank()) return null

        return parseJson(trimmed, nowEpochMs)
    }

    fun markConsumed(payload: SteplyWebSessionPayload) {
        SteplyPairingTokenReplayCache.markConsumed(payload.pairingToken)
    }

    internal fun clearConsumedTokensForTests() {
        SteplyPairingTokenReplayCache.clear()
    }

    private fun parseJson(
        rawValue: String,
        nowEpochMs: Long,
    ): SteplyWebSessionPayload? {
        return runCatching {
            val json = JSONObject(rawValue)
            val keys = json.keys().asSequence().toSet()
            if (keys !in STRICT_QR_KEY_SETS) return@runCatching null
            if (json.getString("type") != "steply-web-session") return@runCatching null
            if (json.getInt("version") != 3) return@runCatching null
            if (json.getString("assessmentSessionSchemaVersion") != "assessment_session.v2") return@runCatching null

            val connectionSessionId = json.getString("connectionSessionId").trim()
            val sessionId = json.getString("sessionId").trim()
            if (connectionSessionId != sessionId) return@runCatching null
            val serverUrl = SteplyWebSessionPayload.normalizeUrl(json.getString("serverUrl"))
            val expiresAtEpochMs = parseExpiry(json) ?: return@runCatching null
            val pairingToken = json.getString("pairingToken").trim()
            val tlsCertSha256 = if (json.has("tlsCertSha256")) {
                parseTlsCertSha256(json.getString("tlsCertSha256"))
            } else {
                null
            }

            val serverUrls = json.getJSONArray("serverUrls")
            if (serverUrls.length() == 0) return@runCatching null
            val candidates = buildList {
                for (index in 0 until serverUrls.length()) {
                    val value = SteplyWebSessionPayload.normalizeUrl(serverUrls.getString(index))
                    if (!SteplyWebSessionPayload.isSecureApiUrl(value)) return@runCatching null
                    add(value)
                }
            }
            if (serverUrl !in candidates) return@runCatching null
            buildPayload(
                sessionId = sessionId,
                serverUrl = serverUrl,
                candidateServerUrls = candidates,
                expiresAtEpochMs = expiresAtEpochMs,
                pairingToken = pairingToken,
                tlsCertSha256 = tlsCertSha256,
                nowEpochMs = nowEpochMs,
            )
        }.getOrNull()
    }

    private fun buildPayload(
        sessionId: String,
        serverUrl: String,
        candidateServerUrls: List<String>,
        expiresAtEpochMs: Long,
        pairingToken: String,
        tlsCertSha256: String?,
        nowEpochMs: Long,
    ): SteplyWebSessionPayload? {
        val normalizedServerUrl = SteplyWebSessionPayload.normalizeUrl(serverUrl)
        if (sessionId.isBlank()) return null
        if (!SteplyWebSessionPayload.isSecureApiUrl(normalizedServerUrl)) return null
        if (expiresAtEpochMs <= nowEpochMs) return null
        if (!isValidPairingToken(pairingToken)) return null
        if (SteplyPairingTokenReplayCache.isConsumed(pairingToken)) return null

        val secureCandidates = candidateServerUrls
            .map { SteplyWebSessionPayload.normalizeUrl(it) }
            .filter { SteplyWebSessionPayload.isSecureApiUrl(it) }
            .distinct()

        return SteplyWebSessionPayload(
            sessionId = sessionId,
            serverUrl = normalizedServerUrl,
            candidateServerUrls = secureCandidates.ifEmpty { listOf(normalizedServerUrl) },
            expiresAtEpochMs = expiresAtEpochMs,
            pairingToken = pairingToken,
            tlsCertSha256 = tlsCertSha256,
        )
    }

    private fun parseExpiry(json: JSONObject): Long? {
        val epoch = json.getLong("expiresAtEpochMs").takeIf { it > 0L } ?: return null
        val parsed = runCatching { Instant.parse(json.getString("expiresAt")).toEpochMilli() }.getOrNull()
            ?: return null
        return epoch.takeIf { it == parsed }
    }

    private fun parseTlsCertSha256(rawValue: String): String? {
        if (rawValue.isBlank()) return null
        return SteplyWebSessionPayload.normalizeTlsCertSha256(rawValue)
            ?: error("Invalid tlsCertSha256")
    }

    private fun isValidPairingToken(token: String): Boolean {
        return token.length >= MIN_PAIRING_TOKEN_LENGTH && token.none { it.isWhitespace() }
    }
}

private object SteplyPairingTokenReplayCache {
    private val consumedTokenDigests = Collections.synchronizedSet(mutableSetOf<String>())

    fun isConsumed(token: String): Boolean {
        if (token.isBlank()) return false
        return token.sha256Hex() in consumedTokenDigests
    }

    fun markConsumed(token: String) {
        if (token.isNotBlank()) consumedTokenDigests += token.sha256Hex()
    }

    fun clear() {
        consumedTokenDigests.clear()
    }
}

private fun String.toWebSocketBaseUrl(): String {
    return when {
        startsWith("https://", ignoreCase = true) -> "wss://${substringAfter("://")}"
        startsWith("http://", ignoreCase = true) -> "wss://${substringAfter("://")}"
        else -> this
    }
}

private fun String.urlEncode(): String {
    return URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")
}

private fun String.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private const val MIN_PAIRING_TOKEN_LENGTH = 16
private val TLS_CERT_SHA256_REGEX = Regex("^[0-9a-f]{64}$")
private val STRICT_QR_REQUIRED_KEYS = setOf(
    "type",
    "version",
    "connectionSessionId",
    "sessionId",
    "assessmentSessionSchemaVersion",
    "serverUrl",
    "serverUrls",
    "expiresAt",
    "expiresAtEpochMs",
    "pairingToken",
)
private val STRICT_QR_KEY_SETS = setOf(
    STRICT_QR_REQUIRED_KEYS,
    STRICT_QR_REQUIRED_KEYS + "tlsCertSha256",
)
