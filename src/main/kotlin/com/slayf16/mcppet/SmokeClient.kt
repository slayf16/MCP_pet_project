package com.slayf16.mcppet

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun main(args: Array<String>) = runBlocking {
    require(args.size == 2) { "Usage: smoke --args '<mcp-url> <name>'" }
    val httpClient = HttpClient(CIO)
    val client = Client(clientInfo = Implementation(name = "mcp-smoke-client", version = "0.1.0"))
    try {
        client.connect(StreamableHttpClientTransport(client = httpClient, url = args[0]))
        SmokeContract.validateDiscovery(client.listTools().tools)
        val result = client.callTool(TOOL_NAME, buildJsonObject { put("name", args[1]) })
        SmokeContract.validateSuccess(result, args[1])
        println(result.structuredContent)
    } finally {
        client.close()
        httpClient.close()
    }
}
