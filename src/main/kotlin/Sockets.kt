package com.example

import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

fun Application.configureSockets() {
    install(WebSockets) {
        pingPeriod = 60.seconds
        timeout = 120.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
        contentConverter = KotlinxWebsocketSerializationConverter(Json)
    }

    routing {
        val instanceId = System.getenv("INSTANCE_ID") ?: "?"
        val connections = ConcurrentHashMap<String, DeviceConnections>()
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; coerceInputValues = true }

        val redisUrl = System.getenv("REDIS_URL") ?: throw IllegalStateException("Missing REDIS_URL env var")
        val redisUri = URI(redisUrl)
        val redisHost = redisUri.host
        val redisPort = redisUri.port.takeIf { it != -1 } ?: 6379

        val redisFullUrl = "redis://$redisHost:$redisPort"

        RedisPubSub.init(redisFullUrl) { message ->
            val parts = message.split("|||origin=")
            val payload = parts[0]
            try {
                val envelope = json.decodeFromString<ClientMessage>(payload)

                val baseTo = when (envelope.type) {
                    "send_to_app" -> json.decodeFromJsonElement<SendMessageWeb>(envelope.data!!).to
                    "send_to_web" -> json.decodeFromJsonElement<SendMessageApp>(envelope.data!!).to
                    else -> null
                }

                baseTo?.let {
                    val target = if (envelope.type == "send_to_app") {
                        connections[it]?.app
                    } else {
                        connections[it]?.web
                    }

                    if (target != null) {
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                target.send(Frame.Text(payload))
                            } catch (e: Exception) {
                                println("➡️ Mensaje reenviado desde instancia $instanceId via Redis a ${baseTo}")
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                println("❌ Error al manejar mensaje de Redis: ${e.localizedMessage}")
            }
        }

        webSocket("/ws") {
            val clientIdRef = AtomicReference<String?>(null)

            suspend fun safeSend(
                session: DefaultWebSocketServerSession?,
                message: String
            ): Boolean {
                return try {
                    session?.send(Frame.Text(message))
                    true
                } catch (e: Exception) {
                    println("❌ Error al enviar mensaje: ${e.localizedMessage}")
                    val clientId = clientIdRef.get()
                    if (clientId != null) {
                        val device = connections[clientId]
                        if (device?.web == session) device?.web = null
                        if (device?.app == session) device?.app = null
                        if (device?.web == null && device?.app == null) {
                            connections.remove(clientId)
                            println("🧹 Cliente eliminado por error: $clientId")
                        }
                    }
                    false
                }
            }

            try {

                for (frame in incoming) {
                    try {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            println("📩 [RECEIVED] From ${clientIdRef.get() ?: "Unknown"}: $text")

                            val envelope = try {
                                json.decodeFromString<ClientMessage>(text)
                            } catch (e: Exception) {
                                safeSend(this@webSocket, "❌ Formato inválido: ${e.localizedMessage}")
                                continue
                            }

                            when (envelope.type) {
                                "register" -> {
                                    val data = try {
                                        json.decodeFromJsonElement<RegisterData>(envelope.data!!)
                                    } catch (e: Exception) {
                                        safeSend(this@webSocket, "❌ Error en datos de registro")
                                        continue
                                    }

                                    val expectedToken = generateToken(data.clientId)
                                    if (data.authToken != expectedToken) {
                                        println("❌ Invalid token for ${data.clientId}")
                                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid auth token"))
                                        continue
                                    }

                                    clientIdRef.set(data.clientId)

                                    val clientId = clientIdRef.get()
                                    val device = connections.getOrPut(clientId) { DeviceConnections() }

                                    when (data.role) {
                                        "web" -> {
                                            device.web = this@webSocket
                                            println("🌐 Web client registered: $clientId")
                                            safeSend(this@webSocket, "✅ Web registrada por $clientId en instancia $instanceId")
                                        }

                                        "app" -> {
                                            device.app = this@webSocket
                                            println("📱 App client registered: $clientId")
                                            safeSend(this@webSocket, "✅ App registrada por $clientId en instancia $instanceId")
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
                                        continue
                                    }

                                    if (base.method == Method.PRINT_VOUCHER && base.body == null) {
                                        safeSend(this@webSocket, "❌ 'body' es obligatorio para printVoucher")
                                        continue
                                    }

                                    val target = connections[base.to]?.app
                                    if (target != null) {
                                        val clientId = clientIdRef.get()

                                        println("➡️ Web $clientId → App ${base.to}: ${base.method}")
                                        if (!safeSend(target, text)) {
                                            safeSend(this@webSocket, "❌ Falló envío a App ${base.to}")
                                        }
                                    } else {
                                        RedisPubSub.publish(text)
                                        safeSend(this@webSocket, "❌ App ${base.to} no conectado")
                                    }
                                }

                                "send_to_web" -> {
                                    val base = try {
                                        json.decodeFromJsonElement<SendMessageApp>(envelope.data!!)
                                    } catch (e: Exception) {
                                        safeSend(this@webSocket, "❌ Error en datos de send_to_web: ${e.localizedMessage}")
                                        continue
                                    }

                                    try {
                                        when (base.method) {
                                            Method.GET_TERMINAL -> json.decodeFromJsonElement(GetTerminalResponse.serializer(), base.response)
                                            Method.GET_CARD_DATA -> json.decodeFromJsonElement(GetCardDataResponse.serializer(), base.response)
                                            Method.PRINT_VOUCHER -> json.decodeFromJsonElement(PrintVoucherResponseApp.serializer(), base.response)
                                        }
                                    } catch (e: Exception) {
                                        safeSend(this@webSocket, "❌ Error parseando respuesta: ${e.localizedMessage}")
                                        continue
                                    }

                                    val target = connections[base.to]?.web
                                    if (target != null) {
                                        val clientId = clientIdRef.get()
                                        println("⬅️ App $clientId → Web ${base.to}: ${base.method}")
                                        if (!safeSend(target, text)) {
                                            safeSend(this@webSocket, "❌ Falló envío a Web ${base.to}")
                                        }
                                    } else {
                                        RedisPubSub.publish(text)
                                        safeSend(this@webSocket, "❌ Web ${base.to} no conectado",)
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
            } finally {
                val clientId = clientIdRef.get()

                if (clientId != null) {
                    val device = connections[clientId]
                    if (device?.web == this) device.web = null
                    if (device?.app == this) device.app = null

                    if (device?.web == null && device?.app == null) {
                        connections.remove(clientId)
                    }

                    val close = closeReason.await()
                    println("🔌 Cierre conexión $clientId con motivo: ${close?.message}")
                    println("👥 Conexiones activas: ${connections.size}")
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