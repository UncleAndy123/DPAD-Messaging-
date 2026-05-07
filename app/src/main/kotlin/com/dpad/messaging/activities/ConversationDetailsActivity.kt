package com.dpad.messaging.activities

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.dpad.messaging.App
import com.dpad.messaging.R
import com.dpad.messaging.databinding.ActivityConversationDetailsBinding
import com.dpad.messaging.helpers.ThemeManager
import com.dpad.messaging.models.BlockedKeyword
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Conversation details screen.
 * Phase 4: rename persists via Room conversationsDao.setCustomTitle();
 *           block inserts a BlockedKeyword row so SmsReceiver suppresses notifications.
 */
class ConversationDetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConversationDetailsBinding

    private var threadId: Long = -1L
    private var currentTitle: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyAccentColor(this)
        binding = ActivityConversationDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        threadId     = intent.getLongExtra(ThreadActivity.EXTRA_THREAD_ID, -1L)
        currentTitle = intent.getStringExtra(ThreadActivity.EXTRA_THREAD_TITLE) ?: ""
        val phoneNumber = intent.getStringExtra(ThreadActivity.EXTRA_PHONE_NUMBER) ?: ""
        val participants = intent.getStringExtra(ThreadActivity.EXTRA_PARTICIPANTS)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        binding.tvContactName.text = currentTitle
        binding.tvPhoneNumber.text = if (participants.size > 1) {
            participants.joinToString("\n") { App.get().contactHelper.getDisplayName(it) }
        } else {
            phoneNumber
        }

        binding.btnBack.setOnClickListener { finish() }

        // D-Pad focus chain: Back → Rename → Block
        binding.btnBack.nextFocusDownId   = binding.btnRename.id
        binding.btnRename.nextFocusUpId   = binding.btnBack.id
        binding.btnRename.nextFocusDownId = binding.btnBlock.id
        binding.btnBlock.nextFocusUpId    = binding.btnRename.id

        binding.btnRename.setOnClickListener { showRenameDialog() }
        val blockTarget = participants.firstOrNull() ?: phoneNumber
        binding.btnBlock.setOnClickListener  { showBlockConfirmation(blockTarget) }
    }

    override fun onResume() {
        super.onResume()
        applyAccent()
    }

    private fun applyAccent() {
        val tint = ColorStateList.valueOf(ThemeManager.accentColor(this))
        binding.btnBack.imageTintList = tint
        binding.btnBack.backgroundTintList = tint
        binding.btnRename.backgroundTintList = tint
        binding.btnBlock.backgroundTintList = tint
    }

    private fun showRenameDialog() {
        val input = android.widget.EditText(this).apply {
            setText(currentTitle)
            setSelection(currentTitle.length)
            setTextColor(getColor(R.color.colorOnBackground))
            setBackgroundResource(R.drawable.compose_input_bg)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.rename_conversation)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = input.text?.toString()?.trim()
                if (!newName.isNullOrBlank()) {
                    // Persist in Room
                    lifecycleScope.launch(Dispatchers.IO) {
                        App.get().database.conversationsDao()
                            .setCustomTitle(threadId, newName)
                        withContext(Dispatchers.Main) {
                            currentTitle = newName
                            binding.tvContactName.text = newName
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showBlockConfirmation(phoneNumber: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.block)
            .setMessage(phoneNumber)
            .setPositiveButton(R.string.yes) { _, _ ->
                // Insert the phone number as a blocked keyword so SmsReceiver
                // will match both body.contains(kw) and address == kw.
                lifecycleScope.launch(Dispatchers.IO) {
                    App.get().database.blockedKeywordsDao()
                        .insert(BlockedKeyword(keyword = phoneNumber))
                    withContext(Dispatchers.Main) { finish() }
                }
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_STAR) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }
}
