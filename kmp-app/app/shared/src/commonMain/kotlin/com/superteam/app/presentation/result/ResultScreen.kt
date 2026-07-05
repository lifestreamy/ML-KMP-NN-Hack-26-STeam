package com.superteam.app.presentation.result

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.superteam.app.domain.AnalysisRepository
import com.superteam.app.models.AnalysisResult
import com.superteam.app.models.AnalysisStage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.compose.koinInject
import kotlin.math.round

private const val ML_BASE_URL = "http://localhost:8000"

data class ResultState(val result: AnalysisResult? = null, val isLoading: Boolean = true, val error: String? = null)

class ResultViewModel(private val taskId: String, private val repository: AnalysisRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(ResultState())
    val state = _state.asStateFlow()

    init {
        scope.launch {
            repository.getAnalysisStream(taskId).collect { stage ->
                when (stage) {
                    is AnalysisStage.Done -> _state.update { it.copy(isLoading = false, result = stage.result) }
                    is AnalysisStage.Error -> _state.update { it.copy(isLoading = false, error = stage.message) }
                    else -> _state.update { it.copy(isLoading = true) }
                }
            }
        }
    }
    fun clear() { scope.cancel() }
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
        Button(onClick = onBackClick) { Text("Назад") }
        Spacer(modifier = Modifier.height(16.dp))

        if (state.isLoading) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(8.dp))
            Text("Ожидание результатов ML...")
        } else if (state.error != null) {
            Text("Ошибка: ${state.error}", color = MaterialTheme.colorScheme.error)
        } else {
            state.result?.let { r ->
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        Text("Задача: $taskId", style = MaterialTheme.typography.headlineSmall)
                        Text("Итоговый класс: ${getRuLabel(r.oreClass)}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)

                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Этап 1 (Сегментация и тальк)", style = MaterialTheme.typography.titleMedium)
                        Text("Сульфиды: ${r.stage1Details.pctSulfide.format2()}%")
                        Text("Потенциальный тальк: ${r.stage1Details.pctPotentialTalc.format2()}%")
                        Text("Фон (порода): ${r.stage1Details.pctBackground.format2()}%")
                        Text("Включения в тальке: ${r.stage1Details.pctInclusionsInTalc.format2()}%")
                        Text("Финальная зона талька: ${r.stage1Details.pctFinalZone.format2()}%")
                        Text("Вердикт 1 этапа: ${getRuLabel(r.stage1Details.stage1Pred)}")

                        val s2Pred = r.stage2Pred
                        val s2Prob = r.stage2ProbTrudnie

                        if (s2Pred != null && s2Prob != null) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Этап 2 (Нейросеть CNN)", style = MaterialTheme.typography.titleMedium)
                            Text("Предсказание: ${getRuLabel(s2Pred)}")
                            Text("Уверенность (Трудные): ${s2Prob.format3()}")
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                        Text("Визуализация:", style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    item {
                        Text("Зоны (Сульфиды / Тальк)")
                        AsyncImage(
                            model = "$ML_BASE_URL/outputs/${r.sampleId}_zones.png",
                            contentDescription = "Маска зон",
                            modifier = Modifier.fillMaxWidth().height(250.dp).padding(vertical = 8.dp)
                                  )
                    }

                    item {
                        Text("Карта плотности")
                        AsyncImage(
                            model = "$ML_BASE_URL/outputs/${r.sampleId}_density.png",
                            contentDescription = "Карта плотности",
                            modifier = Modifier.fillMaxWidth().height(250.dp).padding(vertical = 8.dp)
                                  )
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            } ?: Text("Ошибка обработки данных. Проверьте логи.")
        }
    }
}

private fun getRuLabel(label: String): String = when(label) {
    "ryadovie" -> "Рядовая"
    "otalkovanie" -> "Оталькованная"
    "not_otalkovanie" -> "Не оталькованная"
    "trudnie" -> "Труднообогатимая"
    else -> label
}

private fun Double.format2(): String = (round(this * 100.0) / 100.0).toString()
private fun Double.format3(): String = (round(this * 1000.0) / 1000.0).toString()