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
        val connections = mutableMapOf<String, DeviceConnections>()

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

                                println("TOKEN ESPERADO: $expectedToken")

                                if (data.authToken != expectedToken) {
                                    println("❌ Invalid token for ${data.clientId}")
                                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid auth token"))
                                    return@webSocket
                                }

                                clientId = data.clientId
                                val deviceConnections = connections.getOrPut(clientId) { DeviceConnections() }

                                when (data.role) {
                                    "web" -> {
                                        deviceConnections.web = this
                                        println("🌐 Web client registered: $clientId")
                                        send(Frame.Text("✅ Web registrada por $clientId"))
                                    }
                                    "app" -> {
                                        deviceConnections.app = this
                                        println("📱 App client registered: $clientId")
                                        send(Frame.Text("✅ App registrada por $clientId"))
                                    }
                                    else -> {
                                        send(Frame.Text("❌ Rol invalido"))
                                        return@webSocket
                                    }
                                }
                            }
                            "send_to_app" -> {
                                val data = json.decodeFromJsonElement<SendMessageData>(message.data!!)
                                val target = connections[data.to]?.app

                                if (target != null) {
                                    println("➡️ Web ${clientId} → App ${data.to}: ${data.message}")
                                    target.send(Frame.Text(data.message))
                                } else {
                                    send(Frame.Text("❌ App ${data.to} no conectado"))
                                }
                            }
                            "send_to_web" -> {
                                val data = json.decodeFromJsonElement<SendMessageData>(message.data!!)
                                val target = connections[data.to]?.web

                                if (target != null) {
                                    println("⬅️ App ${clientId} → Web ${data.to}: ${data.message}")
                                    target.send(Frame.Text(data.message))
                                } else {
                                    send(Frame.Text("❌ Web ${data.to} no conectado"))
                                }
                            }
                        }
                    }
                }
            } finally {
                clientId?.let {
                    val device = connections[it]
                    if (device?.web == this) device.web = null
                    if (device?.app == this) device.app = null

                    if (device?.web == null && device?.app == null) {
                        connections.remove(it)
                    }
                    println("❌ Desconectado $clientId (${if (device?.web == this) "web" else "app"})")
                }
            }
        }
    }
}

data class DeviceConnections(
    var web: DefaultWebSocketServerSession? = null,
    var app: DefaultWebSocketServerSession? = null
)

@Serializable
data class ClientMessage(val type: String, val data: JsonElement? = null)

@Serializable
data class RegisterData(
    val clientId: String,
    val role: String,
    val authToken: String
)

@Serializable
data class SendMessageData(val to: String, val message: String)

fun generateToken(clientId: String, secret: String = "clave-maestra-oculta"): String {
    val toHash = "$secret$clientId"
    val bytes = MessageDigest.getInstance("SHA-256").digest(toHash.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}