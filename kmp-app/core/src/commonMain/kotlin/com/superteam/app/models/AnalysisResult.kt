package com.superteam.app.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AnalysisResult(
    @SerialName("sample_id") val sampleId: String,
    @SerialName("ore_class") val oreClass: String,
    @SerialName("talkc_pct") val talkcPct: Double,
    val phases: Map<String, PhaseInfo> = emptyMap(),
    val defects: List<Defect> = emptyList()
)

@Serializable
data class PhaseInfo(
    @SerialName("area_pct") val areaPct: Double,
    val color: String
)

@Serializable
data class Defect(
    val type: String,
    @SerialName("area_px") val areaPx: Int,
    val bbox: List<Int>
)
