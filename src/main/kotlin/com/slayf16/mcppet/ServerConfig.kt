package com.slayf16.mcppet

import java.time.Duration

data class ServerConfig(
    val bindHost: String,
    val port: Int,
    val allowedHosts: List<String> = listOf(bindHost, "localhost"),
    val allowedOrigins: List<String> = listOf("http://$bindHost:$port", "http://localhost:$port"),
    val maxSessions: Int = 32,
    val sessionIdleTimeout: Duration = Duration.ofMinutes(5),
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): ServerConfig {
            val bindHost = environment["MCP_BIND_HOST"].trimmedOrNull() ?: "127.0.0.1"
            val port = environment["MCP_PORT"].strictInt("MCP_PORT") ?: 3000
            require(port in 1..65535) { "MCP_PORT must be between 1 and 65535." }
            val allowedHosts = environment.csv("MCP_ALLOWED_HOSTS") ?: listOf(bindHost, "localhost")
            val allowedOrigins = environment.csv("MCP_ALLOWED_ORIGINS") ?: listOf(
                "http://$bindHost:$port",
                "http://localhost:$port",
            )
            val maxSessions = environment["MCP_MAX_SESSIONS"].strictInt("MCP_MAX_SESSIONS") ?: 32
            require(maxSessions in 1..1_000) { "MCP_MAX_SESSIONS must be between 1 and 1000." }
            val idleSeconds = environment["MCP_SESSION_IDLE_SECONDS"].strictInt("MCP_SESSION_IDLE_SECONDS") ?: 300
            require(idleSeconds in 1..3_600) { "MCP_SESSION_IDLE_SECONDS must be between 1 and 3600." }
            return ServerConfig(bindHost, port, allowedHosts, allowedOrigins, maxSessions, Duration.ofSeconds(idleSeconds.toLong()))
        }

        private fun Map<String, String>.csv(name: String): List<String>? =
            this[name]?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.takeIf(List<String>::isNotEmpty)

        private fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf(String::isNotEmpty)

        private fun String?.strictInt(name: String): Int? {
            val value = trimmedOrNull() ?: return null
            return value.toIntOrNull() ?: throw IllegalArgumentException("$name must be an integer.")
        }
    }
}
