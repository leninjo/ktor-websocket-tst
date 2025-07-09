package com.example

import kotlinx.coroutines.*
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPubSub
import java.util.concurrent.ConcurrentHashMap

object RedisPubSub {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var redisHost: String
    private lateinit var subscriber: JedisPubSub
    private lateinit var jedisPub: Jedis
    var instanceId: String = "0"

    fun init(
        redisHostEnv: String,
        jsonHandler: (String) -> Unit
    ) {
        redisHost = redisHostEnv
        jedisPub = Jedis(redisHost)
        instanceId = System.getenv("INSTANCE_ID") ?: "0"

        subscriber = object : JedisPubSub() {
            override fun onMessage(channel: String, message: String) {
                if (!message.contains("origin=$instanceId")) {
                    val payload = message.split("|||origin=").first()
                    jsonHandler(payload)
                }
            }
        }

        scope.launch {
            try {
                Jedis(redisHost).use { jedis ->
                    jedis.subscribe(subscriber, "ws-channel")
                }
            } catch (e: Exception) {
                println("❌ Redis subscriber error: ${e.localizedMessage}")
            }
        }
    }

    fun publish(message: String) {
        val wrapped = "$message|||origin=$instanceId"
        jedisPub.publish("ws-channel", wrapped)
    }
}
