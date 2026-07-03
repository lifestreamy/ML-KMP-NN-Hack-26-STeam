package com.superteam.app.server

import com.superteam.app.models.AnalysisStage
import com.superteam.app.models.TaskUpdateEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class TaskQueueManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stages = mutableMapOf<String, MutableStateFlow<AnalysisStage>>()
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val clientTasks = mutableMapOf<String, MutableSet<String>>()
    private val filenames = mutableMapOf<String, String>()
    private val updates = Channel<TaskUpdateEvent>(Channel.BUFFERED)

    init {
        scope.launch {
            var position = 0
            for (taskId in queue) {
                position++
                val stateFlow = stages[taskId] ?: continue
                emit(taskId, "processing", position)
                stateFlow.value = AnalysisStage.Processing
                delay(3000.milliseconds)

                if (stages[taskId]?.value is AnalysisStage.Error) continue
                emit(taskId, "segmentation", position)
                stateFlow.value = AnalysisStage.Segmentation
                delay(3000.milliseconds)

                if (stages[taskId]?.value is AnalysisStage.Error) continue
                emit(taskId, "done", position)
                stateFlow.value = AnalysisStage.Done
            }
        }
    }

    private suspend fun emit(taskId: String, stage: String, position: Int) {
        updates.send(
            TaskUpdateEvent(
                taskId = taskId,
                stage = stage,
                positionInQueue = position
            )
        )
    }

    fun enqueueTask(clientId: String, taskId: String, fileName: String) {
        stages[taskId] = MutableStateFlow(AnalysisStage.Queued(position = 0))
        clientTasks.getOrPut(clientId) { mutableSetOf() }.add(taskId)
        filenames[taskId] = fileName
        emitQueuedUpdate(taskId)
        queue.trySend(taskId)
    }

    private fun emitQueuedUpdate(taskId: String) {
        scope.launch {
            updates.send(
                TaskUpdateEvent(
                    taskId = taskId,
                    stage = "queued",
                    positionInQueue = 0
                )
            )
        }
    }

    fun observeUpdates(): kotlinx.coroutines.channels.ReceiveChannel<TaskUpdateEvent> = updates

    fun getClientTaskIds(clientId: String): Set<String> =
        clientTasks[clientId]?.toSet() ?: emptySet()

    fun cancelTasks(taskIds: List<String>) {
        taskIds.forEach { taskId ->
            stages[taskId]?.value = AnalysisStage.Error("Cancelled by user")
            stages.remove(taskId)
        }
        scope.launch {
            taskIds.forEach { taskId ->
                updates.send(
                    TaskUpdateEvent(
                        taskId = taskId,
                        stage = "error",
                        message = "Cancelled by user"
                    )
                )
            }
        }
    }
}