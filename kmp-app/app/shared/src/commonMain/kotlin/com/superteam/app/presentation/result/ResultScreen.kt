package com.superteam.app.presentation.result

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.superteam.app.domain.AnalysisRepository
import com.superteam.app.models.AnalysisResult
import com.superteam.app.models.AnalysisStage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.compose.koinInject

data class ResultState(val result: AnalysisResult? = null, val isLoading: Boolean = true, val error: String? = null)

class ResultViewModel(private val taskId: String, private val repository: AnalysisRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(ResultState())
    val state = _state.asStateFlow()

    init {
        scope.launch {
            repository.getAnalysisStream(taskId).collect { stage ->
                when (stage) {
                    is AnalysisStage.Done -> {
                        _state.update { it.copy(isLoading = false, result = stage.result) }
                    }
                    is AnalysisStage.Error -> {
                        _state.update { it.copy(isLoading = false, error = stage.message) }
                    }
                    else -> {
                        _state.update { it.copy(isLoading = true) }
                    }
                }
            }
        }
    }

    fun clear() {
        scope.cancel()
    }
}

@Composable
fun ResultRoot(taskId: String, onNavigateBack: () -> Unit, repository: AnalysisRepository = koinInject()) {
    val vm = remember(taskId) { ResultViewModel(taskId, repository) }
    DisposableEffect(taskId) { onDispose { vm.clear() } }
    val state by vm.state.collectAsState()
    ResultScreen(state = state, taskId = taskId, onBackClick = onNavigateBack)
}

@Composable
fun ResultScreen(state: ResultState, taskId: String, onBackClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(onClick = onBackClick) { Text("Back") }
        Spacer(modifier = Modifier.height(16.dp))

        if (state.isLoading) {
            CircularProgressIndicator()
            Text("Waiting for ML results...")
        } else if (state.error != null) {
            Text("Error: ${state.error}", color = MaterialTheme.colorScheme.error)
        } else {
            state.result?.let { r ->
                Text("Task: $taskId", style = MaterialTheme.typography.headlineSmall)
                Text("Ore Class: ${r.oreClass}")
                Text("Talk Pct: ${r.talkcPct}%")
                Spacer(modifier = Modifier.height(16.dp))

                Text("Phases:", style = MaterialTheme.typography.titleMedium)
                r.phases.forEach { (n, p) -> Text("$n: ${p.areaPct}%") }

                Spacer(modifier = Modifier.height(16.dp))
                Text("Defects:", style = MaterialTheme.typography.titleMedium)
                r.defects.forEach { d -> Text("${d.type}: ${d.areaPx}px") }
            } ?: Text("Result parsing failed. Check logs.")
        }
    }
}