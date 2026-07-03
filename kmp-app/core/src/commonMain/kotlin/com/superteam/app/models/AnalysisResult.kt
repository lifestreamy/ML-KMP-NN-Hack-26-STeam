package com.superteam.app.models

import kotlinx.serialization.Serializable

@Serializable
data class AnalysisResult(
    val sampleId: String,
    val oreClass: String,
    val talkcPct: Double,
    val phases: Map<String, PhaseInfo> = emptyMap(),
    val defects: List<Defect> = emptyList()
)

@Serializable
data class PhaseInfo(
    val areaPct: Double,
    val color: String
)

@Serializable
data class Defect(
    val type: String,
    val areaPx: Int,
    val bbox: List<Int>
)
