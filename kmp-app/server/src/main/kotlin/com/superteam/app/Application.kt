package com.superteam.app

import com.superteam.app.models.AnalysisResult
import com.superteam.app.models.TaskUpdateEvent
import com.superteam.app.server.TaskQueueManager
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.io.File
import java.util.*

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module() {
    val taskQueue = TaskQueueManager()
    val sseJson = Json { encodeDefaults = false; ignoreUnknownKeys = true }

    val internalClient = HttpClient(CIO) {
        install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    install(CallLogging) {
        level = Level.INFO
        filter { call -> !call.request.uri.startsWith("/health") }
    }

    install(ServerContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; isLenient = true })
    }

    install(SSE)

    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader("X-Client-Id")
        allowHeader(HttpHeaders.CacheControl)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Delete)
    }

    routing {
        get("/health") {
            val isPythonHealthy = try {
                internalClient.get("http://localhost:8000/health").status.isSuccess()
            } catch (e: Exception) {
                false
            }
            call.respond(
                mapOf(
                    "ktor" to "ok",
                    "python" to if (isPythonHealthy) "ok" else "disconnected"
                     )
                        )
        }

        get("/") {
            call.respondText("Ktor Gateway")
        }

        post("/api/tasks") {
            val isPythonHealthy = try {
                internalClient.get("http://localhost:8000/health").status.isSuccess()
            } catch (e: Exception) {
                false
            }

            if (!isPythonHealthy) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    mapOf("error" to "Python ML Server is unreachable. Please try again later.")
                            )
                return@post
            }

            val clientId = call.request.header("X-Client-Id") ?: UUID.randomUUID().toString()
            val multipart = call.receiveMultipart()
            val taskIds = mutableListOf<String>()

            multipart.forEachPart { part ->
                if (part is PartData.FileItem) {
                    val fileName = part.originalFileName ?: "upload"
                    val taskId = UUID.randomUUID().toString().take(12)
                    
                    val time = java.time.LocalDateTime.now().toString().replace("T", " ")
                    println("$time [KTOR-GATEWAY] Accepting file: $fileName. Generating Task ID: $taskId")

                    val tempFile = File(System.getProperty("java.io.tmpdir"), "${taskId}_$fileName")
                    part.provider().copyAndClose(tempFile.writeChannel())

                    taskQueue.enqueueTask(clientId, taskId, fileName, tempFile)
                    taskIds.add(taskId)
                }
                part.release()
            }

            call.respond(mapOf("tasks" to taskIds.map { mapOf("task_id" to it, "filename" to it) }))
        }

        delete("/api/tasks") {
            val body = call.receive<Map<String, List<String>>>()
            val ids = body["task_ids"] ?: return@delete call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Missing task_ids")
                                                                    )
            taskQueue.cancelTasks(ids)
            call.respond(mapOf("status" to "cancelled"))
        }

        sse("/api/tasks/stream") {
            val clientId = call.request.header("X-Client-Id") ?: return@sse close()
            send(ServerSentEvent(event = "ping", data = """{"status":"connected"}"""))

            val knownIds = taskQueue.getClientTaskIds(clientId).toMutableSet()

            taskQueue.observeUpdates().collect { update ->
                if (update.taskId in knownIds || update.stage == "queued") {
                    knownIds.add(update.taskId)
                    when (update.stage) {
                        "error" -> {
                            val payload = sseJson.encodeToString(TaskUpdateEvent.serializer(), update)
                            send(ServerSentEvent(data = payload, event = "error"))
                        }
                        "done" -> {
                            val payload = sseJson.encodeToString(TaskUpdateEvent.serializer(), update)
                            send(ServerSentEvent(data = payload, event = "result"))
                        }
                        else -> {
                            val payload = sseJson.encodeToString(TaskUpdateEvent.serializer(), update)
                            send(ServerSentEvent(data = payload, event = "update"))
                        }
                    }
                }
            }
        }
    }
}