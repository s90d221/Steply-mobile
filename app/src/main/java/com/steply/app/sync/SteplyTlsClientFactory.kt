package com.steply.app.sync

import okhttp3.OkHttpClient
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object SteplyTlsClientFactory {
    fun build(
        baseClient: OkHttpClient,
        tlsCertSha256: String?,
    ): OkHttpClient {
        if (tlsCertSha256 == null) return baseClient
        val normalizedPin = requireNotNull(SteplyWebSessionPayload.normalizeTlsCertSha256(tlsCertSha256)) {
            "Invalid TLS certificate SHA-256 pin"
        }

        val trustManager = PinnedLeafCertificateTrustManager(normalizedPin)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }

        return baseClient.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier(PinnedLeafCertificateHostnameVerifier(normalizedPin))
            .build()
    }
}

private class PinnedLeafCertificateTrustManager(
    private val expectedLeafSha256: String,
) : X509TrustManager {
    private val platformTrustManager = platformTrustManager()

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) {
        platformTrustManager.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) {
        val leaf = chain?.firstOrNull()
            ?: throw CertificateException("Server certificate chain is empty")
        leaf.checkValidity()

        val actualLeafSha256 = leaf.encoded.sha256Hex()
        if (!actualLeafSha256.constantTimeEquals(expectedLeafSha256)) {
            throw CertificateException("Server certificate SHA-256 does not match QR TLS pin")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> {
        return platformTrustManager.acceptedIssuers
    }
}

private class PinnedLeafCertificateHostnameVerifier(
    private val expectedLeafSha256: String,
) : HostnameVerifier {
    override fun verify(hostname: String?, session: SSLSession?): Boolean {
        val leaf = runCatching { session?.peerCertificates?.firstOrNull() }
            .getOrNull() as? X509Certificate ?: return false

        return runCatching {
            leaf.checkValidity()
            leaf.encoded.sha256Hex().constantTimeEquals(expectedLeafSha256)
        }.getOrDefault(false)
    }
}

private fun platformTrustManager(): X509TrustManager {
    val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    trustManagerFactory.init(null as KeyStore?)
    return trustManagerFactory.trustManagers
        .filterIsInstance<X509TrustManager>()
        .single()
}

private fun ByteArray.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(this)
    return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun String.constantTimeEquals(other: String): Boolean {
    return MessageDigest.isEqual(
        toByteArray(StandardCharsets.UTF_8),
        other.toByteArray(StandardCharsets.UTF_8),
    )
}
