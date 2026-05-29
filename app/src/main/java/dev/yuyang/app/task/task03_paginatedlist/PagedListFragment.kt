package dev.yuyang.app.task.task03_paginatedlist

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.launch

/**
 * ============================================================================
 * Task 3 的 UI 层：下拉刷新 + RecyclerView + 无限滚动。
 * ============================================================================
 * 为了让这个 task 完全自包含（不依赖 XML 布局、不依赖 ViewBinding 生成的类），
 * 这里的界面全部用代码搭。真实项目会写 XML 布局 + ViewBinding，但逻辑一样。
 *
 * 数据全部来自 PagedListViewModel，本类只做两件事：
 *   1. 观察 viewModel.state → 渲染界面
 *   2. 把用户操作（下拉、滚到底）转成 viewModel 的方法调用
 * ============================================================================
 */
class PagedListFragment : Fragment() {

    // by viewModels()：Fragment KTX 提供的委托，自动按 Fragment 生命周期创建/复用 ViewModel。
    // 配置变更（旋转屏幕）时 ViewModel 不重建，数据不丢。
    private val viewModel: PagedListViewModel by viewModels()
    private lateinit var adapter: PagedAdapter
    private lateinit var swipe: SwipeRefreshLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        adapter = PagedAdapter()

        // RecyclerView：列表控件。
        val recycler = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)   // 竖直线性排列
            adapter = this@PagedListFragment.adapter
            // 滚动监听：实现“无限滚动”。
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    val lm = rv.layoutManager as LinearLayoutManager
                    val visible = lm.childCount                       // 当前可见的 item 数
                    val total = lm.itemCount                          // 总 item 数
                    val firstVisible = lm.findFirstVisibleItemPosition() // 第一个可见 item 的下标
                    // 当“可见的最后一个 item”进入倒数第 5 个范围时，提前加载下一页，
                    // 让用户滚到底之前数据就准备好，体验更顺。
                    if (visible + firstVisible >= total - 5 && firstVisible >= 0) {
                        viewModel.loadMore()
                    }
                }
            })
        }

        // SwipeRefreshLayout：把 RecyclerView 包起来，提供“下拉刷新”手势。
        swipe = SwipeRefreshLayout(context).apply {
            addView(recycler, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setOnRefreshListener { viewModel.refresh() }  // 下拉 → 触发刷新
        }
        return swipe
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 【观察状态的标准现代写法】
        // repeatOnLifecycle(STARTED)：只在界面可见（STARTED~STOPPED）时收集，
        // 界面进后台自动停止收集、回到前台自动恢复，避免后台白白消耗 + 内存泄漏。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    swipe.isRefreshing = state.isRefreshing
                    adapter.submitList(state.items)  // ListAdapter 会自动做差量更新
                }
            }
        }
        viewModel.loadFirstPageIfNeeded()  // 触发首次加载
    }

    /**
     * RecyclerView 适配器。
     * 用 ListAdapter + DiffUtil：只更新真正变化的 item（而不是 notifyDataSetChanged 全刷），
     * 性能好、还自带动画。这是面试官想看到的现代写法。
     */
    private class PagedAdapter : ListAdapter<PagedItem, PagedAdapter.VH>(DIFF) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            // 每个 item 就是一个带内边距的 TextView（代码创建，省去 item XML）。
            val tv = TextView(parent.context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                setPadding(48, 48, 48, 48)
                gravity = Gravity.CENTER_VERTICAL
            }
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            (holder.itemView as TextView).text = getItem(position).title
        }

        class VH(view: View) : RecyclerView.ViewHolder(view)

        companion object {
            // DiffUtil 回调：告诉 ListAdapter 怎么判断“是不是同一个 item”和“内容有没有变”。
            private val DIFF = object : DiffUtil.ItemCallback<PagedItem>() {
                override fun areItemsTheSame(old: PagedItem, new: PagedItem) = old.id == new.id
                override fun areContentsTheSame(old: PagedItem, new: PagedItem) = old == new
            }
        }
    }
}
