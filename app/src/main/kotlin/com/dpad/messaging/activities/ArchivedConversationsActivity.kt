package com.dpad.messaging.activities

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.dpad.messaging.App
import com.dpad.messaging.adapters.ConversationsAdapter
import com.dpad.messaging.databinding.ActivityArchivedBinding
import com.dpad.messaging.extensions.getConversationsFromTelephony
import com.dpad.messaging.models.Conversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ArchivedConversationsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityArchivedBinding
    private lateinit var adapter: ConversationsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArchivedBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        adapter = ConversationsAdapter(
            onConversationClick = { openThread(it) },
            onConversationLongClick = { showUnarchiveMenu(it) },
            onConversationMenuClick = { showUnarchiveMenu(it) }
        )
        binding.rvConversations.apply {
            this.adapter = this@ArchivedConversationsActivity.adapter
            layoutManager = LinearLayoutManager(this@ArchivedConversationsActivity)
            onTopEdgeReached = { binding.btnBack.requestFocus() }
        }

        loadArchivedConversations()
    }

    private fun openThread(conversation: Conversation) {
        val intent = Intent(this, ThreadActivity::class.java).apply {
            putExtra(ThreadActivity.EXTRA_THREAD_ID, conversation.threadId)
            putExtra(ThreadActivity.EXTRA_THREAD_TITLE, conversation.title)
            putExtra(ThreadActivity.EXTRA_PHONE_NUMBER, conversation.phoneNumber)
            if (conversation.participants.isNotBlank()) {
                putExtra(ThreadActivity.EXTRA_PARTICIPANTS, conversation.participants)
            }
        }
        startActivity(intent)
    }

    private fun loadArchivedConversations() {
        lifecycleScope.launch {
            // Phase 2: filter archived flag from Room; for Phase 1 show empty state
            val archived = withContext(Dispatchers.IO) {
                App.get().database.conversationsDao().getArchivedConversations()
            }
            adapter.submitList(archived)
            binding.tvEmpty.visibility = if (archived.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showUnarchiveMenu(conversation: Conversation) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(conversation.title)
            .setItems(arrayOf(getString(com.dpad.messaging.R.string.unarchive))) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    App.get().database.conversationsDao()
                        .setArchived(conversation.threadId, false)
                }
                loadArchivedConversations()
            }
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_STAR) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }
}
