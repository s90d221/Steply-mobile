package com.steply.app.ui.navigation

object Routes {
    const val ProfileList = "profiles"
    const val AddEditProfile = "profiles/edit?profileId={profileId}"
    const val RemoteConnect = "remote_connect"
    const val History = "history"
    const val RemoteCamera = "remote_camera"

    fun addProfile(): String = "profiles/edit"
    fun editProfile(profileId: String): String = "profiles/edit?profileId=$profileId"

    fun remoteCamera(): String = RemoteCamera
}
