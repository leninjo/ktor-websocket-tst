@file:Suppress("CAST_NEVER_SUCCEEDS")

package com.example

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

fun Application.configureSockets() {
    install(WebSockets) {
        pingPeriod = 60.seconds
        timeout = 120.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
        extensions {
            install(WebSocketDeflateExtension)
        }
    }

    routing {
        val connections = mutableMapOf<String, DeviceConnections>()

        val messageScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
            val json = Json {
                ignoreUnknownKeys = true
                encodeDefaults = false
                coerceInputValues = true
            }

            try {
                call.response.headers.append("Connection", "Upgrade")

                for (frame in incoming) {
                    messageScope.launch {
                        try {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                println("📩 [RECEIVED] From ${clientId ?: "Unknown"}: $text")

                                val envelope = try {
                                    json.decodeFromString<ClientMessage>(text)
                                } catch (e: Exception) {
                                    safeSend(this@webSocket, "❌ Formato inválido: ${e.localizedMessage}")
                                    return@launch
                                }

                                when (envelope.type) {
                                    "register" -> {
                                        val data = try {
                                            json.decodeFromJsonElement<RegisterData>(envelope.data!!)
                                        } catch (e: Exception) {
                                            safeSend(this@webSocket, "❌ Error en datos de registro")
                                            return@launch
                                        }

                                        val expectedToken = generateToken(data.clientId)
                                        if (data.authToken != expectedToken) {
                                            println("❌ Invalid token for ${data.clientId}")
                                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid auth token"))
                                            return@launch
                                        }

                                        clientId = data.clientId
                                        val device = connections.getOrPut(clientId) { DeviceConnections() }

                                        when (data.role) {
                                            "web" -> {
                                                device.web = this@webSocket
                                                println("🌐 Web client registered: $clientId")
                                                safeSend(this@webSocket, "✅ Web registrada por $clientId")
                                            }

                                            "app" -> {
                                                device.app = this@webSocket
                                                println("📱 App client registered: $clientId")
                                                safeSend(this@webSocket, "✅ App registrada por $clientId")
                                            }

                                            else -> {
                                                safeSend(this@webSocket, "❌ Rol inválido")
                                            }
                                        }
                                    }

                                    "send_to_app" -> {
                                        val base = try {
                                            json.decodeFromJsonElement<SendMessageWeb>(envelope.data!!)
                                        } catch (e: Exception) {
                                            safeSend(this@webSocket, "❌ Error en datos de send_to_app: ${e.localizedMessage}")
                                            return@launch
                                        }

                                        if (base.method == Method.PRINT_VOUCHER && base.body == null) {
                                            safeSend(this@webSocket, "❌ 'body' es obligatorio para printVoucher")
                                            return@launch
                                        }

                                        val target = connections[base.to]?.app
                                        if (target != null) {
                                            println("➡️ Web $clientId → App ${base.to}: ${base.method}")
                                            if (!safeSend(target, text)) {
                                                safeSend(this@webSocket, "❌ Falló envío a App ${base.to}")
                                            }
                                        } else {
                                            safeSend(this@webSocket, "❌ App ${base.to} no conectado")
                                        }
                                    }

                                    "send_to_web" -> {
                                        val base = try {
                                            json.decodeFromJsonElement<SendMessageApp>(envelope.data!!)
                                        } catch (e: Exception) {
                                            safeSend(this@webSocket, "❌ Error en datos de send_to_web: ${e.localizedMessage}")
                                            return@launch
                                        }

                                        try {
                                            when (base.method) {
                                                Method.GET_TERMINAL -> json.decodeFromJsonElement(GetTerminalResponse.serializer(), base.response)
                                                Method.GET_CARD_DATA -> json.decodeFromJsonElement(GetCardDataResponse.serializer(), base.response)
                                                Method.PRINT_VOUCHER -> json.decodeFromJsonElement(PrintVoucherResponseApp.serializer(), base.response)
                                            }
                                        } catch (e: Exception) {
                                            safeSend(this@webSocket, "❌ Error parseando respuesta: ${e.localizedMessage}")
                                            return@launch
                                        }

                                        val target = connections[base.to]?.web
                                        if (target != null) {
                                            println("⬅️ App $clientId → Web ${base.to}: ${base.method}")
                                            if (!safeSend(target, text)) {
                                                safeSend(this@webSocket, "❌ Falló envío a Web ${base.to}")
                                            }
                                        } else {
                                            safeSend(this@webSocket, "❌ Web ${base.to} no conectado")
                                        }
                                    }

                                    else -> {
                                        safeSend(this@webSocket, "❌ Tipo de mensaje no soportado: ${envelope.type}")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            println("❌ Error procesando mensaje: ${e.localizedMessage}")
                            safeSend(this@webSocket, "❌ Error interno: ${e.localizedMessage}")
                        }
                    }
                }
            } finally {
                clientId?.let { id ->
                    val device = connections[id]
                    if (device != null) {
                        if (device.web == this) device.web = null
                        if (device.app == this) device.app = null

                        if (device.web == null && device.app == null) {
                            connections.remove(id)
                        }
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
    val body: JsonElement? = null
)

fun generateToken(clientId: String, secret: String = "clave-maestra-oculta"): String {
    val toHash = "$secret$clientId"
    val bytes = MessageDigest.getInstance("SHA-256").digest(toHash.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}