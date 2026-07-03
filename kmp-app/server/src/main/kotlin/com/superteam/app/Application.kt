package com.superteam.app

import com.superteam.app.models.AnalysisResult
import com.superteam.app.models.Defect
import com.superteam.app.models.PhaseInfo
import com.superteam.app.models.TaskUpdateEvent
import com.superteam.app.server.TaskQueueManager
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.Json
import java.io.File
import java.util.*

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    val taskQueue = TaskQueueManager()
    val sseJson = Json { encodeDefaults = false }

    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient = true
        })
    }

    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader("X-Client-Id")
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.CacheControl)
    }

    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok", "service" to "ktor-gateway"))
        }

        get("/") {
            call.respondText(sayHello("Ktor"))
        }

        post("/api/tasks") {
            val clientId = call.request.header("X-Client-Id") ?: UUID.randomUUID().toString()
            val multipart = call.receiveMultipart()
            val taskIds = mutableListOf<String>()

            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        val fileName = part.originalFileName ?: "upload"
                        val taskId = UUID.randomUUID().toString().take(12)
                        val tempFile = File(
                            System.getProperty("java.io.tmpdir"),
                            "${taskId}_$fileName"
                        )
                        part.provider().copyAndClose(tempFile.writeChannel())
                        taskQueue.enqueueTask(clientId, taskId, fileName)
                        taskIds.add(taskId)
                    }
                    else -> {}
                }
                part.dispose()
            }

            call.respond(
                mapOf("tasks" to taskIds.map { id ->
                    mapOf("task_id" to id, "filename" to id)
                })
            )
        }

        delete("/api/tasks") {
            val clientId = call.request.header("X-Client-Id") ?: return@delete call.respond(
                HttpStatusCode.BadRequest, mapOf("error" to "Missing X-Client-Id")
            )
            val body = call.receive<Map<String, List<String>>>()
            val taskIds = body["task_ids"] ?: return@delete call.respond(
                HttpStatusCode.BadRequest, mapOf("error" to "Missing task_ids")
            )
            taskQueue.cancelTasks(taskIds)
            call.respond(mapOf("status" to "cancelled"))
        }

        get("/api/tasks/stream") {
            val clientId = call.request.header("X-Client-Id")
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing X-Client-Id")

            call.response.cacheControl(CacheControl.NoCache(null))
            call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                val channel = taskQueue.observeUpdates()
                val knownIds = taskQueue.getClientTaskIds(clientId).toMutableSet()

                for (update in channel) {
                    if (update.taskId in knownIds) {
                        when (update.stage) {
                            "done" -> {
                                val fakeResult = sseJson.encodeToString(
                                    AnalysisResult.serializer(),
                                    AnalysisResult(
                                        sampleId = update.taskId,
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
                                val payload = sseJson.encodeToString(
                                    TaskUpdateEvent.serializer(),
                                    update.copy(message = fakeResult)
                                )
                                write("event: result\n")
                                write("data: $payload\n\n")
                            }
                            "error" -> {
                                val payload = sseJson.encodeToString(TaskUpdateEvent.serializer(), update)
                                write("event: error\n")
                                write("data: $payload\n\n")
                            }
                            else -> {
                                val payload = sseJson.encodeToString(TaskUpdateEvent.serializer(), update)
                                write("event: update\n")
                                write("data: $payload\n\n")
                            }
                        }
                        flush()
                    }
                }
            }
        }
    }
}