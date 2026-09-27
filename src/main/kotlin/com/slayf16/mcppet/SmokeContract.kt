package com.slayf16.mcppet

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object SmokeContract {
    fun validateDiscovery(tools: List<Tool>) {
        check(tools.size == 1) { "Expected exactly one tool." }
        val tool = tools.single()
        check(tool.name == TOOL_NAME) { "Unexpected tool name." }
        check(tool.inputSchema.required == listOf("name")) { "Tool must require name." }
        val properties = requireNotNull(tool.inputSchema.properties) { "Tool schema properties are missing." }
        check(properties.keys == setOf("name")) { "Tool schema has unexpected properties." }
        check(properties["name"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "string") {
            "Tool name property must be a string."
        }
    }

    fun validateSuccess(result: CallToolResult, expectedQuery: String) {
        check(result.isError != true) { "Tool returned an error." }
        val output = requireNotNull(result.structuredContent) { "Tool did not return structured content." }
        check(output.keys == RESULT_FIELDS) { "Unexpected result fields." }
        check(output.string("query") == expectedQuery.trim()) { "Result query differs from request." }
        check(output.int("page") == 1) { "Result page must be 1." }
        check(output.int("pageSizeLimit") == 20) { "Result pageSizeLimit must be 20." }
        val returnedCount = output.int("returnedCount")
        val results = output["results"]?.jsonArray ?: error("Result cards are missing.")
        check(results.size <= 20 && results.size == returnedCount) { "Result count is not bounded or consistent." }
        check(output.int("totalCount") >= returnedCount) { "Result totalCount is inconsistent." }
        check(output.int("totalPages") >= 0) { "Result totalPages is invalid." }
        output.boolean("hasMore")
        results.forEach { card ->
            val fields = card.jsonObject
            check(fields.keys == CARD_FIELDS) { "Card has unexpected fields." }
            fields.int("id")
            fields.string("name")
            fields.string("status")
            fields.string("species")
            fields.string("imageUrl")
        }
    }

    private fun JsonObject.string(name: String): String =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: error("$name must be a string.")

    private fun JsonObject.int(name: String): Int = this[name]?.jsonPrimitive?.int
        ?: error("$name must be an integer.")

    private fun JsonObject.boolean(name: String): Boolean = this[name]?.jsonPrimitive?.booleanOrNull
        ?: error("$name must be a boolean.")

    private val RESULT_FIELDS = setOf("query", "page", "pageSizeLimit", "totalCount", "totalPages", "returnedCount", "hasMore", "results")
    private val CARD_FIELDS = setOf("id", "name", "status", "species", "imageUrl")
}
