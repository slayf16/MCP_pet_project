package com.slayf16.mcppet

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class McpSearchIntegrationTest {
    @Test
    fun `real MCP transport discovers one tool and returns bounded first page`() = runBlocking {
        var upstreamRequestCount = 0
        var receivedName: String? = null
        var receivedParameterNames: Set<String> = emptySet()
        val upstreamPort = freePort()
        val upstream = embeddedServer(Netty, host = "127.0.0.1", port = upstreamPort) {
            routing {
                get("/api/character/") {
                    upstreamRequestCount += 1
                    receivedName = call.request.queryParameters["name"]
                    receivedParameterNames = call.request.queryParameters.names()
                    call.respondText(
                        successPayload,
                        ContentType.Application.Json,
                        HttpStatusCode.OK,
                    )
                }
            }
        }.start(wait = false)

        val mcpPort = freePort()
        val upstreamClient = HttpClient(CIO) { expectSuccess = false }
        val mcp = McpServer(
            config = ServerConfig("127.0.0.1", mcpPort),
            upstreamClient = upstreamClient,
            upstreamBaseUrl = "http://127.0.0.1:$upstreamPort/api",
        )
        mcp.start(wait = false)

        val transportClient = HttpClient(CIO)
        val client = Client(
            clientInfo = Implementation(name = "integration-test-client", version = "0.1.0"),
            options = ClientOptions(),
        )
        try {
            client.connect(
                StreamableHttpClientTransport(
                    client = transportClient,
                    url = "http://127.0.0.1:$mcpPort/mcp",
                ),
            )

            val tools = client.listTools().tools
            assertEquals(1, tools.size)
            val tool = tools.single()
            assertEquals("search_rick_and_morty_characters", tool.name)
            assertEquals(true, tool.annotations?.readOnlyHint)
            val inputSchema = assertNotNull(tool.inputSchema)
            assertEquals(listOf("name"), inputSchema.required)
            val properties = assertNotNull(inputSchema.properties)
            assertEquals(setOf("name"), properties.keys)
            assertEquals("string", properties["name"]?.jsonObject?.get("type")?.jsonPrimitive?.content)

            val callResult = client.callTool(
                tool.name,
                buildJsonObject { put("name", "Rick & Morty") },
            )
            assertFalse(callResult.isError == true)
            assertEquals(1, upstreamRequestCount)
            assertEquals("Rick & Morty", receivedName)
            assertEquals(setOf("name"), receivedParameterNames)

            val result = assertNotNull(callResult.structuredContent)
            assertEquals("Rick & Morty", result["query"]?.jsonPrimitive?.content)
            assertEquals(1, result["page"]?.jsonPrimitive?.int)
            assertEquals(20, result["pageSizeLimit"]?.jsonPrimitive?.int)
            assertEquals(21, result["totalCount"]?.jsonPrimitive?.int)
            assertEquals(2, result["totalPages"]?.jsonPrimitive?.int)
            assertEquals(1, result["returnedCount"]?.jsonPrimitive?.int)
            assertEquals(true, result["hasMore"]?.jsonPrimitive?.boolean)
            val card = result["results"]!!.jsonArray.single().jsonObject
            assertEquals(setOf("id", "name", "status", "species", "imageUrl"), card.keys)

            val reconnectTransportClient = HttpClient(CIO)
            val reconnectClient = Client(
                clientInfo = Implementation(name = "reconnect-test-client", version = "0.1.0"),
                options = ClientOptions(),
            )
            try {
                reconnectClient.connect(
                    StreamableHttpClientTransport(
                        client = reconnectTransportClient,
                        url = "http://127.0.0.1:$mcpPort/mcp",
                    ),
                )
                val reconnectResult = reconnectClient.callTool(
                    TOOL_NAME,
                    buildJsonObject { put("name", "Rick") },
                )
                assertFalse(reconnectResult.isError == true)
                assertEquals(2, upstreamRequestCount)
            } finally {
                reconnectClient.close()
                reconnectTransportClient.close()
            }
        } finally {
            client.close()
            transportClient.close()
            mcp.close()
            upstream.stop(1_000, 5_000)
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private companion object {
        const val successPayload = """{"info":{"count":21,"pages":2,"next":"https://controlled.example/page=2"},"results":[{"id":1,"name":"Rick Sanchez","status":"Alive","species":"Human","image":"https://controlled.example/1.jpeg","episode":["must-not-leak"]}]}"""
    }
}
