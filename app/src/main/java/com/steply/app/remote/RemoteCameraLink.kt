package com.steply.app.remote

import android.net.Uri

object RemoteCameraLink {
    private const val DefaultPort = 3000

    fun parseHost(rawValue: String): String? {
        val trimmed = rawValue.trim()
        if (trimmed.isBlank()) return null

        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()

        if (scheme == "steply" && uri.host == "remote-camera") {
            val host = uri.getQueryParameter("host")?.trim()
            if (!host.isNullOrBlank()) return normalizeHost(host)

            val ws = uri.getQueryParameter("ws")?.trim()
            if (!ws.isNullOrBlank()) return parseHost(ws)
        }

        if (scheme == "ws" || scheme == "wss" || scheme == "http" || scheme == "https") {
            return uri.host?.let(::normalizeHost)
        }

        return normalizeHost(trimmed)
    }

    fun buildWebSocketUrl(host: String): String {
        val trimmed = host.trim()
        return when {
            trimmed.startsWith("wss://") -> trimmed
            trimmed.startsWith("ws://") -> trimmed.replaceFirst("ws://", "wss://")
            trimmed.contains(":") -> "wss://$trimmed/ws"
            else -> "wss://$trimmed:$DefaultPort/ws"
        }
    }

    private fun normalizeHost(host: String): String? {
        val trimmed = host.trim()
            .removePrefix("[")
            .removeSuffix("]")
            .trimEnd('/')

        if (trimmed.isBlank()) return null
        if (trimmed.contains("/")) return null
        return trimmed.substringBefore(":")
    }
}
