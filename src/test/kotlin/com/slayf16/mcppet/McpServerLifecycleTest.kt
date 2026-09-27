package com.slayf16.mcppet

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.http.HttpStatusCode
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CancelledNotification
import io.modelcontextprotocol.kotlin.sdk.types.CancelledNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.ServerSocket
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class McpServerLifecycleTest {
    @Test
    fun `fails to start when configured port is occupied`() {
        val port = freePort()
        ServerSocket(port).use {
            val upstreamClient = HttpClient(CIO)
            val server = McpServer(ServerConfig("127.0.0.1", port), upstreamClient)
            try {
                assertThrows<Exception> { server.start(wait = false) }
            } finally {
                server.close()
            }
        }
    }

    @Test
    fun `shutdown closes the shared upstream client and protocol sessions`() = runBlocking {
        val upstreamClient = HttpClient(CIO)
        val server = McpServer(ServerConfig("127.0.0.1", freePort()), upstreamClient)
        try {
            server.start(wait = false)
        } finally {
            server.close()
        }
        val clientJob = upstreamClient.coroutineContext[Job]!!
        withTimeout(1_000) { clientJob.join() }
        assertFalse(clientJob.isActive)
        assertTrue(clientJob.isCompleted)
        assertEquals(0, server.activeSessionCount())
    }

    @Test
    fun `session registry rejects excess initialize and removes abandoned session after TTL`() = runBlocking {
        val port = freePort()
        val server = McpServer(
            ServerConfig("127.0.0.1", port, maxSessions = 1, sessionIdleTimeout = Duration.ofMillis(200)),
            HttpClient(CIO),
        )
        val client = HttpClient(CIO) { expectSuccess = false }
        try {
            server.start(wait = false)
            val first = initialize(client, port)
            assertEquals(HttpStatusCode.OK, first.status)
            withTimeout(1_000) { while (server.activeSessionCount() != 1) delay(20) }
            assertEquals(HttpStatusCode.ServiceUnavailable, initialize(client, port).status)
            withTimeout(2_000) { while (server.activeSessionCount() != 0) delay(20) }
            assertEquals(HttpStatusCode.OK, initialize(client, port).status)
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun `failed initialize and unknown session do not retain a session permit`() = runBlocking {
        val port = freePort()
        val server = McpServer(ServerConfig("127.0.0.1", port, maxSessions = 1), HttpClient(CIO))
        val client = HttpClient(CIO) { expectSuccess = false }
        try {
            server.start(wait = false)
            val malformed = client.post("http://127.0.0.1:$port/mcp") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                header(HttpHeaders.Accept, "application/json, text/event-stream")
                setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
            }
            assertNotEquals(HttpStatusCode.ServiceUnavailable, malformed.status)
            withTimeout(1_000) { while (server.activeSessionCount() != 0) delay(20) }
            assertEquals(HttpStatusCode.NotFound, initialize(client, port, sessionId = "unknown-session").status)
            assertEquals(HttpStatusCode.OK, initialize(client, port).status)
        } finally {
            client.close()
            server.close()
        }
    }
    @Test
    fun active_call_survives_ttl_and_expires_after_full_idle_interval() = runBlocking {
        val upstreamStarted = CompletableDeferred<Unit>()
        val upstreamPort = freePort()
        val upstream = embeddedServer(Netty, host = "127.0.0.1", port = upstreamPort) {
            routing {
                get("/api/character/") {
                    upstreamStarted.complete(Unit)
                    delay(5_000)
                    call.respondText(
                        """{"info":{"count":1,"pages":1,"next":null},"results":[{"id":1,"name":"Slow Rick","status":"Alive","species":"Human","image":"https://controlled.example/1.jpg"}]}""",
                        ContentType.Application.Json,
                        HttpStatusCode.OK,
                    )
                }
            }
        }.start(wait = false)
        val mcpPort = freePort()
        val server = McpServer(
            ServerConfig("127.0.0.1", mcpPort, sessionIdleTimeout = Duration.ofSeconds(1)),
            HttpClient(CIO) { expectSuccess = false },
            "http://127.0.0.1:$upstreamPort/api",
        )
        val protocolClient = HttpClient(CIO)
        val client = Client(Implementation("ttl-test-client", "1.0"), ClientOptions())
        try {
            server.start(wait = false)
            client.connect(StreamableHttpClientTransport(protocolClient, "http://127.0.0.1:$mcpPort/mcp"))
            val call = async(start = CoroutineStart.UNDISPATCHED) {
                client.callTool(TOOL_NAME, buildJsonObject { put("name", "slow") })
            }
            withTimeout(2_000) { upstreamStarted.await() }
            delay(2_000)
            assertEquals(1, server.activeSessionCount())
            assertFalse(call.await().isError == true)
            delay(500)
            assertEquals(1, server.activeSessionCount())
            withTimeout(3_000) { while (server.activeSessionCount() != 0) delay(20) }
        } finally {
            client.close()
            protocolClient.close()
            server.close()
            upstream.stop(1_000, 5_000)
        }
    }
    @Test
    fun `SDK DNS protection accepts local origin and rejects foreign Host and Origin`() = runBlocking {
        val port = freePort()
        val server = McpServer(ServerConfig("127.0.0.1", port), HttpClient(CIO))
        val client = HttpClient(CIO) { expectSuccess = false }
        try {
            server.start(wait = false)
            assertEquals(HttpStatusCode.OK, initialize(client, port, origin = "http://127.0.0.1:$port").status)
            assertEquals(HttpStatusCode.Forbidden, initialize(client, port, origin = "https://attacker.example").status)
            assertEquals(HttpStatusCode.Forbidden, initialize(client, port, host = "attacker.example").status)
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun `MCP cancelled notification cancels the real server outbound request`() = runBlocking {
        val upstreamStarted = CompletableDeferred<Unit>()
        val upstreamCancelled = CompletableDeferred<Unit>()
        val serverClient = HttpClient(MockEngine) {
            engine {
                addHandler {
                    upstreamStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        upstreamCancelled.complete(Unit)
                    }
                }
            }
        }

        val mcpPort = freePort()
        val server = McpServer(
            config = ServerConfig("127.0.0.1", mcpPort),
            upstreamClient = serverClient,
            upstreamBaseUrl = "https://controlled.example/api",
        )
        val protocolHttpClient = HttpClient(CIO)
        val client = Client(
            clientInfo = Implementation(name = "cancellation-test-client", version = "0.1.0"),
            options = ClientOptions(),
        )
        try {
            server.start(wait = false)
            client.connect(StreamableHttpClientTransport(client = protocolHttpClient, url = "http://127.0.0.1:$mcpPort/mcp"))
            val call = async(start = CoroutineStart.UNDISPATCHED) {
                client.callTool(TOOL_NAME, buildJsonObject { put("name", "slow") })
            }
            withTimeout(5_000) { upstreamStarted.await() }
            val requestId = client.responseHandlers.keys.single()
            client.notification(CancelledNotification(CancelledNotificationParams(requestId, "controlled cancellation")))
            withTimeout(5_000) { upstreamCancelled.await() }
            call.cancelAndJoin()
        } finally {
            client.close()
            protocolHttpClient.close()
            server.close()
        }
    }

    private suspend fun initialize(client: HttpClient, port: Int, host: String? = null, origin: String? = null, sessionId: String? = null) =
        client.post("http://127.0.0.1:$port/mcp") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            host?.let { header(HttpHeaders.Host, it) }
            origin?.let { header(HttpHeaders.Origin, it) }
            sessionId?.let { header("mcp-session-id", it) }
            setBody("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""")
        }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}



