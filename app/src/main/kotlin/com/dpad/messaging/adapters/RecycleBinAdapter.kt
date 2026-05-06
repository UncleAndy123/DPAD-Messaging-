package com.dpad.messaging.adapters

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dpad.messaging.R
import com.dpad.messaging.databinding.ItemConversationBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Lightweight data class carrying the full message data needed to display
 * a recycle-bin row (fetched from the Telephony CP by ID at load time).
 */
data class RecycledItem(
    val id: Long,
    val senderName: String,
    val phoneNumber: String,
    val body: String,
    val date: Long
)

/**
 * Adapter for RecycleBinActivity — reuses item_conversation.xml.
 * Unread badge and pin indicator are always hidden.
 */
class RecycleBinAdapter(
    private val onItemClick: (RecycledItem) -> Unit,
    private val onItemLongClick: (RecycledItem) -> Unit
) : ListAdapter<RecycledItem, RecycleBinAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemConversationBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemConversationBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RecycledItem) {
            binding.tvName.text = item.senderName
            binding.tvName.setTypeface(null, Typeface.NORMAL)

            binding.tvSnippet.text = item.body
            binding.tvSnippet.setTypeface(null, Typeface.NORMAL)
            binding.tvSnippet.setTextColor(
                binding.root.context.getColor(R.color.conversationSnippet)
            )

            binding.tvDate.text = formatDate(item.date)

            // No unread badge or pin in recycle bin
            binding.tvUnreadCount.visibility = View.GONE
            binding.ivPinned.visibility = View.GONE

            // Avatar — letter-only (no photo lookup for deleted messages)
            binding.ivAvatar.visibility = View.GONE
            binding.tvAvatarLetter.visibility = View.VISIBLE
            val initial = item.senderName.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
            binding.tvAvatarLetter.text = initial
            binding.tvAvatarLetter.background.setTint(avatarColor(item.phoneNumber))

            binding.root.setOnClickListener { onItemClick(item) }
            binding.root.setOnLongClickListener { onItemLongClick(item); true }
        }

        private fun avatarColor(seed: String): Int {
            val hue = (Math.abs(seed.hashCode()) % 360).toFloat()
            return ColorUtils.HSLToColor(floatArrayOf(hue, 0.55f, 0.35f))
        }

        private fun formatDate(timestamp: Long): String {
            if (timestamp == 0L) return ""
            val now = Calendar.getInstance()
            val msg = Calendar.getInstance().apply { timeInMillis = timestamp }
            return when {
                isSameDay(now, msg) ->
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
                diffDays(now, msg) < 7 ->
                    SimpleDateFormat("EEE", Locale.getDefault()).format(Date(timestamp))
                else ->
                    SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(Date(timestamp))
            }
        }

        private fun isSameDay(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

        private fun diffDays(now: Calendar, msg: Calendar) =
            ((now.timeInMillis - msg.timeInMillis) / (24 * 60 * 60 * 1000L)).toInt()
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<RecycledItem>() {
            override fun areItemsTheSame(old: RecycledItem, new: RecycledItem) =
                old.id == new.id
            override fun areContentsTheSame(old: RecycledItem, new: RecycledItem) =
                old == new
        }
    }
}
