package com.slayf16.mcppet

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SmokeContractTest {
    @Test
    fun `rejects discovery with an extra tool`() {
        val tool = Tool(
            name = TOOL_NAME,
            inputSchema = ToolSchema(
                properties = buildJsonObject { putJsonObject("name") { put("type", "string") } },
                required = listOf("name"),
            ),
        )
        assertThrows<IllegalStateException> { SmokeContract.validateDiscovery(listOf(tool, tool)) }
    }

    @Test
    fun `rejects result card with an extra upstream field`() {
        val result = CallToolResult(
            content = emptyList(),
            structuredContent = buildJsonObject {
                put("query", "rick")
                put("page", 1)
                put("pageSizeLimit", 20)
                put("totalCount", 1)
                put("totalPages", 1)
                put("returnedCount", 1)
                put("hasMore", false)
                putJsonArray("results") {
                    add(buildJsonObject {
                        put("id", 1)
                        put("name", "Rick")
                        put("status", "Alive")
                        put("species", "Human")
                        put("imageUrl", "https://controlled.example/1.jpg")
                        put("episode", "must-not-pass")
                    })
                }
            },
        )
        assertThrows<IllegalStateException> { SmokeContract.validateSuccess(result, "rick") }
    }
}
