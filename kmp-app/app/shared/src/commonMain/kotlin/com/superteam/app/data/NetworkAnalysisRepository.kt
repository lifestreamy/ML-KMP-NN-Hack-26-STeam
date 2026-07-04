package com.superteam.app.data

import com.superteam.app.domain.AnalysisRepository
import com.superteam.app.error.NetworkError
import com.superteam.app.error.Result
import com.superteam.app.error.toNetworkError
import com.superteam.app.models.AnalysisResult
import com.superteam.app.models.AnalysisStage
import com.superteam.app.models.TaskUpdateEvent
import io.ktor.client.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

class NetworkAnalysisRepository(
    private val client: HttpClient,
    private val healthRepo: NetworkHealthRepository,
    private val baseUrl: String = "http://localhost:8080"
                               ) : AnalysisRepository {
    private val clientId = "ui-${Random.nextLong().toString(16)}"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stages = mutableMapOf<String, MutableStateFlow<AnalysisStage>>()

    private val networkJson = Json { ignoreUnknownKeys = true }

    private val _taskIds = MutableStateFlow<List<String>>(emptyList())
    override val taskIds: StateFlow<List<String>> = _taskIds.asStateFlow()

    init {
        connectSse()
        monitorConnection()
    }

    private fun monitorConnection() {
        scope.launch {
            healthRepo.isKtorConnected.collect { connected ->
                if (!connected) {
                    stages.forEach { (_, sf) ->
                        val c = sf.value
                        if (c !is AnalysisStage.Done && c !is AnalysisStage.Error) {
                            sf.value = AnalysisStage.Error("Connection to Ktor server lost")
                        }
                    }
                }
            }
        }
    }

    private fun connectSse() {
        scope.launch {
            while (isActive) {
                try {
                    client.sse({ url("$baseUrl/api/tasks/stream"); header("X-Client-Id", clientId) }) {
                        incoming.collect { event ->
                            val data = event.data ?: return@collect
                            if (data.isEmpty() || event.event == "ping") return@collect

                            try {
                                val update = networkJson.decodeFromString<TaskUpdateEvent>(data)
                                stages.getOrPut(update.taskId) { MutableStateFlow(AnalysisStage.Queued(0)) }.value = mapStage(update)
                                if (update.taskId !in _taskIds.value) _taskIds.update { current -> current + update.taskId }
                            } catch (e: Throwable) {
                                println("SSE Parse Error: ${e.message}. Raw data: $data")
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    delay(3000.milliseconds)
                }
            }
        }
    }

    private fun mapStage(update: TaskUpdateEvent): AnalysisStage = when (update.stage) {
        "queued" -> AnalysisStage.Queued(update.positionInQueue ?: 0)
        "processing" -> AnalysisStage.Processing
        "segmentation" -> AnalysisStage.Segmentation
        "done" -> {
            // Парсим JSON от Питона
            val parsedResult = update.message?.let { jsonString ->
                try {
                    networkJson.decodeFromString<AnalysisResult>(jsonString)
                } catch (e: Throwable) {
                    println("Failed to parse ML Result: ${e.message}")
                    null
                }
            }
            AnalysisStage.Done(parsedResult)
        }
        "error" -> AnalysisStage.Error(update.message ?: "Unknown error")
        else -> AnalysisStage.Error(update.message ?: "Unknown stage: ${update.stage}")
    }

    override suspend fun uploadImages(files: Map<String, ByteArray>): Result<List<String>, NetworkError> {
        return try {
            val response = client.post("$baseUrl/api/tasks") {
                header("X-Client-Id", clientId)
                setBody(MultiPartFormDataContent(formData {
                    files.forEach { (fileName, bytes) ->
                        append("files", bytes, Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                        })
                    }
                }))
            }
            if (response.status == HttpStatusCode.ServiceUnavailable) {
                return Result.Error(NetworkError.Unknown("Python ML Server is down"))
            }

            val body = response.bodyAsText()
            val ids = networkJson.decodeFromString<UploadResponse>(body).tasks.map { it.taskId }
            ids.forEach { taskId ->
                // Если SSE уже пришел и создал статус Processing, getOrPut его НЕ перезапишет
                stages.getOrPut(taskId) { MutableStateFlow(AnalysisStage.Queued(0)) }
                if (taskId !in _taskIds.value) {
                    _taskIds.update { c -> c + taskId }
                }
            }
            Result.Success(ids)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.Error(e.toNetworkError())
        }
    }

    override fun getAnalysisStream(taskId: String): Flow<AnalysisStage> = stages.getOrPut(taskId) { MutableStateFlow(AnalysisStage.Error("Unknown task")) }

    override suspend fun cancelTasks(taskIds: List<String>): Result<Unit, NetworkError> {
        return try {
            client.delete("$baseUrl/api/tasks") {
                header("X-Client-Id", clientId)
                contentType(ContentType.Application.Json)
                setBody(mapOf("task_ids" to taskIds))
            }
            taskIds.forEach { stages[it]?.value = AnalysisStage.Error("Cancelled by user") }
            _taskIds.update { current -> current - taskIds.toSet() }
            Result.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.Error(e.toNetworkError())
        }
    }
}

@kotlinx.serialization.Serializable
private data class UploadTaskItem(@kotlinx.serialization.SerialName("task_id") val taskId: String, val filename: String)

@kotlinx.serialization.Serializable
private data class UploadResponse(val tasks: List<UploadTaskItem>)