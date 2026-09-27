package com.slayf16.mcppet

import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CharacterSearchToolTest {
    @Test
    fun `invalid name does not call upstream`() = runBlocking {
        val gateway = RecordingGateway(CharacterSearchResponse.NoMatches)
        val tool = CharacterSearchTool(gateway)
        val invalidArguments: List<Map<String, *>?> = listOf(
            null,
            buildJsonObject { put("name", JsonPrimitive(123)) },
            buildJsonObject { put("name", "   ") },
            buildJsonObject { put("name", "x".repeat(101)) },
        )

        invalidArguments.forEach { arguments ->
            assertTrue(tool.handle(arguments).isError == true)
        }
        assertEquals(0, gateway.calls)
    }

    @Test
    fun `canonical no match is a successful empty result`() = runBlocking {
        val tool = CharacterSearchTool(RecordingGateway(CharacterSearchResponse.NoMatches))

        val result = tool.handle(buildJsonObject { put("name", "  absent  ") })

        assertTrue(result.isError != true)
        assertTrue((result.content.single() as TextContent).text.contains("Персонажи не найдены"))
        assertEquals("absent", result.structuredContent!!["query"]?.jsonPrimitive?.content)
        assertEquals(0, result.structuredContent!!["totalCount"]?.jsonPrimitive?.int)
        assertTrue(result.structuredContent!!["results"]!!.jsonArray.isEmpty())
    }

    private class RecordingGateway(
        private val response: CharacterSearchResponse,
    ) : CharacterSearchGateway {
        var calls = 0

        override suspend fun search(name: String): CharacterSearchResponse {
            calls += 1
            return response
        }
    }
}
