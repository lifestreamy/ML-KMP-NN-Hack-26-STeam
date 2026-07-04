package com.superteam.app.domain

import com.superteam.app.error.NetworkError
import com.superteam.app.error.Result
import com.superteam.app.models.AnalysisStage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface AnalysisRepository {
    val taskIds: StateFlow<List<String>>
    suspend fun uploadImages(files: Map<String, ByteArray>): Result<List<String>, NetworkError>
    fun getAnalysisStream(taskId: String): Flow<AnalysisStage>
    suspend fun cancelTasks(taskIds: List<String>): Result<Unit, NetworkError>
}
