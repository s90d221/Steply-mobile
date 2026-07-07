package com.steply.app.ui.navigation

import android.net.Uri
import com.steply.app.sync.SteplyWebSessionPayload

object Routes {
    const val ProfileList = "profiles"
    const val AddEditProfile = "profiles/edit?profileId={profileId}"
    const val RemoteConnect = "remote_connect"
    const val History = "history"
    const val RemoteCamera = "remote_camera/{sessionId}/{serverUrl}" +
        "?pairingToken={pairingToken}&expiresAtEpochMs={expiresAtEpochMs}&tlsCertSha256={tlsCertSha256}"

    fun addProfile(): String = "profiles/edit"
    fun editProfile(profileId: String): String = "profiles/edit?profileId=$profileId"

    fun remoteCamera(session: SteplyWebSessionPayload): String {
        return "remote_camera/${Uri.encode(session.sessionId)}/${Uri.encode(session.serverUrl)}" +
            "?pairingToken=${Uri.encode(session.pairingToken)}" +
            "&expiresAtEpochMs=${session.expiresAtEpochMs}" +
            "&tlsCertSha256=${Uri.encode(session.tlsCertSha256.orEmpty())}"
    }
}
