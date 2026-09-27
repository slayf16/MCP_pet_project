package com.slayf16.mcppet

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RickAndMortyApiClientTest {
    @Test
    fun `uses one encoded name query without page`() = runBlocking {
        var requestCount = 0
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requestCount += 1
                    assertEquals("Morty Smith", request.url.parameters["name"])
                    assertEquals(null, request.url.parameters["page"])
                    respond(successPayload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        val response = RickAndMortyApiClient(client, "https://controlled.example/api").search("Morty Smith")
        assertEquals(1, requestCount)
        assertEquals("Morty Smith", assertIs<CharacterSearchResponse.Matches>(response).value.results.single().name)
        client.close()
    }

    @Test
    fun `maps canonical 404 to no matches`() = runBlocking {
        val client = HttpClient(MockEngine) {
            engine { addHandler { respond("""{"error":"There is nothing here"}""", HttpStatusCode.NotFound) } }
        }
        assertEquals(CharacterSearchResponse.NoMatches, RickAndMortyApiClient(client).search("missing"))
        client.close()
    }

    @Test
    fun `does not map an arbitrary 404 to no matches`() = runBlocking {
        val client = HttpClient(MockEngine) {
            engine { addHandler { respond("""{"error":"wrong route"}""", HttpStatusCode.NotFound) } }
        }
        assertIs<CharacterSearchResponse.Failure>(RickAndMortyApiClient(client).search("missing"))
        client.close()
    }

    @Test
    fun `maps malformed and failing upstream responses to safe errors`() = runBlocking {
        val malformed = HttpClient(MockEngine) {
            engine { addHandler { respond("{not-json", HttpStatusCode.OK) } }
        }
        val unavailable = HttpClient(MockEngine) {
            engine { addHandler { throw IOException("controlled connection failure") } }
        }
        val throttled = HttpClient(MockEngine) {
            engine { addHandler { respond("upstream body must not leak", HttpStatusCode.TooManyRequests) } }
        }
        val serverError = HttpClient(MockEngine) {
            engine { addHandler { respond("upstream body must not leak", HttpStatusCode.InternalServerError) } }
        }

        listOf(
            RickAndMortyApiClient(malformed).search("rick"),
            RickAndMortyApiClient(unavailable).search("rick"),
            RickAndMortyApiClient(throttled).search("rick"),
            RickAndMortyApiClient(serverError).search("rick"),
        ).forEach { response ->
            val failure = assertIs<CharacterSearchResponse.Failure>(response)
            assertTrue(!failure.message.contains("controlled connection failure"))
            assertTrue(!failure.message.contains("upstream body"))
        }
        malformed.close()
        unavailable.close()
        throttled.close()
        serverError.close()
    }

    @Test
    fun `timeout performs one request and returns a safe error`() = runBlocking {
        var requestCount = 0
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    requestCount += 1
                    delay(10_500)
                    respond(successPayload, HttpStatusCode.OK)
                }
            }
        }

        val result = RickAndMortyApiClient(client).search("slow")

        assertEquals(1, requestCount)
        assertIs<CharacterSearchResponse.Failure>(result)
        client.close()
    }

    @Test
    fun `caller cancellation propagates to controlled upstream`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            }
        }
        val request = async { RickAndMortyApiClient(client).search("cancel") }

        started.await()
        request.cancelAndJoin()
        withTimeout(1_000) { cancelled.await() }
        client.close()
    }

    private companion object {
        const val successPayload = """{"info":{"count":1,"pages":1,"next":null},"results":[{"id":2,"name":"Morty Smith","status":"Alive","species":"Human","image":"https://example.test/2.jpeg","episode":["ignored"]}]}"""
    }
}
