package com.steply.app.care.android

object ProfileWorkTag {
    fun forProfile(profileId: String): String {
        require(profileId.isNotBlank())
        return "steply-profile:$profileId"
    }
}
