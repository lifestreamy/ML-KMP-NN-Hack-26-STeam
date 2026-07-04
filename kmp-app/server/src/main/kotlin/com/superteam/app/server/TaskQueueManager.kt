package com.superteam.app.server

import com.superteam.app.models.AnalysisStage
import com.superteam.app.models.TaskUpdateEvent
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.File

data class EnqueuedTask(val taskId: String, val clientId: String, val fileName: String, val file: File)

class TaskQueueManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stages = mutableMapOf<String, MutableStateFlow<AnalysisStage>>()
    private val queue = Channel<EnqueuedTask>(Channel.UNLIMITED)
    private val clientTasks = mutableMapOf<String, MutableSet<String>>()
    private val _updates = MutableSharedFlow<TaskUpdateEvent>(extraBufferCapacity = 100)

    private val mlClient = HttpClient(CIO) {
        engine { requestTimeout = 0 }
    }

    init {
        scope.launch {
            var pos = 0
            for (task in queue) {
                pos++
                val sf = stages[task.taskId] ?: continue

                if (sf.value is AnalysisStage.Error) {
                    task.file.delete()
                    continue
                }

                println("[KTOR-QUEUE] Starting task ${task.taskId}. Sending to Python ML...")
                emit(task.taskId, "processing", pos)
                sf.value = AnalysisStage.Processing

                try {
                    val response = mlClient.post("http://localhost:8000/analyze") {
                        setBody(MultiPartFormDataContent(formData {
                            append("file", task.file.readBytes(), Headers.build {
                                append(HttpHeaders.ContentDisposition, "filename=\"${task.fileName}\"")
                            })
                        }))
                    }

                    if (response.status.isSuccess()) {
                        val resultJson = response.bodyAsText()
                        println("[KTOR-QUEUE] Task ${task.taskId} ML Inference Done.")

                        emit(task.taskId, "done", pos, message = resultJson)
                        sf.value = AnalysisStage.Done
                    } else {
                        throw Exception("ML Server returned ${response.status}")
                    }
                } catch (e: Exception) {
                    println("[KTOR-QUEUE] Task ${task.taskId} failed: ${e.message}")
                    emit(task.taskId, "error", pos, message = "ML Inference Failed: ${e.message}")
                    sf.value = AnalysisStage.Error("ML Inference Failed")
                } finally {
                    val deleted = task.file.delete()
                    println("[KTOR-QUEUE] Cleanup: Deleted temp file ${task.file.name} -> $deleted")
                }
            }
        }
    }

    private suspend fun emit(id: String, stage: String, pos: Int, message: String? = null) {
        _updates.emit(TaskUpdateEvent(taskId = id, stage = stage, positionInQueue = pos, message = message))
    }

    fun enqueueTask(clientId: String, taskId: String, fileName: String, file: File) {
        stages[taskId] = MutableStateFlow(AnalysisStage.Queued(position = 0))
        clientTasks.getOrPut(clientId) { mutableSetOf() }.add(taskId)

        emitQueuedUpdate(taskId)
        queue.trySend(EnqueuedTask(taskId, clientId, fileName, file))
    }

    private fun emitQueuedUpdate(taskId: String) {
        scope.launch {
            _updates.emit(TaskUpdateEvent(taskId = taskId, stage = "queued", positionInQueue = 0))
        }
    }

    fun observeUpdates(): SharedFlow<TaskUpdateEvent> = _updates.asSharedFlow()

    fun getClientTaskIds(clientId: String): Set<String> = clientTasks[clientId]?.toSet() ?: emptySet()

    fun cancelTasks(taskIds: List<String>) {
        taskIds.forEach {
            stages[it]?.value = AnalysisStage.Error("Cancelled by user")
            stages.remove(it)
        }
        scope.launch {
            taskIds.forEach {
                _updates.emit(TaskUpdateEvent(taskId = it, stage = "error", message = "Cancelled by user"))
            }
        }
    }
}