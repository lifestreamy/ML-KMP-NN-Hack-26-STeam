package com.superteam.app.di

import com.superteam.app.data.FakeAnalysisRepository
import com.superteam.app.data.NetworkAnalysisRepository
import com.superteam.app.domain.AnalysisRepository
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.sse.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import org.koin.dsl.module

val fakeAppModule = module {
    single<AnalysisRepository> { FakeAnalysisRepository(Dispatchers.Default) }
}

val networkAppModule = module {
    single {
        HttpClient {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
            install(SSE) {
                showCommentEvents()
                showRetryEvents()
            }
        }
    }
    single<AnalysisRepository> { NetworkAnalysisRepository(get()) }
}