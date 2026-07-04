package com.superteam.app.data

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NetworkHealthRepository(
    private val client: HttpClient,
    private val baseUrl: String = "http://localhost:8080"
                             ) {
    private val _isKtorConnected = MutableStateFlow(false)
    val isKtorConnected: StateFlow<Boolean> = _isKtorConnected.asStateFlow()

    private val _isPythonConnected = MutableStateFlow(false)
    val isPythonConnected: StateFlow<Boolean> = _isPythonConnected.asStateFlow()

    init {
        CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                try {
                    val response = client.get("$baseUrl/health")
                    if (response.status.isSuccess()) {
                        _isKtorConnected.value = true
                        val body = response.bodyAsText()
                        // Парсим фейковый ответ из Ktor
                        _isPythonConnected.value = body.contains("\"python\":\"ok\"")
                    } else {
                        _isKtorConnected.value = false
                        _isPythonConnected.value = false
                    }
                } catch (e: Throwable) {
                    _isKtorConnected.value = false
                    _isPythonConnected.value = false
                }
                delay(3000)
            }
        }
    }
}