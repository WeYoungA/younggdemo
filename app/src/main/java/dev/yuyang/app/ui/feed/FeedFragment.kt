package dev.yuyang.app.ui.feed

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.yuyang.app.R
import dev.yuyang.app.databinding.FragmentFeedBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class FeedFragment : Fragment() {

    private var _binding: FragmentFeedBinding? = null
    private val binding get() = _binding!!

    private val viewModel: FeedViewModel by viewModels()

    private val adapter by lazy {
        FeedAdapter { item -> viewModel.onEvent(FeedEvent.ItemClicked(item.id)) }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentFeedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupList()
        setupRefresh()
        setupRetry()
        observeState()
        observeEffects()
    }

    private fun setupList() {
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter
        binding.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                val visible = lm.childCount
                val total = lm.itemCount
                val first = lm.findFirstVisibleItemPosition()
                if (dy > 0 && visible + first >= total - PREFETCH_THRESHOLD) {
                    viewModel.onEvent(FeedEvent.LoadMore)
                }
            }
        })
    }

    private fun setupRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.onEvent(FeedEvent.Refresh)
        }
    }

    private fun setupRetry() {
        binding.retryButton.setOnClickListener {
            viewModel.onEvent(FeedEvent.Load)
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    binding.swipeRefresh.isRefreshing = state.isRefreshing
                    binding.progress.visibility =
                        if (state.isLoading) View.VISIBLE else View.GONE

                    val showError = state.error != null && state.items.isEmpty()
                    binding.errorGroup.visibility =
                        if (showError) View.VISIBLE else View.GONE
                    binding.errorText.text = state.error ?: getString(R.string.feed_error)

                    val showEmpty = !state.isLoading && state.items.isEmpty() && state.error == null
                    binding.emptyText.visibility = if (showEmpty) View.VISIBLE else View.GONE

                    adapter.submitList(state.items)
                }
            }
        }
    }

    private fun observeEffects() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effects.collect { effect ->
                    when (effect) {
                        is FeedEffect.NavigateToDetail -> {
                            val action = FeedFragmentDirections
                                .actionFeedToDetail(itemId = effect.id)
                            findNavController().navigate(action)
                        }
                        is FeedEffect.ShowToast -> {
                            Toast.makeText(requireContext(), effect.message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        binding.recycler.adapter = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val PREFETCH_THRESHOLD = 4
    }
}
