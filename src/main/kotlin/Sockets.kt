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

        suspend fun safeSend(session: DefaultWebSocketServerSession?, message: String): Boolean {
            return try {
                session?.send(Frame.Text(message))
                true
            } catch (e: Exception) {
                println("❌ Error al enviar mensaje: ${e.localizedMessage}")
                false
            }
        }

        webSocket("/ws") {
            var clientId: String? = null
            val json = Json { ignoreUnknownKeys = true }

            try {
                for (frame in incoming) {
                    try {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            println("📩 [RECEIVED] From ${clientId ?: "Unknown"}: $text")

                            val envelope = try {
                                json.decodeFromString<ClientMessage>(text)
                            } catch (e: Exception) {
                                safeSend(this, "❌ Formato inválido: ${e.localizedMessage}")
                                continue
                            }

                            when (envelope.type) {
                                "register" -> {
                                    val data = try {
                                        json.decodeFromJsonElement<RegisterData>(envelope.data!!)
                                    } catch (e: Exception) {
                                        safeSend(this, "❌ Error en datos de registro")
                                        continue
                                    }

                                    val expectedToken = generateToken(data.clientId)
                                    if (data.authToken != expectedToken) {
                                        println("❌ Invalid token for ${data.clientId}")
                                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid auth token"))
                                        return@webSocket
                                    }

                                    clientId = data.clientId
                                    val device = connections.getOrPut(clientId) { DeviceConnections() }

                                    when (data.role) {
                                        "web" -> {
                                            device.web = this
                                            println("🌐 Web client registered: $clientId")
                                            safeSend(this, "✅ Web registrada por $clientId")
                                        }

                                        "app" -> {
                                            device.app = this
                                            println("📱 App client registered: $clientId")
                                            safeSend(this, "✅ App registrada por $clientId")
                                        }

                                        else -> {
                                            safeSend(this, "❌ Rol inválido")
                                        }
                                    }
                                }

                                "send_to_app" -> {
                                    val base = try {
                                        json.decodeFromJsonElement<SendMessageWeb>(envelope.data!!)
                                    } catch (e: Exception) {
                                        safeSend(this, "❌ Error en datos de send_to_app: ${e.localizedMessage}")
                                        continue
                                    }

                                    val target = connections[base.to]?.app
                                    if (target != null) {
                                        println("➡️ Web $clientId → App ${base.to}: ${base.method}")
                                        if (!safeSend(target, text)) {
                                            safeSend(this, "❌ Falló envío a App ${base.to}")
                                        }
                                    } else {
                                        safeSend(this, "❌ App ${base.to} no conectado")
                                    }
                                }

                                "send_to_web" -> {
                                    val base = try {
                                        json.decodeFromJsonElement<SendMessageApp>(envelope.data!!)
                                    } catch (e: Exception) {
                                        safeSend(this, "❌ Error en datos de send_to_web: ${e.localizedMessage}")
                                        continue
                                    }

                                    try {
                                        when (base.method) {
                                            Method.GET_TERMINAL -> json.decodeFromJsonElement(GetTerminalResponse.serializer(), base.response)
                                            Method.GET_CARD_DATA -> json.decodeFromJsonElement(GetCardDataResponse.serializer(), base.response)
                                            Method.PRINT_VOUCHER -> json.decodeFromJsonElement(PrintVoucherResponseApp.serializer(), base.response)
                                        }
                                    } catch (e: Exception) {
                                        safeSend(this, "❌ Error parseando respuesta: ${e.localizedMessage}")
                                        continue
                                    }

                                    val target = connections[base.to]?.web
                                    if (target != null) {
                                        println("⬅️ App $clientId → Web ${base.to}: ${base.method}")
                                        if (!safeSend(target, text)) {
                                            safeSend(this, "❌ Falló envío a Web ${base.to}")
                                        }
                                    } else {
                                        safeSend(this, "❌ Web ${base.to} no conectado")
                                    }
                                }

                                else -> {
                                    safeSend(this, "❌ Tipo de mensaje no soportado: ${envelope.type}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        println("❌ Error procesando mensaje: ${e.localizedMessage}")
                        safeSend(this, "❌ Error interno: ${e.localizedMessage}")
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

                    println("🔌 Desconectado $clientId")
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