package com.dpad.messaging.adapters

import android.graphics.Typeface
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.dpad.messaging.R
import com.dpad.messaging.databinding.ItemConversationBinding
import com.dpad.messaging.models.Conversation
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ConversationsAdapter(
    private val onConversationClick: (Conversation) -> Unit,
    private val onConversationLongClick: (Conversation) -> Unit
) : ListAdapter<Conversation, ConversationsAdapter.ConversationViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConversationViewHolder {
        val binding = ItemConversationBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ConversationViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ConversationViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ConversationViewHolder(
        private val binding: ItemConversationBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(conversation: Conversation) {
            val bold = if (!conversation.read) Typeface.BOLD else Typeface.NORMAL

            // Title
            binding.tvName.text = conversation.title
            binding.tvName.setTypeface(null, bold)

            // Snippet
            binding.tvSnippet.text = conversation.snippet
            binding.tvSnippet.setTypeface(null, bold)
            binding.tvSnippet.setTextColor(
                if (!conversation.read)
                    binding.root.context.getColor(R.color.conversationTitle)
                else
                    binding.root.context.getColor(R.color.conversationSnippet)
            )

            // Date
            binding.tvDate.text = formatDate(conversation.date)

            // Unread badge
            if (!conversation.read && conversation.unreadCount > 0) {
                binding.tvUnreadCount.visibility = View.VISIBLE
                binding.tvUnreadCount.text = conversation.unreadCount.coerceAtMost(99).toString()
            } else {
                binding.tvUnreadCount.visibility = View.GONE
            }

            // Pin indicator
            binding.ivPinned.visibility =
                if (conversation.pinned) View.VISIBLE else View.GONE

            // Avatar
            bindAvatar(conversation)

            // Interactions
            binding.root.setOnClickListener { onConversationClick(conversation) }
            binding.root.setOnLongClickListener {
                onConversationLongClick(conversation)
                true
            }
        }

        private fun bindAvatar(conversation: Conversation) {
            if (conversation.photoUri.isNotBlank()) {
                binding.ivAvatar.visibility = View.VISIBLE
                binding.tvAvatarLetter.visibility = View.GONE
                Glide.with(binding.ivAvatar.context)
                    .load(Uri.parse(conversation.photoUri))
                    .circleCrop()
                    .placeholder(R.drawable.ic_person)
                    .into(binding.ivAvatar)
            } else {
                binding.ivAvatar.visibility = View.GONE
                binding.tvAvatarLetter.visibility = View.VISIBLE
                val initial = conversation.title.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
                binding.tvAvatarLetter.text = initial
                binding.tvAvatarLetter.background.setTint(
                    avatarColor(conversation.phoneNumber)
                )
            }
        }

        /** Generate a stable, readable HSL colour from the phone number. */
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
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Conversation>() {
            override fun areItemsTheSame(old: Conversation, new: Conversation) =
                old.threadId == new.threadId
            override fun areContentsTheSame(old: Conversation, new: Conversation) =
                old == new
        }
    }
}
