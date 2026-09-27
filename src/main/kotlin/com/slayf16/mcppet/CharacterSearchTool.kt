package com.slayf16.mcppet

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

const val TOOL_NAME = "search_rick_and_morty_characters"
private val outputJson = Json { encodeDefaults = true }

fun createMcpServer(apiClient: CharacterSearchGateway): Server {
    val server = Server(
        serverInfo = Implementation(name = "rick-and-morty-search", version = "0.1.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
            ),
        ),
    )
    CharacterSearchTool(apiClient).register(server)
    return server
}

class CharacterSearchTool(private val apiClient: CharacterSearchGateway) {
    fun register(server: Server) {
        server.addTool(
        name = TOOL_NAME,
        description = "Searches Rick and Morty characters by name. Returns only the first page, with at most 20 results.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("name") { put("type", "string") }
            },
            required = listOf("name"),
        ),
        toolAnnotations = ToolAnnotations(readOnlyHint = true),
        ) { request -> handle(request.arguments) }
    }

    suspend fun handle(arguments: Map<String, *>?): CallToolResult {
        val name = (arguments?.get("name") as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
        if (name.isNullOrEmpty() || name.length > 100) {
            return errorResult("Provide name as a non-blank string of 1 to 100 characters.")
        }

        return when (val response = apiClient.search(name)) {
            is CharacterSearchResponse.Matches -> successResult(name, response.value)
            CharacterSearchResponse.NoMatches -> noMatchResult(name)
            is CharacterSearchResponse.Failure -> errorResult(response.message)
        }
    }
}

private fun successResult(query: String, upstream: RickAndMortyResponse): CallToolResult {
    val result = CharacterSearchResult(
        query = query,
        totalCount = upstream.info.count,
        totalPages = upstream.info.pages,
        returnedCount = upstream.results.size.coerceAtMost(20),
        hasMore = upstream.info.next != null,
        results = upstream.results.take(20).map {
            CharacterCard(it.id, it.name, it.status, it.species, it.image)
        },
    )
    val encoded = outputJson.encodeToString(result)
    return CallToolResult(
        content = listOf(TextContent(encoded)),
        structuredContent = outputJson.parseToJsonElement(encoded).jsonObject,
    )
}

private fun noMatchResult(query: String): CallToolResult {
    val result = CharacterSearchResult(
        query = query,
        totalCount = 0,
        totalPages = 0,
        returnedCount = 0,
        hasMore = false,
        results = emptyList(),
    )
    val encoded = outputJson.encodeToString(result)
    return CallToolResult(
        content = listOf(TextContent("Персонажи не найдены. $encoded")),
        structuredContent = outputJson.parseToJsonElement(encoded).jsonObject,
    )
}

private fun errorResult(message: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(message)), isError = true)
