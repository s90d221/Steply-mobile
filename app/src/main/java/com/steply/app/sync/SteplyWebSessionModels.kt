package com.steply.app.sync

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
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
 *   "sessionId": "...",
 *   "serverUrl": "https://192.168.0.12:3000",
 *   "serverUrls": ["https://192.168.0.12:3000", "https://192.168.0.12:5173"],
 *   "expiresAt": "ISO_8601_UTC_EXPIRY",
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

        parseJson(trimmed, nowEpochMs)?.let { return it }
        parseUri(trimmed, nowEpochMs)?.let { return it }
        return null
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
            val type = json.optString("type")
            if (type != "steply-web-session") return@runCatching null

            val sessionId = json.optString("sessionId").trim()
            val serverUrl = SteplyWebSessionPayload.normalizeUrl(json.optString("serverUrl"))
            val expiresAtEpochMs = parseExpiry(json) ?: return@runCatching null
            val pairingToken = json.optString("pairingToken").trim()
            val tlsCertSha256 = parseTlsCertSha256(json.optString("tlsCertSha256"))

            val candidates = mutableListOf<String>()
            val serverUrls = json.optJSONArray("serverUrls")
            if (serverUrls != null) {
                for (i in 0 until serverUrls.length()) {
                    val value = SteplyWebSessionPayload.normalizeUrl(serverUrls.optString(i))
                    if (value.isNotBlank()) candidates += value
                }
            }
            if (serverUrl.isNotBlank()) candidates += serverUrl
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

    private fun parseUri(
        rawValue: String,
        nowEpochMs: Long,
    ): SteplyWebSessionPayload? {
        return runCatching {
            val uri = URI(rawValue)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "steply" || uri.host != "web-session") return@runCatching null

            val params = uri.queryParameters()
            val sessionId = params["sessionId"]?.trim().orEmpty()
            val serverUrl = params["serverUrl"]?.let { SteplyWebSessionPayload.normalizeUrl(it) }.orEmpty()
            val expiresAtEpochMs = parseExpiry(params) ?: return@runCatching null
            val pairingToken = params["pairingToken"]?.trim().orEmpty()
            val tlsCertSha256 = parseTlsCertSha256(params["tlsCertSha256"].orEmpty())

            buildPayload(
                sessionId = sessionId,
                serverUrl = serverUrl,
                candidateServerUrls = listOf(serverUrl),
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
        if (json.has("expiresAtEpochMs")) {
            val value = json.optLong("expiresAtEpochMs", Long.MIN_VALUE)
            if (value > 0) return value
        }

        val expiresAt = json.optString("expiresAt").trim()
        if (expiresAt.isBlank()) return null
        return runCatching { Instant.parse(expiresAt).toEpochMilli() }.getOrNull()
    }

    private fun parseExpiry(params: Map<String, String>): Long? {
        params["expiresAtEpochMs"]?.toLongOrNull()?.let { if (it > 0) return it }
        val expiresAt = params["expiresAt"]?.trim().orEmpty()
        if (expiresAt.isBlank()) return null
        return runCatching { Instant.parse(expiresAt).toEpochMilli() }.getOrNull()
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

private fun URI.queryParameters(): Map<String, String> {
    val query = rawQuery ?: return emptyMap()
    return query
        .split("&")
        .filter { it.isNotBlank() }
        .mapNotNull { pair ->
            val key = pair.substringBefore("=")
            val value = pair.substringAfter("=", "")
            val decodedKey = key.urlDecode()
            if (decodedKey.isBlank()) null else decodedKey to value.urlDecode()
        }
        .toMap()
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

private fun String.urlDecode(): String {
    return URLDecoder.decode(this, StandardCharsets.UTF_8.name())
}

private fun String.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private const val MIN_PAIRING_TOKEN_LENGTH = 16
private val TLS_CERT_SHA256_REGEX = Regex("^[0-9a-f]{64}$")
