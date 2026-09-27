package com.slayf16.mcppet

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class ServerConfigTest {
    @Test
    fun `uses documented defaults`() {
        val config = ServerConfig.fromEnvironment(emptyMap())
        assertEquals("127.0.0.1", config.bindHost)
        assertEquals(3000, config.port)
        assertEquals(listOf("127.0.0.1", "localhost"), config.allowedHosts)
    }

    @Test
    fun `uses default port for blank value`() {
        assertEquals(3000, ServerConfig.fromEnvironment(mapOf("MCP_PORT" to " ")).port)
    }

    @Test
    fun `rejects non numeric port instead of silently using default`() {
        val error = assertThrows<IllegalArgumentException> {
            ServerConfig.fromEnvironment(mapOf("MCP_PORT" to "30O0"))
        }
        assertEquals("MCP_PORT must be an integer.", error.message)
    }

    @Test
    fun `rejects out of range port`() {
        assertThrows<IllegalArgumentException> {
            ServerConfig.fromEnvironment(mapOf("MCP_PORT" to "70000"))
        }
    }
}
