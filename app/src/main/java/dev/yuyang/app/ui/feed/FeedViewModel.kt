package dev.yuyang.app.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.yuyang.app.data.repository.FeedRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val repository: FeedRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(FeedUiState(isLoading = true))
    val state: StateFlow<FeedUiState> = _state.asStateFlow()

    private val _effects = Channel<FeedEffect>(Channel.BUFFERED)
    val effects: Flow<FeedEffect> = _effects.receiveAsFlow()

    init {
        onEvent(FeedEvent.Load)
    }

    fun onEvent(event: FeedEvent) {
        when (event) {
            FeedEvent.Load -> load(reset = true)
            FeedEvent.Refresh -> load(reset = true, isRefresh = true)
            FeedEvent.LoadMore -> {
                val s = _state.value
                if (!s.isLoading && !s.isLoadingMore && s.hasMore) {
                    load(reset = false)
                }
            }
            is FeedEvent.ItemClicked -> emit(FeedEffect.NavigateToDetail(event.id))
            FeedEvent.DismissError -> _state.update { it.copy(error = null) }
        }
    }

    private fun load(reset: Boolean, isRefresh: Boolean = false) {
        viewModelScope.launch {
            val targetPage = if (reset) 1 else _state.value.page + 1
            _state.update {
                it.copy(
                    isLoading = reset && !isRefresh && it.items.isEmpty(),
                    isRefreshing = isRefresh,
                    isLoadingMore = !reset,
                    error = null,
                )
            }
            repository.fetchPage(targetPage).fold(
                onSuccess = { page ->
                    _state.update { s ->
                        s.copy(
                            items = if (reset) page.items else s.items + page.items,
                            page = page.page,
                            hasMore = page.hasMore,
                            isLoading = false,
                            isRefreshing = false,
                            isLoadingMore = false,
                            error = null,
                        )
                    }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            isLoadingMore = false,
                            error = e.message ?: "Unknown error",
                        )
                    }
                    emit(FeedEffect.ShowToast("Failed: ${e.message}"))
                },
            )
        }
    }

    private fun emit(effect: FeedEffect) {
        viewModelScope.launch { _effects.send(effect) }
    }
}
