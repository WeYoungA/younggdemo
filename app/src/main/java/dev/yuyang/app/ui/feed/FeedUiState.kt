package dev.yuyang.app.ui.feed

import dev.yuyang.app.domain.Item

data class FeedUiState(
    val items: List<Item> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val page: Int = 0,
    val error: String? = null,
)

sealed interface FeedEvent {
    data object Load : FeedEvent
    data object Refresh : FeedEvent
    data object LoadMore : FeedEvent
    data class ItemClicked(val id: String) : FeedEvent
    data object DismissError : FeedEvent
}

sealed interface FeedEffect {
    data class NavigateToDetail(val id: String) : FeedEffect
    data class ShowToast(val message: String) : FeedEffect
}
