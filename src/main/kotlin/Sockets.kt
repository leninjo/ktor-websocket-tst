package com.example

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds

fun Application.configureSockets() {
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
    routing {
        val connections = mutableMapOf<String, DefaultWebSocketServerSession>()

        webSocket("/ws") {
            var clientId: String? = null
            val json = Json { ignoreUnknownKeys = true }

            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val text = frame.readText()

                        println("📩 [RECEIVED] From ${clientId ?: "Unknown"}: $text")

                        val message = json.decodeFromString<ClientMessage>(text)

                        when (message.type) {
                            "register" -> {
                                val data = json.decodeFromJsonElement<RegisterData>(message.data!!)
                                val expectedToken = generateToken(data.clientId)

                                if (data.authToken != expectedToken) {
                                    println("❌ Invalid token for ${data.clientId}")
                                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid auth token"))
                                    return@webSocket
                                }
                                clientId = data.clientId
                                connections[clientId] = this
                                println("Client registered: $clientId")
                                send(Frame.Text("✅ Registered as $clientId"))
                            }
                            "send_to_app" -> {
                                if (clientId == null) {
                                    send(Frame.Text("❌ You must register first"))
                                    continue
                                }

                                val data = json.decodeFromJsonElement<SendMessageData>(message.data!!)
                                val targetSession = connections[data.to]
                                println("➡️ [FORWARD] $clientId to ${data.to}: ${data.message}")

                                if (targetSession != null) {
                                    targetSession.send(Frame.Text("Message from $clientId: ${data.message}"))
                                } else {
                                    outgoing.send(Frame.Text("Target ${data.to} not connected"))
                                }
                            }
                            "send_to_web" -> {
                                if (clientId == null) {
                                    send(Frame.Text("❌ You must register first"))
                                    continue
                                }

                                val data = json.decodeFromJsonElement<SendMessageData>(message.data!!)
                                val targetSession = connections[data.to]
                                println("➡️ [FORWARD] $clientId to ${data.to}: ${data.message}")

                                if (targetSession != null) {
                                    targetSession.send(Frame.Text("Response from App: ${data.message}"))
                                } else {
                                    outgoing.send(Frame.Text("Web target ${data.to} not connected"))
                                }
                            }
                        }
                    }
                }
            } finally {
                clientId?.let { connections.remove(it) }
                println("❌ Client disconnected: $clientId")
            }
        }
    }
}

@Serializable
data class ClientMessage(val type: String, val data: JsonElement? = null)

@Serializable
data class RegisterData(val clientId: String, val authToken: String)

@Serializable
data class SendMessageData(val to: String, val message: String)

fun generateToken(clientId: String, secret: String = "clave-maestra-oculta"): String {
    val toHash = "$secret$clientId"
    val bytes = MessageDigest.getInstance("SHA-256").digest(toHash.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}