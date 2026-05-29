package dev.yuyang.app.domain

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Item(
    val id: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String,
    val updatedAt: Long,
) : Parcelable
