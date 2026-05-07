package com.dpad.messaging.activities

import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Telephony
import android.view.KeyEvent
import android.view.View
import android.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.dpad.messaging.App
import com.dpad.messaging.R
import com.dpad.messaging.adapters.RecycleBinAdapter
import com.dpad.messaging.adapters.RecycledItem
import com.dpad.messaging.databinding.ActivityRecycleBinBinding
import com.dpad.messaging.helpers.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecycleBinActivity : BaseActivity() {

    private lateinit var binding: ActivityRecycleBinBinding
    private lateinit var adapter: RecycleBinAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyAccentColor(this)
        binding = ActivityRecycleBinBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        adapter = RecycleBinAdapter(
            onItemClick     = { showItemMenu(binding.root, it) },
            onItemLongClick = { showItemMenu(binding.root, it) },
            onItemMenuClick = { view, item -> showItemMenu(view, item) }
        )
        binding.rvMessages.apply {
            this.adapter = this@RecycleBinActivity.adapter
            layoutManager = LinearLayoutManager(this@RecycleBinActivity)
            onTopEdgeReached = { binding.btnBack.requestFocus() }
        }

        loadRecycleBin()
    }

    override fun onResume() {
        super.onResume()
        applyAccent()
    }

    private fun applyAccent() {
        val tint = ColorStateList.valueOf(ThemeManager.accentColor(this))
        binding.btnBack.imageTintList = tint
        binding.btnBack.backgroundTintList = tint
    }

    // ─── Data ─────────────────────────────────────────────────────────────────

    private fun loadRecycleBin() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                App.get().database.messagesDao().getRecycleBinMessages()
                    .sortedByDescending { it.deletedTs }
                    .map { rbMsg ->
                        RecycledItem(
                            id          = rbMsg.id,
                            senderName  = rbMsg.senderName.ifBlank { rbMsg.address },
                            phoneNumber = rbMsg.address,
                            body        = rbMsg.body,
                            date        = rbMsg.date
                        )
                    }
            }
            adapter.submitList(items)
            binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    // ─── Context menu ─────────────────────────────────────────────────────────

    private fun showItemMenu(anchor: View, item: RecycledItem) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 0, 0, getString(R.string.restore))
        popup.menu.add(0, 1, 1, getString(R.string.delete))
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                0 -> restoreItem(item)
                1 -> permanentlyDeleteItem(item)
            }
            true
        }
        popup.show()
    }

    /**
     * Restore: re-insert the SMS into the Telephony provider so it reappears in the thread,
     * then remove from the Room recycle-bin table.
     */
    private fun restoreItem(item: RecycledItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cv = android.content.ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, item.phoneNumber)
                    put(Telephony.Sms.BODY, item.body)
                    put(Telephony.Sms.DATE, item.date)
                    put(Telephony.Sms.READ, 1)
                    put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                }
                contentResolver.insert(Telephony.Sms.CONTENT_URI, cv)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            App.get().database.messagesDao().removeFromRecycleBin(item.id)
            withContext(Dispatchers.Main) { loadRecycleBin() }
        }
    }

    /**
     * Permanently delete: message was already removed from Telephony when moved to recycle bin.
     * Just remove the saved copy from the Room recycle-bin table.
     */
    private fun permanentlyDeleteItem(item: RecycledItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            App.get().database.messagesDao().removeFromRecycleBin(item.id)
            withContext(Dispatchers.Main) { loadRecycleBin() }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_STAR) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }
}
