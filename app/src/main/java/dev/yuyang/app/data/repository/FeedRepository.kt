package dev.yuyang.app.data.repository

import dev.yuyang.app.data.api.ItemDto
import dev.yuyang.app.domain.Item
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

data class PageResult(
    val items: List<Item>,
    val hasMore: Boolean,
    val page: Int,
)

interface FeedRepository {
    suspend fun fetchPage(page: Int): Result<PageResult>
    suspend fun fetchItem(id: String): Result<Item>
}

@Singleton
class FakeFeedRepository @Inject constructor() : FeedRepository {

    override suspend fun fetchPage(page: Int): Result<PageResult> = runCatching {
        delay(600)
        val start = (page - 1) * PAGE_SIZE
        val items = (0 until PAGE_SIZE).map { i ->
            val id = (start + i + 1).toString()
            Item(
                id = id,
                title = "Item #$id",
                subtitle = "Generated locally · page=$page",
                imageUrl = "https://picsum.photos/seed/$id/200",
                updatedAt = System.currentTimeMillis() - i * 1000L,
            )
        }
        PageResult(items = items, hasMore = page < MAX_PAGES, page = page)
    }

    override suspend fun fetchItem(id: String): Result<Item> = runCatching {
        delay(300)
        Item(
            id = id,
            title = "Item #$id",
            subtitle = "Detail loaded locally",
            imageUrl = "https://picsum.photos/seed/$id/600",
            updatedAt = System.currentTimeMillis(),
        )
    }

    companion object {
        private const val PAGE_SIZE = 20
        private const val MAX_PAGES = 5
    }
}

internal fun ItemDto.toDomain(): Item = Item(
    id = id,
    title = title,
    subtitle = subtitle,
    imageUrl = imageUrl,
    updatedAt = updatedAt,
)
