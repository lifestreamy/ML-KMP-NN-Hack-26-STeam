package com.superteam.app.models

import kotlinx.serialization.Serializable

@Serializable
data class TaskUpdateEvent(
    val taskId: String,
    val stage: String,
    val positionInQueue: Int? = null,
    val message: String? = null
)
