package com.superteam.app.error

sealed interface NetworkError : Error {
    val message: String

    data object NoInternet : NetworkError { override val message = "No internet connection" }
    data object Timeout : NetworkError { override val message = "Request timed out" }
    data class Http(val code: Int) : NetworkError { override val message = "Server error: $code" }
    data class Unknown(val causeMessage: String?) : NetworkError { override val message = causeMessage ?: "Unknown error" }
}

fun Throwable.toNetworkError(): NetworkError = when {
    this::class.simpleName == "JsError" -> NetworkError.NoInternet
    else -> NetworkError.Unknown(message)
}
