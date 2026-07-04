package com.superteam.app.presentation.upload

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.superteam.app.domain.AnalysisRepository
import com.superteam.app.error.onFailure
import com.superteam.app.error.onSuccess
import com.superteam.app.models.AnalysisStage
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.koin.compose.koinInject
import kotlin.collections.map

data class UploadState(
    val selectedFiles: List<PlatformFile> = emptyList(), val tasks: List<TaskUi> = emptyList(),
    val isAnalyzing: Boolean = false, val errorMessage: String? = null
                      ) {
    val hasActiveTasks: Boolean get() = tasks.any { !it.isTerminal }
}

data class TaskUi(val taskId: String, val stage: String = "Ready") {
    val isTerminal: Boolean get() = stage == "Done" || stage.startsWith("Error")
}

sealed interface UploadAction {
    data class OnFilesSelected(val files: List<PlatformFile>) : UploadAction
    data object OnAnalyze : UploadAction
    data object OnCancelAll : UploadAction
    data object OnDismissError : UploadAction
    data class OnCancelTask(val taskId: String) : UploadAction
    data class OnTaskClick(val taskId: String) : UploadAction
}

sealed interface UploadEvent {
    data class NavigateToResult(val taskId: String) : UploadEvent
}

class UploadViewModel(private val repository: AnalysisRepository) {
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
                        s.copy(tasks = s.tasks.filter { it.taskId !in removedIds } + addedIds.map { TaskUi(it) })
                    }
                }
                ids.forEach { taskId ->
                    if (taskId !in subscriptions) {
                        subscriptions[taskId] = scope.launch {
                            repository.getAnalysisStream(taskId).collect { stage ->
                                val label = mapStageToString(stage)
                                _state.update { s ->
                                    s.copy(
                                        tasks = s.tasks.map { if (it.taskId == taskId) it.copy(stage = label) else it })
                                }
                            }
                        }
                    }
                }
                subscriptions.keys.filter { it !in ids }
                    .forEach { subscriptions[it]?.cancel(); subscriptions.remove(it) }
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
            is UploadAction.OnFilesSelected -> _state.update { it.copy(selectedFiles = action.files) }
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

    private fun analyze() {
        scope.launch {
            _state.update { it.copy(isAnalyzing = true, errorMessage = null) }
            try {
                val files = _state.value.selectedFiles
                val byteArrays = files.map { it.readBytes() }

                repository.uploadImages(byteArrays).onSuccess {
                    _state.update { s -> s.copy(selectedFiles = emptyList()) }
                }.onFailure { error ->
                    _state.update { s -> s.copy(errorMessage = error.message) }
                }
            } catch (e: Exception) {
                _state.update { s -> s.copy(errorMessage = e.message) }
            } finally {
                _state.update { s -> s.copy(isAnalyzing = false) }
            }
        }
    }

    private fun cancelAll() {
        scope.launch {
            val ids = _state.value.tasks.filter { !it.isTerminal }.map { it.taskId }
            if (ids.isNotEmpty()) repository.cancelTasks(ids).onSuccess { }
                .onFailure { error -> _state.update { it.copy(errorMessage = error.message) } }
        }
    }

    private fun cancelTask(taskId: String) {
        scope.launch {
            repository.cancelTasks(listOf(taskId)).onSuccess { }
                .onFailure { error -> _state.update { it.copy(errorMessage = error.message) } }
        }
    }

    private fun onTaskClick(taskId: String) {
        scope.launch { _events.send(UploadEvent.NavigateToResult(taskId)) }
    }

    fun clear() {
        subscriptions.values.forEach { it.cancel() }
        scope.cancel()
    }
}

@Composable
fun UploadRoot(onNavigateToResult: (String) -> Unit, repository: AnalysisRepository = koinInject()) {
    val vm = remember { UploadViewModel(repository) }
    DisposableEffect(Unit) { onDispose { vm.clear() } }
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) {
        vm.events.collect {
            when (it) {
                is UploadEvent.NavigateToResult -> onNavigateToResult(it.taskId)
            }
        }
    }
    UploadScreen(state = state, onAction = vm::onAction)
}

@Composable
fun UploadScreen(state: UploadState, onAction: (UploadAction) -> Unit) {
    // Лаунчер выбора файлов (FileKit 0.14.2)
    val launcher = rememberFilePickerLauncher(type = FileKitType.Image, mode = FileKitMode.Multiple()) { files ->
        if (!files.isNullOrEmpty()) {
            onAction(UploadAction.OnFilesSelected(files))
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Upload", style = MaterialTheme.typography.headlineMedium)

        state.errorMessage?.let { msg ->
            Spacer(modifier = Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onAction(UploadAction.OnDismissError) }) { Text("Dismiss") }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { launcher.launch() }) { Text("Select Images") }
        Text("Selected: ${state.selectedFiles.size} file(s)")

        if (state.selectedFiles.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(modifier = Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.Center) {
                items(state.selectedFiles) { file ->
                    // Рендерим локальный файл через Coil без утечек памяти
                    AsyncImage(model = file, contentDescription = null, modifier = Modifier.size(100.dp).padding(4.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = { onAction(UploadAction.OnAnalyze) },
            enabled = state.selectedFiles.isNotEmpty() && !state.isAnalyzing) {
            Text(if (state.isAnalyzing) "Uploading..." else "Analyze")
        }

        if (state.hasActiveTasks) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { onAction(UploadAction.OnCancelAll) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text("Cancel All")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        if (state.tasks.isNotEmpty()) {
            Text("Tasks:", style = MaterialTheme.typography.titleMedium)
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(state.tasks, key = { it.taskId }) { task ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        onClick = { onAction(UploadAction.OnTaskClick(task.taskId)) }) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(task.taskId)
                                Text(task.stage, style = MaterialTheme.typography.bodySmall)
                            }
                            if (!task.isTerminal) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(onClick = { onAction(UploadAction.OnCancelTask(task.taskId)) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error)) { Text("Cancel") }
                            }
                        }
                    }
                }
            }
        }
    }
}
