package com.superteam.app.error

sealed interface Result<out D, out E> {
    data class Success<D>(val data: D) : Result<D, Nothing>
    data class Error<E>(val error: E) : Result<Nothing, E>
}
