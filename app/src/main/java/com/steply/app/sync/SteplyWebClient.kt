package com.steply.app.sync

import com.steply.app.domain.model.AssessmentSession
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

interface CleanupCallback {
    fun onSuccess(body: String, cleanedSession: SteplyWebSessionPayload)
    fun onFailure(message: String)

    object Noop : CleanupCallback {
        override fun onSuccess(body: String, cleanedSession: SteplyWebSessionPayload) = Unit
        override fun onFailure(message: String) = Unit
    }
}

interface PcSessionCleanupRequester {
    fun requestSessionCleanup(
        session: SteplyWebSessionPayload,
        reason: String = "mobile-session-ended",
        callback: CleanupCallback = CleanupCallback.Noop,
    )
}

class SteplyWebClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
) : PcSessionCleanupRequester {
    fun connectProfile(
        session: SteplyWebSessionPayload,
        assessmentSession: AssessmentSession? = null,
        dataContract: SteplyDataContract,
        callback: ResultCallback = ResultCallback.Noop,
    ) {
        val candidates = session.candidateServerUrls.ifEmpty { listOf(session.serverUrl) }.distinct()
        tryConnectProfile(
            session = session,
            assessmentSession = assessmentSession,
            dataContract = dataContract,
            candidates = candidates,
            index = 0,
            errors = mutableListOf(),
            callback = callback,
        )
    }

    override fun requestSessionCleanup(
        session: SteplyWebSessionPayload,
        reason: String,
        callback: CleanupCallback,
    ) {
        val candidates = session.candidateServerUrls.ifEmpty { listOf(session.serverUrl) }.distinct()
        tryRequestSessionCleanup(
            session = session,
            reason = reason,
            candidates = candidates,
            index = 0,
            errors = mutableListOf(),
            callback = callback,
        )
    }

    private fun tryRequestSessionCleanup(
        session: SteplyWebSessionPayload,
        reason: String,
        candidates: List<String>,
        index: Int,
        errors: MutableList<String>,
        callback: CleanupCallback,
    ) {
        if (index >= candidates.size) {
            callback.onFailure(errors.joinToString(separator = "\n"))
            return
        }

        val baseUrl = SteplyWebSessionPayload.normalizeUrl(candidates[index])
        val activeSession = session.withServerUrl(baseUrl)
        val body = JSONObject()
            .put("connectionSessionId", activeSession.sessionId)
            .put("sessionId", activeSession.sessionId)
            .put("pairingToken", activeSession.pairingToken)
            .put("reason", reason)

        postJson(
            session = activeSession,
            url = "${activeSession.apiBaseUrl}/api/session/${activeSession.sessionId}/cleanup",
            json = body,
            onSuccess = { responseBody -> callback.onSuccess(responseBody, activeSession) },
            onFailure = { message ->
                errors += "$baseUrl -> $message"
                tryRequestSessionCleanup(
                    session = session,
                    reason = reason,
                    candidates = candidates,
                    index = index + 1,
                    errors = errors,
                    callback = callback,
                )
            },
        )
    }

    private fun tryConnectProfile(
        session: SteplyWebSessionPayload,
        assessmentSession: AssessmentSession?,
        dataContract: SteplyDataContract,
        candidates: List<String>,
        index: Int,
        errors: MutableList<String>,
        callback: ResultCallback,
    ) {
        if (index >= candidates.size) {
            callback.onFailure(errors.joinToString(separator = "\n"))
            return
        }

        val baseUrl = SteplyWebSessionPayload.normalizeUrl(candidates[index])
        val activeSession = session.withServerUrl(baseUrl)
        val body = buildConnectRequestBody(activeSession, assessmentSession, dataContract)

        postJson(
            session = activeSession,
            url = "${activeSession.apiBaseUrl}/api/session/${activeSession.sessionId}/connect",
            json = body,
            onSuccess = { responseBody -> callback.onSuccess(responseBody, activeSession) },
            onFailure = { message ->
                errors += "$baseUrl -> $message"
                tryConnectProfile(
                    session = session,
                    assessmentSession = assessmentSession,
                    dataContract = dataContract,
                    candidates = candidates,
                    index = index + 1,
                    errors = errors,
                    callback = callback,
                )
            },
        )
    }

    private fun postJson(
        session: SteplyWebSessionPayload,
        url: String,
        json: JSONObject,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val requestBuilder = Request.Builder()
            .url(url)
            .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
        if (session.pairingToken.isNotBlank()) {
            requestBuilder.header("X-Steply-Pairing-Token", session.pairingToken)
        }
        val request = requestBuilder.build()

        SteplyTlsClientFactory.build(client, session.tlsCertSha256).newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onFailure(e.message ?: "Network request failed")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        onSuccess(it.body?.string().orEmpty())
                    } else {
                        onFailure("HTTP ${it.code}: ${it.body?.string().orEmpty()}")
                    }
                }
            }
        })
    }

    interface ResultCallback {
        fun onSuccess(body: String, connectedSession: SteplyWebSessionPayload)
        fun onFailure(message: String)

        object Noop : ResultCallback {
            override fun onSuccess(body: String, connectedSession: SteplyWebSessionPayload) = Unit
            override fun onFailure(message: String) = Unit
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

internal fun buildConnectRequestBody(
    session: SteplyWebSessionPayload,
    assessmentSession: AssessmentSession?,
    dataContract: SteplyDataContract,
): JSONObject = JSONObject()
    .put("connectionSessionId", session.sessionId)
    .put("sessionId", session.sessionId)
    .put("pairingToken", session.pairingToken)
    .put("dataContract", SteplyDataContractJsonCodec.encodeObject(dataContract))
    .also { body ->
        assessmentSession?.let { body.put("assessmentSession", AssessmentSessionJsonCodec.sessionToJson(it)) }
    }
