package com.slayf16.mcppet

import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.modelcontextprotocol.kotlin.sdk.server.DnsRebindingProtection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

private const val MCP_SESSION_ID_HEADER = "mcp-session-id"

class McpServer(
    config: ServerConfig,
    private val upstreamClient: HttpClient,
    upstreamBaseUrl: String = RickAndMortyApiClient.DEFAULT_BASE_URL,
) : AutoCloseable {
    private val sessions = BoundedSessionRegistry(
        config = config,
        createServer = { createMcpServer(RickAndMortyApiClient(upstreamClient, upstreamBaseUrl)) },
    )
    private val engine = embeddedServer(Netty, host = config.bindHost, port = config.port) {
        installBoundedMcp(config, sessions)
    }

    fun start(wait: Boolean) = engine.start(wait = wait)

    fun stop(gracePeriodMillis: Long = 1_000, timeoutMillis: Long = 5_000) {
        runBlocking { sessions.close() }
        engine.stop(gracePeriodMillis, timeoutMillis)
        upstreamClient.close()
    }

    internal fun activeSessionCount(): Int = sessions.activeCount()

    override fun close() = stop()
}

private fun Application.installBoundedMcp(config: ServerConfig, sessions: BoundedSessionRegistry) {
    install(ContentNegotiation) { json(McpJson) }
    install(SSE)
    routing {
        route("/mcp") {
            install(DnsRebindingProtection) {
                allowedHosts = config.allowedHosts
                allowedOrigins = config.allowedOrigins
            }
            sse {
                val lease = sessions.existing(call) ?: return@sse
                try {
                    lease.transport.handleRequest(this, call)
                } finally {
                    lease.release()
                }
            }
            post {
                val lease = sessions.forPost(call) ?: return@post
                try {
                    lease.transport.handleRequest(null, call)
                } finally {
                    lease.release()
                    sessions.removeUninitialized(lease)
                }
            }
            delete {
                val lease = sessions.existing(call) ?: return@delete
                try {
                    lease.transport.handleRequest(null, call)
                } finally {
                    lease.release()
                }
            }
        }
    }
}
private class BoundedSessionRegistry(
    private val config: ServerConfig,
    private val createServer: () -> Server,
) {
    private val entries = ConcurrentHashMap<String, ManagedSession>()
    private val permits = Semaphore(config.maxSessions, true)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cleanupPeriodMillis = config.sessionIdleTimeout.toMillis().coerceIn(100L, 1_000L)

    init {
        scope.launch {
            while (isActive) {
                delay(cleanupPeriodMillis)
                val deadline = System.nanoTime() - config.sessionIdleTimeout.toNanos()
                entries.values.forEach { entry ->
                    if (entry.markExpiredIfIdle(deadline)) closeMarkedEntry(entry)
                }
            }
        }
    }

    suspend fun forPost(call: ApplicationCall): SessionLease? {
        val sessionId = call.request.headers[MCP_SESSION_ID_HEADER]
        if (!sessionId.isNullOrBlank()) return existing(call)
        if (!permits.tryAcquire()) {
            call.respondText("MCP session limit reached.", status = HttpStatusCode.ServiceUnavailable)
            return null
        }
        val entry = ManagedSession(
            StreamableHttpServerTransport(StreamableHttpServerTransport.Configuration(enableJsonResponse = true)),
            createServer(),
        )
        entry.transport.setOnSessionInitialized { id ->
            entry.sessionId = id
            entry.touch()
            entries[id] = entry
        }
        entry.transport.setOnSessionClosed { scope.launch { releaseFromCallback(entry) } }
        entry.server.onClose { scope.launch { releaseFromCallback(entry) } }
        entry.server.createSession(entry.transport)
        return checkNotNull(entry.acquireLease())
    }

    suspend fun existing(call: ApplicationCall): SessionLease? {
        val sessionId = call.request.headers[MCP_SESSION_ID_HEADER]
        val lease = sessionId?.let(entries::get)?.acquireLease()
        if (lease == null) {
            call.respondText("MCP session not found.", status = HttpStatusCode.NotFound)
            return null
        }
        return lease
    }

    suspend fun removeUninitialized(lease: SessionLease) {
        if (lease.entry.sessionId == null && lease.entry.markReleased()) closeMarkedEntry(lease.entry)
    }

    suspend fun close() {
        entries.values.toList().forEach { entry ->
            if (entry.markReleased()) closeMarkedEntry(entry)
        }
        scope.cancel()
    }

    fun activeCount(): Int = entries.size

    private suspend fun releaseFromCallback(entry: ManagedSession) {
        if (entry.markReleased()) releasePermit(entry)
    }

    private suspend fun closeMarkedEntry(entry: ManagedSession) {
        releasePermit(entry)
        entry.transport.close()
    }

    private fun releasePermit(entry: ManagedSession) {
        entry.sessionId?.let { entries.remove(it, entry) }
        permits.release()
    }
}

private class SessionLease(internal val entry: ManagedSession) {
    val transport: StreamableHttpServerTransport get() = entry.transport
    fun release() = entry.releaseLease()
}
private class ManagedSession(
    val transport: StreamableHttpServerTransport,
    val server: Server,
) {
    private val lock = Any()
    private var activeLeases = 0
    private var lastReleasedNanos = System.nanoTime()
    private var released = false

    @Volatile var sessionId: String? = null

    fun acquireLease(): SessionLease? = synchronized(lock) {
        if (released) null else {
            activeLeases += 1
            SessionLease(this)
        }
    }

    fun releaseLease() = synchronized(lock) {
        check(activeLeases > 0) { "Session lease released twice." }
        activeLeases -= 1
        if (activeLeases == 0) lastReleasedNanos = System.nanoTime()
    }

    fun touch() = synchronized(lock) {
        if (activeLeases == 0) lastReleasedNanos = System.nanoTime()
    }

    fun markExpiredIfIdle(deadline: Long): Boolean = synchronized(lock) {
        if (released || activeLeases != 0 || lastReleasedNanos >= deadline) false
        else {
            released = true
            true
        }
    }

    fun markReleased(): Boolean = synchronized(lock) {
        if (released) false else {
            released = true
            true
        }
    }
}

