package dev.yuyang.app.data.api

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {

    @GET("items")
    suspend fun getItems(
        @Query("page") page: Int,
        @Query("limit") limit: Int = 20,
    ): ItemsResponse

    @GET("items/{id}")
    suspend fun getItem(@Path("id") id: String): ItemDto
}

data class ItemsResponse(
    val items: List<ItemDto>,
    val hasMore: Boolean,
    val page: Int,
)

data class ItemDto(
    val id: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String,
    val updatedAt: Long,
)
