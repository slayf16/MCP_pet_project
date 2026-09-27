package com.slayf16.mcppet

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

fun main() {
    val config = ServerConfig.fromEnvironment()
    val upstreamClient = HttpClient(CIO) { expectSuccess = false }
    val server = McpServer(config, upstreamClient)
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    server.start(wait = true)
}
