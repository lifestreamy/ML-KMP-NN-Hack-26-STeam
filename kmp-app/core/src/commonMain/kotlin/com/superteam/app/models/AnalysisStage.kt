package com.superteam.app.models

import kotlinx.serialization.Serializable

@Serializable
sealed interface AnalysisStage {
    @Serializable data class Queued(val position: Int) : AnalysisStage
    @Serializable data object Processing : AnalysisStage
    @Serializable data object Segmentation : AnalysisStage
    @Serializable data object Done : AnalysisStage
    @Serializable data class Error(val message: String) : AnalysisStage
}
