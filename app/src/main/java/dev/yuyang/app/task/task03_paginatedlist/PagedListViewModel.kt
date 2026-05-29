package dev.yuyang.app.task.task03_paginatedlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ============================================================================
 * Task 3：列表 + 网络 + 分页（这是 App 里最常见的界面）
 * ============================================================================
 *
 * 【面试场景】实现一个分页列表，要求：
 *   1. 首次加载有 loading 状态
 *   2. 滚到底自动加载下一页（无限滚动）
 *   3. 下拉刷新
 *   4. 出错能显示错误并重试
 *   5. 屏幕旋转（配置变更）数据不丢
 *
 * 【架构】MVVM + 单向数据流（UDF）：
 *   - 所有状态都放在 ViewModel 的一个 StateFlow 里（State 是唯一真相源）
 *   - 配置变更时 ViewModel 不重建，所以数据天然不丢
 *   - UI（见 PagedListFragment）只负责“观察 state 渲染” + “把用户操作转成方法调用”
 *
 * 本文件是“逻辑层”；UI 在 PagedListFragment.kt 里用纯代码搭。
 * ============================================================================
 */

/** 列表项数据模型。 */
data class PagedItem(val id: String, val title: String)

/** 一页的返回结果：这一页的数据 + 是否还有下一页。 */
data class PageResult(val items: List<PagedItem>, val hasMore: Boolean)

/**
 * 模拟的分页数据源：共 5 页、每页 20 条。
 * 故意让第 2 页抛异常，用来演示“错误处理 + 重试”。
 */
class PagedRepository {
    suspend fun fetchPage(page: Int, limit: Int = 20): Result<PageResult> = runCatching {
        delay(600) // 模拟网络耗时
        if (page == 2) throw RuntimeException("Simulated error on page 2 — tap retry")
        val totalPages = 5
        val items = (1..limit).map {
            val n = (page - 1) * limit + it
            PagedItem(id = "id_$n", title = "Item #$n")
        }
        PageResult(items = items, hasMore = page < totalPages)
    }
}

/**
 * 界面状态：把所有“界面上能看到的东西”都集中在这一个不可变对象里。
 * 用 data class + copy() 做不可变更新，是 UDF 的标准做法。
 */
data class PagedUiState(
    val items: List<PagedItem> = emptyList(), // 当前已加载的全部数据
    val isInitialLoading: Boolean = false,    // 首屏 loading（整页转圈）
    val isRefreshing: Boolean = false,        // 下拉刷新中（顶部转圈）
    val isAppending: Boolean = false,         // 加载下一页中（底部转圈）
    val error: String? = null,                // 错误信息，null 表示没错
    val page: Int = 0,                        // 当前已加载到第几页
    val hasMore: Boolean = true,              // 是否还有下一页
)

/**
 * ViewModel：持有状态、处理三种加载（首次/刷新/下一页）。
 * @param repo 数据源，默认值方便直接 new；生产用 Hilt 注入。
 */
class PagedListViewModel(
    private val repo: PagedRepository = PagedRepository(),
) : ViewModel() {

    // _state 是可变的、私有的；对外只暴露只读的 StateFlow，外部不能乱改状态。
    private val _state = MutableStateFlow(PagedUiState())
    val state: StateFlow<PagedUiState> = _state.asStateFlow()

    /** 首次进入界面时调用：只有当前是空的才加载，避免配置变更后重复加载。 */
    fun loadFirstPageIfNeeded() {
        if (_state.value.items.isEmpty() && !_state.value.isInitialLoading) load(reset = true)
    }

    /** 下拉刷新：reset=true 表示从第 1 页重新来，isRefresh=true 走顶部转圈。 */
    fun refresh() = load(reset = true, isRefresh = true)

    /**
     * 加载下一页（无限滚动触发）。
     * 多重防护：正在首加载/正在追加/正在刷新/没有更多了，都不重复触发。
     */
    fun loadMore() {
        val s = _state.value
        if (s.isInitialLoading || s.isAppending || s.isRefreshing || !s.hasMore) return
        load(reset = false)
    }

    /** 错误后点“重试”：列表为空就当首加载，否则当加载下一页。 */
    fun retry() = load(reset = _state.value.items.isEmpty())

    /**
     * 统一的加载逻辑。
     * @param reset     true=从第 1 页开始并替换数据；false=加载下一页并追加数据
     * @param isRefresh 是否是下拉刷新（影响显示哪个转圈）
     */
    private fun load(reset: Boolean, isRefresh: Boolean = false) {
        val targetPage = if (reset) 1 else _state.value.page + 1
        // 先把“加载中”状态发出去，清掉旧错误。update{} 是基于旧值原子地算新值。
        _state.update {
            it.copy(
                isInitialLoading = reset && !isRefresh,
                isRefreshing = isRefresh,
                isAppending = !reset,
                error = null,
            )
        }
        // viewModelScope：绑定 ViewModel 生命周期的协程作用域，
        // ViewModel 销毁时自动取消，不会泄漏协程。
        viewModelScope.launch {
            repo.fetchPage(targetPage).fold(
                onSuccess = { result ->
                    _state.update { s ->
                        s.copy(
                            // reset 替换、否则在原数据后面追加
                            items = if (reset) result.items else s.items + result.items,
                            page = targetPage,
                            hasMore = result.hasMore,
                            isInitialLoading = false,
                            isRefreshing = false,
                            isAppending = false,
                        )
                    }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            isInitialLoading = false,
                            isRefreshing = false,
                            isAppending = false,
                            error = e.message ?: "Unknown error",
                        )
                    }
                },
            )
        }
    }
}
