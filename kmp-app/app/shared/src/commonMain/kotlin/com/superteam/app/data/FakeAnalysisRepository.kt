package com.superteam.app.data

import com.superteam.app.domain.AnalysisRepository
import com.superteam.app.error.NetworkError
import com.superteam.app.error.Result
import com.superteam.app.models.AnalysisStage
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

class FakeAnalysisRepository(
    dispatcher: CoroutineDispatcher = Dispatchers.Default
                            ) : AnalysisRepository {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val stages = mutableMapOf<String, MutableStateFlow<AnalysisStage>>()
    private val queue = Channel<String>(Channel.UNLIMITED)

    private val _taskIds = MutableStateFlow<List<String>>(emptyList())
    override val taskIds: StateFlow<List<String>> = _taskIds.asStateFlow()

    init {
        scope.launch {
            for (taskId in queue) {
                val stateFlow = stages[taskId] ?: continue
                stateFlow.value = AnalysisStage.Processing
                delay(1800.milliseconds)
                if (stages[taskId]?.value is AnalysisStage.Error) continue
                stateFlow.value = AnalysisStage.Segmentation
                delay(2500.milliseconds)
                if (stages[taskId]?.value is AnalysisStage.Error) continue
                stateFlow.value = AnalysisStage.Done()
            }
        }
    }

    override suspend fun uploadImages(files: Map<String, ByteArray>): Result<List<String>, NetworkError> {
        delay(300.milliseconds)

        val ids = List(files.size) {
            "task-${Random.nextInt(0x10000, 0xFFFFF).toString(16)}"
        }

        ids.forEachIndexed { index, id ->
            val initialStage = AnalysisStage.Queued(position = index + 1)
            stages[id] = MutableStateFlow(initialStage)
            queue.send(id)
        }
        _taskIds.update { current -> current + ids }
        return Result.Success(ids)
    }

    override fun getAnalysisStream(taskId: String): Flow<AnalysisStage> {
        return stages.getOrPut(taskId) { MutableStateFlow(AnalysisStage.Queued(position = 0)) }
    }

    override suspend fun cancelTasks(taskIds: List<String>): Result<Unit, NetworkError> {
        taskIds.forEach { taskId ->
            stages[taskId]?.value = AnalysisStage.Error("Cancelled by user")
            stages.remove(taskId)
        }
        _taskIds.update { current -> current - taskIds.toSet() }
        return Result.Success(Unit)
    }
}