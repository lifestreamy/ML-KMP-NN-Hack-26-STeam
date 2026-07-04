package com.superteam.app.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TaskUpdateEvent(
    @SerialName("task_id") val taskId: String,
    val stage: String,
    @SerialName("position_in_queue") val positionInQueue: Int? = null,
    val message: String? = null
)
