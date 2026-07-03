package com.superteam.app.presentation.upload

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.superteam.app.domain.AnalysisRepository
import com.superteam.app.models.AnalysisStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import com.superteam.app.error.Result

data class UploadState(
    val selectedFileCount: Int = 0,
    val tasks: List<TaskUi> = emptyList(),
    val isAnalyzing: Boolean = false,
    val errorMessage: String? = null
) {
    val hasActiveTasks: Boolean
        get() = tasks.any { !it.isTerminal }
}

data class TaskUi(
    val taskId: String,
    val stage: String = "Ready"
) {
    val isTerminal: Boolean
        get() = stage == "Done" || stage.startsWith("Error")
}

sealed interface UploadAction {
    data object OnPickFiles : UploadAction
    data object OnAnalyze : UploadAction
    data object OnCancelAll : UploadAction
    data object OnDismissError : UploadAction
    data class OnCancelTask(val taskId: String) : UploadAction
    data class OnTaskClick(val taskId: String) : UploadAction
}

sealed interface UploadEvent {
    data class NavigateToResult(val taskId: String) : UploadEvent
}

class UploadViewModel(
    private val repository: AnalysisRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val subscriptions = mutableMapOf<String, Job>()

    private val _state = MutableStateFlow(UploadState())
    val state = _state.asStateFlow()

    private val _events = Channel<UploadEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        scope.launch {
            repository.taskIds.collect { ids ->
                val existingIds = _state.value.tasks.map { it.taskId }.toSet()
                val addedIds = ids.toSet() - existingIds
                val removedIds = existingIds - ids.toSet()

                if (addedIds.isNotEmpty() || removedIds.isNotEmpty()) {
                    _state.update { s ->
                        s.copy(
                            tasks = s.tasks
                                .filter { it.taskId !in removedIds }
                                + addedIds.map { TaskUi(taskId = it) }
                        )
                    }
                }

                ids.forEach { taskId ->
                    if (taskId !in subscriptions) {
                        subscriptions[taskId] = scope.launch {
                            repository.getAnalysisStream(taskId).collect { stage ->
                                val label = mapStageToString(stage)
                                _state.update { s ->
                                    s.copy(tasks = s.tasks.map {
                                        if (it.taskId == taskId) it.copy(stage = label) else it
                                    })
                                }
                            }
                        }
                    }
                }

                subscriptions.keys.filter { it !in ids }.forEach { removedId ->
                    subscriptions[removedId]?.cancel()
                    subscriptions.remove(removedId)
                }
            }
        }
    }

    private fun mapStageToString(stage: AnalysisStage): String = when (stage) {
        is AnalysisStage.Queued -> "Queued #${stage.position}"
        is AnalysisStage.Processing -> "Processing"
        is AnalysisStage.Segmentation -> "Segmentation"
        is AnalysisStage.Done -> "Done"
        is AnalysisStage.Error -> "Error: ${stage.message}"
    }

    fun onAction(action: UploadAction) {
        when (action) {
            UploadAction.OnPickFiles -> pickFiles()
            UploadAction.OnAnalyze -> analyze()
            UploadAction.OnCancelAll -> cancelAll()
            UploadAction.OnDismissError -> dismissError()
            is UploadAction.OnCancelTask -> cancelTask(action.taskId)
            is UploadAction.OnTaskClick -> onTaskClick(action.taskId)
        }
    }

    private fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private fun pickFiles() {
        _state.update { it.copy(selectedFileCount = 50) }
    }

    private fun analyze() {
        scope.launch {
            _state.update { it.copy(isAnalyzing = true, errorMessage = null) }
            val fakeBytes = List(_state.value.selectedFileCount) { index ->
                "slide_$index.tiff".encodeToByteArray()
            }
            when (val result = repository.uploadImages(fakeBytes)) {
                is Result.Success -> {}
                is Result.Error -> {
                    _state.update { it.copy(errorMessage = "Network unreachable. Check server.") }
                }
            }
            _state.update { it.copy(isAnalyzing = false) }
        }
    }

    private fun cancelAll() {
        scope.launch {
            val activeIds = _state.value.tasks
                .filter { !it.isTerminal }
                .map { it.taskId }
            if (activeIds.isNotEmpty()) {
                when (val result = repository.cancelTasks(activeIds)) {
                    is Result.Success -> {}
                    is Result.Error -> {
                        _state.update { it.copy(errorMessage = "Failed to cancel. Network issue.") }
                    }
                }
            }
        }
    }

    private fun cancelTask(taskId: String) {
        scope.launch {
            when (val result = repository.cancelTasks(listOf(taskId))) {
                is Result.Success -> {}
                is Result.Error -> {
                    _state.update { it.copy(errorMessage = "Failed to cancel. Network issue.") }
                }
            }
        }
    }

    private fun onTaskClick(taskId: String) {
        scope.launch {
            _events.send(UploadEvent.NavigateToResult(taskId))
        }
    }

    fun clear() {
        subscriptions.values.forEach { it.cancel() }
        scope.cancel()
    }
}

@Composable
fun UploadRoot(
    onNavigateToResult: (String) -> Unit,
    repository: AnalysisRepository = koinInject()
) {
    val viewModel = remember { UploadViewModel(repository) }
    DisposableEffect(Unit) {
        onDispose { viewModel.clear() }
    }

    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is UploadEvent.NavigateToResult -> onNavigateToResult(event.taskId)
            }
        }
    }

    UploadScreen(
        state = state,
        onAction = viewModel::onAction
    )
}

@Composable
fun UploadScreen(
    state: UploadState,
    onAction: (UploadAction) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Upload", style = MaterialTheme.typography.headlineMedium)

        state.errorMessage?.let { msg ->
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(msg, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { onAction(UploadAction.OnDismissError) }) {
                        Text("Dismiss")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { onAction(UploadAction.OnPickFiles) }) {
            Text("Select Images")
        }
        Text("Selected: ${state.selectedFileCount} file(s)")
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { onAction(UploadAction.OnAnalyze) },
            enabled = state.selectedFileCount > 0 && !state.isAnalyzing
        ) {
            Text(if (state.isAnalyzing) "Uploading..." else "Analyze")
        }
        if (state.hasActiveTasks) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { onAction(UploadAction.OnCancelAll) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Cancel All")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (state.tasks.isNotEmpty()) {
            Text("Tasks:", style = MaterialTheme.typography.titleMedium)
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(state.tasks, key = { it.taskId }) { task ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        onClick = { onAction(UploadAction.OnTaskClick(task.taskId)) }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(task.taskId, style = MaterialTheme.typography.bodyMedium)
                                Text(task.stage, style = MaterialTheme.typography.bodySmall)
                            }
                            if (!task.isTerminal) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = { onAction(UploadAction.OnCancelTask(task.taskId)) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Text("Cancel")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}