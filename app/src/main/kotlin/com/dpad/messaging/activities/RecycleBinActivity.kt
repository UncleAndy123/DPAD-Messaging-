package com.dpad.messaging.activities

import android.content.ContentUris
import android.os.Bundle
import android.provider.Telephony
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.dpad.messaging.App
import com.dpad.messaging.R
import com.dpad.messaging.adapters.RecycleBinAdapter
import com.dpad.messaging.adapters.RecycledItem
import com.dpad.messaging.databinding.ActivityRecycleBinBinding
import com.dpad.messaging.extensions.getSmsMessageById
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecycleBinActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecycleBinBinding
    private lateinit var adapter: RecycleBinAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecycleBinBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        adapter = RecycleBinAdapter(
            onItemClick     = { /* read-only — no action on plain click */ },
            onItemLongClick = { showItemMenu(it) }
        )
        binding.rvMessages.apply {
            this.adapter = this@RecycleBinActivity.adapter
            layoutManager = LinearLayoutManager(this@RecycleBinActivity)
            onTopEdgeReached = { binding.btnBack.requestFocus() }
        }

        loadRecycleBin()
    }

    // ─── Data ─────────────────────────────────────────────────────────────────

    private fun loadRecycleBin() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                val rbMessages = App.get().database.messagesDao().getRecycleBinMessages()
                rbMessages.mapNotNull { rbMsg ->
                    val msg = getSmsMessageById(rbMsg.id, App.get().contactHelper)
                        ?: return@mapNotNull null
                    RecycledItem(
                        id          = msg.id,
                        senderName  = msg.senderName.ifBlank { msg.address },
                        phoneNumber = msg.address,
                        body        = msg.body,
                        date        = msg.date
                    )
                }
            }
            adapter.submitList(items)
            binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    // ─── Context menu ─────────────────────────────────────────────────────────

    private fun showItemMenu(item: RecycledItem) {
        AlertDialog.Builder(this)
            .setTitle(item.senderName)
            .setItems(arrayOf(getString(R.string.restore), getString(R.string.delete))) { _, which ->
                when (which) {
                    0 -> restoreItem(item)
                    1 -> permanentlyDeleteItem(item)
                }
            }
            .show()
    }

    /** Restore: remove from the recycle-bin Room table only — message stays in Telephony CP. */
    private fun restoreItem(item: RecycledItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            App.get().database.messagesDao().removeFromRecycleBin(item.id)
            withContext(Dispatchers.Main) { loadRecycleBin() }
        }
    }

    /** Permanently delete: remove from Room recycle-bin table AND from the Telephony CP. */
    private fun permanentlyDeleteItem(item: RecycledItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            App.get().database.messagesDao().removeFromRecycleBin(item.id)
            try {
                contentResolver.delete(
                    ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, item.id),
                    null, null
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            withContext(Dispatchers.Main) { loadRecycleBin() }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_STAR) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }
}
