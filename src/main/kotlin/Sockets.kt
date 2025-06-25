@file:Suppress("CAST_NEVER_SUCCEEDS")

package com.example

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
                                    val base = json.decodeFromJsonElement<SendMessageWeb>(envelope.data!!)

                                    when (base.method) {
                                        Method.GET_TERMINAL -> {}
                                        Method.GET_CARD_DATA -> {}
                                        Method.PRINT_VOUCHER -> {
                                            json.decodeFromJsonElement(JsonObject.serializer(), base.body)
                                        }
                                    }

                                    val target = connections[base.to]?.app

                                    if (target != null) {
                                        println("➡️ Web $clientId → App ${base.to}: ${base.method}")
                                        target.send(Frame.Text(text))
                                    } else {
                                        send(Frame.Text("App ${base.to} no conectado"))
                                    }
                                }

                                /* ---------- APP → WEB ---------- */
                                "send_to_web" -> {
                                    val base = json.decodeFromJsonElement<SendMessageApp>(envelope.data!!)

                                    when (base.method) {
                                        Method.GET_TERMINAL -> {
                                            json.decodeFromJsonElement(
                                                GetTerminalResponse.serializer(), base.response
                                            )
                                        }
                                        Method.GET_CARD_DATA -> {
                                            json.decodeFromJsonElement(
                                                GetCardDataResponse.serializer(), base.response
                                            )
                                        }
                                        Method.PRINT_VOUCHER -> {
                                            json.decodeFromJsonElement(
                                                PrintVoucherResponseApp.serializer(), base.response
                                            )
                                        }
                                    }

                                    val target = connections[base.to]?.web

                                    if (target != null) {
                                        println("⬅️ App $clientId → Web ${base.to}: ${base.method}")
                                        target.send(Frame.Text(text))
                                    } else {
                                        send(Frame.Text("Web ${base.to} no conectado"))
                                    }
                                }

                                else -> {
                                    send(Frame.Text("Tipo de mensaje no soportado"))
                                }
                            }

                        } catch (e: SerializationException) {
                            send(Frame.Text(e.localizedMessage))
                        } catch (e: IllegalArgumentException) {
                            send(Frame.Text(e.localizedMessage))
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
    @SerialName("getTerminal")
    GET_TERMINAL,

    @SerialName("getCardData")
    GET_CARD_DATA,

    @SerialName("printVoucher")
    PRINT_VOUCHER
}

fun String.toMethodOrNull(): Method? = when (this) {
    "getTerminal" -> Method.GET_TERMINAL
    "getCardData" -> Method.GET_CARD_DATA
    "printVoucher" -> Method.PRINT_VOUCHER
    else -> null
}

@Serializable
enum class Status { success, fail }

@Serializable
sealed interface AppResponse

@Serializable @SerialName("getTerminal")
data class GetTerminalResponse(
    val status: Status,
    val statusMsg: String,
    val terminal: String
) : AppResponse

@Serializable @SerialName("getCardData")
data class GetCardDataResponse(
    val status: Status,
    val statusMsg: String,
    val cardString: String
) : AppResponse

@Serializable
data class PrintVoucherResponseApp(
    val status: Status,
    val statusMsg: String
) : AppResponse

@Serializable
data class PrintVoucherResponseWeb(
    val body: JsonObject
) : AppResponse

@Serializable
data class SendMessageApp(
    val type: String = "send_to_web",
    val to: String,
    val method: Method,
    val response: JsonElement
)

@Serializable
data class SendMessageWeb(
    val type: String = "send_to_app",
    val to: String,
    val method: Method,
    val body: JsonElement
)

fun generateToken(clientId: String, secret: String = "clave-maestra-oculta"): String {
    val toHash = "$secret$clientId"
    val bytes = MessageDigest.getInstance("SHA-256").digest(toHash.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}