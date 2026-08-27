package com.nexafbproxy.app

enum class AppMode(val wireValue: String) {
    FACEBOOK("facebook"),
    FACEBOOK_LITE("facebook_lite"),
    BOTH("both");

    companion object {
        fun fromWire(value: String?): AppMode =
            entries.firstOrNull { it.wireValue == value } ?: FACEBOOK
    }
}
