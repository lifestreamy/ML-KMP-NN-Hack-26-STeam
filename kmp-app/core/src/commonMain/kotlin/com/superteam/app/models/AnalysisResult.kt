package com.superteam.app.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AnalysisResult(
    @SerialName("sample") val sampleId: String,
    @SerialName("final_label") val oreClass: String,
    @SerialName("stage1_details") val stage1Details: Stage1Details,
    @SerialName("stage2_pred") val stage2Pred: String? = null,
    @SerialName("stage2_prob_trudnie") val stage2ProbTrudnie: Double? = null
                         )

@Serializable
data class Stage1Details(
    @SerialName("pct_sulfide") val pctSulfide: Double,
    @SerialName("pct_potential_talc") val pctPotentialTalc: Double,
    @SerialName("pct_background") val pctBackground: Double,
    @SerialName("pct_inclusions_in_talc") val pctInclusionsInTalc: Double,
    @SerialName("pct_final_zone") val pctFinalZone: Double,
    @SerialName("stage1_pred") val stage1Pred: String
                        )