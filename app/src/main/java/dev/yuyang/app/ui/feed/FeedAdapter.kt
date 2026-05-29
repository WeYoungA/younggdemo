package dev.yuyang.app.ui.feed

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import dev.yuyang.app.databinding.ItemFeedBinding
import dev.yuyang.app.domain.Item

class FeedAdapter(
    private val onClick: (Item) -> Unit,
) : ListAdapter<Item, FeedAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemFeedBinding.inflate(
            LayoutInflater.from(parent.context), parent, false,
        )
        return VH(binding, onClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(
        private val binding: ItemFeedBinding,
        private val onClick: (Item) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: Item) {
            binding.item = item
            // Image is loaded in code (not via a DataBinding adapter): DataBinding
            // has no KSP processor, so Kotlin @BindingAdapter methods aren't
            // discovered in a pure-KSP build. Glide-in-onBind is the common
            // real-world pattern anyway.
            val url = item.imageUrl
            if (url.isNullOrBlank()) {
                binding.thumb.setImageDrawable(null)
            } else {
                Glide.with(binding.thumb)
                    .load(url)
                    .apply(RequestOptions().transform(RoundedCorners(16)))
                    .into(binding.thumb)
            }
            binding.root.setOnClickListener { onClick(item) }
            binding.executePendingBindings()
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Item>() {
            override fun areItemsTheSame(old: Item, new: Item): Boolean = old.id == new.id
            override fun areContentsTheSame(old: Item, new: Item): Boolean = old == new
        }
    }
}
