package com.slayf16.mcppet

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.time.Duration.Companion.seconds

class RickAndMortyApiClient(
    private val httpClient: HttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : CharacterSearchGateway {
    override suspend fun search(name: String): CharacterSearchResponse = try {
        withTimeout(10.seconds) {
            val response = httpClient.get("$baseUrl/character/") { parameter("name", name) }
            when (response.status) {
                HttpStatusCode.OK -> CharacterSearchResponse.Matches(json.decodeFromString(response.body<String>()))
                HttpStatusCode.NotFound -> {
                    val body = response.body<String>()
                    if (isCanonicalNoMatch(body)) CharacterSearchResponse.NoMatches
                    else CharacterSearchResponse.Failure("Upstream returned an unexpected not-found response.")
                }
                else -> CharacterSearchResponse.Failure("Upstream is temporarily unavailable (HTTP ${response.status.value}).")
            }
        }
    } catch (_: TimeoutCancellationException) {
        CharacterSearchResponse.Failure("The character service did not respond within 10 seconds.")
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: SerializationException) {
        CharacterSearchResponse.Failure("The character service returned an invalid response.")
    } catch (_: Exception) {
        CharacterSearchResponse.Failure("The character service is unavailable.")
    }

    private fun isCanonicalNoMatch(body: String): Boolean = runCatching {
        json.parseToJsonElement(body).jsonObject["error"]?.toString()?.trim('"') == "There is nothing here"
    }.getOrDefault(false)

    companion object { const val DEFAULT_BASE_URL = "https://rickandmortyapi.com/api" }
}
