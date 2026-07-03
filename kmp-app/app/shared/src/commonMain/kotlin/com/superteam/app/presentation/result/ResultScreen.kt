package com.superteam.app.presentation.result

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.superteam.app.models.AnalysisResult
import com.superteam.app.models.Defect
import com.superteam.app.models.PhaseInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ResultState(
    val result: AnalysisResult? = null,
    val isLoading: Boolean = true
)

class ResultViewModel(
    private val taskId: String
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(ResultState())
    val state = _state.asStateFlow()

    init {
        loadResult()
    }

    private fun loadResult() {
        scope.launch {
            delay(800)
            _state.update {
                it.copy(
                    isLoading = false,
                    result = AnalysisResult(
                        sampleId = taskId,
                        oreClass = "talcose",
                        talkcPct = 14.2,
                        phases = mapOf(
                            "ordinary_intergrowths" to PhaseInfo(24.1, "#00FF00"),
                            "fine_intergrowths" to PhaseInfo(61.7, "#FF0000")
                        ),
                        defects = listOf(
                            Defect("crack", 1200, listOf(120, 45, 180, 90))
                        )
                    )
                )
            }
        }
    }

    fun clear() {
        scope.cancel()
    }
}

@Composable
fun ResultRoot(
    taskId: String,
    onNavigateBack: () -> Unit
) {
    val viewModel = remember(taskId) { ResultViewModel(taskId) }
    DisposableEffect(taskId) {
        onDispose { viewModel.clear() }
    }
    val state by viewModel.state.collectAsState()

    ResultScreen(
        state = state,
        taskId = taskId,
        onBackClick = onNavigateBack
    )
}

@Composable
fun ResultScreen(
    state: ResultState,
    taskId: String,
    onBackClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Button(onClick = onBackClick) {
            Text("Back")
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (state.isLoading) {
            CircularProgressIndicator()
        }
        state.result?.let { result ->
            Text("Task: $taskId", style = MaterialTheme.typography.headlineSmall)
            Text("Ore Class: ${result.oreClass}")
            Text("Talk Pct: ${result.talkcPct}%")
            Spacer(modifier = Modifier.height(16.dp))
            Text("Phases:", style = MaterialTheme.typography.titleMedium)
            result.phases.forEach { (name, phase) ->
                Text("$name: ${phase.areaPct}%")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("Defects:", style = MaterialTheme.typography.titleMedium)
            result.defects.forEach { defect ->
                Text("${defect.type}: ${defect.areaPx}px")
            }
        }
    }
}
