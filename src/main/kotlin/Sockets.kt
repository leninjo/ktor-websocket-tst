package com.example

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.ktor.websocket.Frame.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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

                        try {
                            val envelope = json.decodeFromString<ClientMessage>(text)

                            when (envelope.type) {
                                "register" -> {
                                    val data = json.decodeFromJsonElement<RegisterData>(envelope.data!!)
                                    val expectedToken = generateToken(data.clientId)

                                    if (data.authToken != expectedToken) {
                                        println("❌ Invalid token for ${data.clientId}")
                                        close(
                                            CloseReason(
                                                CloseReason.Codes.VIOLATED_POLICY,
                                                "Invalid auth token"
                                            )
                                        )
                                        return@webSocket
                                    }

                                    clientId = data.clientId
                                    val dev = connections.getOrPut(clientId) { DeviceConnections() }

                                    when (data.role) {
                                        "web" -> {
                                            dev.web = this
                                            send(Frame.Text("✅ Web registrada por $clientId"))
                                            println("🌐 Web client registered: $clientId")
                                        }
                                        "app" -> {
                                            dev.app = this
                                            send(Frame.Text("✅ App registrada por $clientId"))
                                            println("📱 App client registered: $clientId")
                                        }
                                        else  -> {
                                            send(Frame.Text("❌ Rol inválido"))
                                            continue
                                        }
                                    }
                                }

                                /* ---------- WEB → APP ---------- */
                                "send_to_app" -> {
                                    val req = json.decodeFromJsonElement<SendMessageRequest>(envelope.data!!)

                                    val method = req.method.toMethodOrNull()
                                    if (method == null) {
                                        send(Frame.Text("Método '${req.method}' no válido. Usa: getTerminal, getCardData o printVoucher."))
                                        continue
                                    }

                                    if (method == Method.PRINT_VOUCHER) {
                                        if (req.body == null || req.body == JsonNull) {
                                            send(Frame.Text("El campo 'body' es obligatorio para el método 'printVoucher'."))
                                            continue
                                        }

                                        if (req.body !is JsonObject) {
                                            send(Frame.Text("El campo 'body' debe ser un objeto JSON válido."))
                                            continue
                                        }
                                    }

                                    val target = connections[req.to]?.app

                                    if (target != null) {
                                        println("➡️ Web $clientId → App ${req.to}: ${req.method}")
                                        target.send(Frame.Text(text))
                                    } else {
                                        send(Frame.Text("App ${req.to} no conectado"))
                                    }
                                }

                                /* ---------- APP → WEB ---------- */
                                "send_to_web" -> {
                                    val req = json.decodeFromJsonElement<SendMessageRequestApp>(envelope.data!!)
                                    val target = connections[req.to]?.web

                                    val method = req.method.toMethodOrNull()
                                    if (method == null) {
                                        send(Frame.Text("Método '${req.method}' no válido. Usa: getTerminal o getCardData."))
                                        continue
                                    }

                                    val responseObj = req.response as? JsonObject
                                    if (responseObj == null) {
                                        send(Frame.Text("El campo 'response' debe ser un objeto JSON válido."))
                                        continue
                                    }

                                    when (method) {
                                        Method.GET_TERMINAL -> {
                                            if (!responseObj.containsKey("terminal")) {
                                                send(Text("Falta el campo 'terminal' en la respuesta de getTerminal."))
                                                continue
                                            }
                                        }
                                        Method.GET_CARD_DATA -> {
                                            if (!responseObj.containsKey("cardString")) {
                                                send(Text("Falta el campo 'cardString' en la respuesta de getCardData."))
                                                continue
                                            }
                                        }

                                        Method.PRINT_VOUCHER -> {}
                                    }

                                    if (target != null) {
                                        println("⬅️ App $clientId → Web ${req.to}: ${req.method}")
                                        target.send(Frame.Text(text))
                                    } else {
                                        send(Frame.Text("Web ${req.to} no conectado"))
                                    }
                                }

                                else -> {
                                    send(Frame.Text("Tipo de mensaje no soportado"))
                                }
                            }

                        } catch (e: SerializationException) {
                            send(Frame.Text("Formato inválido del mensaje: ${e.localizedMessage}"))
                        } catch (e: IllegalArgumentException) {
                            send(Frame.Text("Error inesperado: ${e.localizedMessage}"))
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
                    println("Desconectado $clientId (${if (device?.web == this) "web" else "app"})")
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

enum class Method {
    GET_TERMINAL,
    GET_CARD_DATA,
    PRINT_VOUCHER
}

fun String.toMethodOrNull(): Method? = when (this) {
    "getTerminal" -> Method.GET_TERMINAL
    "getCardData" -> Method.GET_CARD_DATA
    "printVoucher" -> Method.PRINT_VOUCHER
    else -> null
}

@Serializable
data class SendMessageRequest(
    val to: String,
    val method: String,
    val body: JsonElement? = null
)

@Serializable
data class SendMessageRequestApp(
    val to: String,
    val method: String,
    val response: JsonElement
)

fun generateToken(clientId: String, secret: String = "clave-maestra-oculta"): String {
    val toHash = "$secret$clientId"
    val bytes = MessageDigest.getInstance("SHA-256").digest(toHash.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}