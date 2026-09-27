package com.slayf16.mcppet

import kotlinx.serialization.Serializable

@Serializable data class RickAndMortyResponse(val info: RickAndMortyInfo, val results: List<RickAndMortyCharacter>)
@Serializable data class RickAndMortyInfo(val count: Int, val pages: Int, val next: String? = null)
@Serializable data class RickAndMortyCharacter(val id: Int, val name: String, val status: String, val species: String, val image: String)
@Serializable data class CharacterCard(val id: Int, val name: String, val status: String, val species: String, val imageUrl: String)
@Serializable data class CharacterSearchResult(
    val query: String,
    val page: Int = 1,
    val pageSizeLimit: Int = 20,
    val totalCount: Int,
    val totalPages: Int,
    val returnedCount: Int,
    val hasMore: Boolean,
    val results: List<CharacterCard>,
)

sealed interface CharacterSearchResponse {
    data class Matches(val value: RickAndMortyResponse) : CharacterSearchResponse
    data object NoMatches : CharacterSearchResponse
    data class Failure(val message: String) : CharacterSearchResponse
}

interface CharacterSearchGateway {
    suspend fun search(name: String): CharacterSearchResponse
}
