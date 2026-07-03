package com.superteam.app.error

import kotlinx.serialization.Serializable

@Serializable
enum class NetworkError {
    NETWORK_UNREACHABLE,
    SERVER_ERROR,
    BAD_REQUEST,
    UNKNOWN
}
