package com.superteam.app.data

import com.superteam.app.domain.AnalysisRepository
import com.superteam.app.error.NetworkError
import com.superteam.app.error.Result
import com.superteam.app.error.toNetworkError
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
    private val sseJson = Json { ignoreUnknownKeys = true }

    private val _taskIds = MutableStateFlow<List<String>>(emptyList())
    override val taskIds: StateFlow<List<String>> = _taskIds.asStateFlow()

    init {
        connectSse()
        monitorConnection()
    }

    // Слушаем статус подключения. Если Ktor упал, "убиваем" зависшие таски
    private fun monitorConnection() {
        scope.launch {
            healthRepo.isKtorConnected.collect { isConnected ->
                if (!isConnected) {
                    stages.forEach { (_, stateFlow) ->
                        val current = stateFlow.value
                        if (current !is AnalysisStage.Done && current !is AnalysisStage.Error) {
                            stateFlow.value = AnalysisStage.Error("Connection to Ktor server lost")
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
                    client.sse({
                        url("$baseUrl/api/tasks/stream")
                        header("X-Client-Id", clientId)
                    }) {
                        incoming.collect { event ->
                            val data = event.data ?: return@collect
                            if (data.isEmpty() || event.event == "ping") return@collect
                            val update = try {
                                sseJson.decodeFromString<TaskUpdateEvent>(data)
                            } catch (e: Throwable) { return@collect }

                            val stage = mapStage(update)
                            val stateFlow = stages.getOrPut(update.taskId) {
                                MutableStateFlow(AnalysisStage.Queued(0))
                            }
                            stateFlow.value = stage

                            if (update.taskId !in _taskIds.value) {
                                _taskIds.update { current -> current + update.taskId }
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
        "done" -> AnalysisStage.Done
        "error" -> AnalysisStage.Error(update.message ?: "Unknown error")
        else -> AnalysisStage.Error(update.message ?: "Unknown stage: ${update.stage}")
    }

    override suspend fun uploadImages(files: List<ByteArray>): Result<List<String>, NetworkError> {
        return try {
            val response = client.post("$baseUrl/api/tasks") {
                header("X-Client-Id", clientId)
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            files.forEachIndexed { index, bytes ->
                                append("files", bytes, Headers.build {
                                    append(HttpHeaders.ContentType, "image/tiff")
                                    append(HttpHeaders.ContentDisposition, "filename=\"slide_$index.tiff\"")
                                })
                            }
                        }
                                            )
                       )
            }

            // Если Ktor вернул ошибку 503 Service Unavailable (питон лежит)
            if (response.status == HttpStatusCode.ServiceUnavailable) {
                return Result.Error(NetworkError.Unknown("Python ML Server is down"))
            }

            val body = response.bodyAsText()
            val parsed = Json { ignoreUnknownKeys = true }.decodeFromString<UploadResponse>(body)
            val ids = parsed.tasks.map { it.taskId }

            ids.forEach { id ->
                stages[id] = MutableStateFlow(AnalysisStage.Queued(0))
                _taskIds.update { current -> current + id }
            }
            Result.Success(ids)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.Error(e.toNetworkError())
        }
    }

    override fun getAnalysisStream(taskId: String): Flow<AnalysisStage> {
        return stages.getOrPut(taskId) { MutableStateFlow(AnalysisStage.Error("Unknown task")) }
    }

    override suspend fun cancelTasks(taskIds: List<String>): Result<Unit, NetworkError> {
        return try {
            client.delete("$baseUrl/api/tasks") {
                header("X-Client-Id", clientId)
                contentType(ContentType.Application.Json)
                setBody(mapOf("task_ids" to taskIds))
            }
            taskIds.forEach { taskId ->
                stages[taskId]?.value = AnalysisStage.Error("Cancelled by user")
            }
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
private data class UploadTaskItem(
    @kotlinx.serialization.SerialName("task_id") val taskId: String,
    val filename: String
                                 )

@kotlinx.serialization.Serializable
private data class UploadResponse(val tasks: List<UploadTaskItem>)