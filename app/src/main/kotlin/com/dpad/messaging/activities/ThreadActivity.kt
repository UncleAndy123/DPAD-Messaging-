package com.dpad.messaging.activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.dpad.messaging.App
import com.dpad.messaging.R
import com.dpad.messaging.adapters.ThreadAdapter
import com.dpad.messaging.databinding.ActivityThreadBinding
import com.dpad.messaging.events.RefreshMessages
import com.dpad.messaging.extensions.getMessagesForThread
import com.dpad.messaging.extensions.markThreadAsReadInTelephony
import com.dpad.messaging.helpers.MmsSender
import com.dpad.messaging.helpers.Prefs
import com.dpad.messaging.helpers.SmsSender
import com.dpad.messaging.models.Message
import com.dpad.messaging.models.RecycleBinMessage
import com.dpad.messaging.models.ThreadItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class ThreadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityThreadBinding
    private lateinit var threadAdapter: ThreadAdapter

    private var threadId: Long = -1L
    private var threadTitle: String = ""
    private var phoneNumber: String = ""
    /** All participant numbers for this thread (size > 1 = group). */
    private var participants: List<String> = emptyList()

    /** Active subscriptionId to use when sending (-1 = system default). */
    private var selectedSubId: Int = -1

    /**
     * Cached list of (subscriptionId, displayName) for the SIM picker.
     * Populated in loadSimInfo(); empty on single-SIM or no READ_PHONE_STATE.
     */
    private var simEntries: List<Pair<Int, String>> = emptyList()

    /** URI of the image the user has selected but not yet sent. Null when no pending attachment. */
    private var pendingAttachmentUri: Uri? = null

    private lateinit var imagePickerLauncher: ActivityResultLauncher<String>

    // ─── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityThreadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1L)
        threadTitle = intent.getStringExtra(EXTRA_THREAD_TITLE) ?: ""
        phoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: ""
        participants = intent.getStringExtra(EXTRA_PARTICIPANTS)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            ?: listOf(phoneNumber).filter { it.isNotBlank() }

        if (threadId == -1L) { finish(); return }

        // Register before setupComposeBar() (must be called before onStart)
        imagePickerLauncher = registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri ->
            if (uri != null) {
                pendingAttachmentUri = uri
                showAttachmentPreview(uri)
            }
        }

        setupToolbar()
        setupMessageList()
        setupComposeBar()
        loadSimInfo()
        loadMessages()
    }

    override fun onResume() {
        super.onResume()
        EventBus.getDefault().register(this)
        markThreadRead()
        loadMessages()   // Bug #1 fix: refresh thread when returning from background
    }

    override fun onPause() {
        saveDraft()
        EventBus.getDefault().unregister(this)
        super.onPause()
    }

    // ─── Setup ─────────────────────────────────────────────────────────────

    private fun setupToolbar() {
        binding.tvContactName.text = threadTitle

        binding.btnBack.setOnClickListener { finish() }

        binding.btnCall.setOnClickListener {
            if (phoneNumber.isNotBlank()) {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber"))
                startActivity(intent)
            }
        }

        binding.btnDetails.setOnClickListener {
            val intent = Intent(this, ConversationDetailsActivity::class.java).apply {
                putExtra(EXTRA_THREAD_ID, threadId)
                putExtra(EXTRA_THREAD_TITLE, threadTitle)
                putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
            }
            startActivity(intent)
        }

        // D-Pad DOWN from any toolbar button → focus the message list
        val goToMessages = View.OnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                binding.rvMessages.focusLastItem()
                true
            } else false
        }
        binding.btnBack.setOnKeyListener(goToMessages)
        binding.btnCall.setOnKeyListener(goToMessages)
        binding.btnDetails.setOnKeyListener(goToMessages)
    }

    private fun setupMessageList() {
        threadAdapter = ThreadAdapter(
            onMessageLongClick = { message -> showMessageContextMenu(message) }
        )

        binding.rvMessages.apply {
            adapter = threadAdapter
            layoutManager = LinearLayoutManager(this@ThreadActivity).apply {
                stackFromEnd = true   // newest messages at the bottom
            }
            // D-Pad UP from first message → toolbar
            onTopEdgeReached = {
                binding.btnBack.requestFocus()
            }
            // D-Pad DOWN from last message → compose bar
            onBottomEdgeReached = {
                binding.etMessage.requestFocus()
            }
        }
    }

    private fun setupComposeBar() {
        // D-Pad UP from compose → message list
        binding.etMessage.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) {
                binding.rvMessages.focusLastItem()
                true
            } else false
        }
        binding.btnAttach.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) {
                binding.rvMessages.focusLastItem()
                true
            } else false
        }
        binding.btnSend.setOnKeyListener { _, keyCode, event ->
            when {
                keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN -> {
                    binding.rvMessages.focusLastItem(); true
                }
                // Wrap right → SIM button if visible, else wrap to attach
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN &&
                        binding.btnSim.visibility != View.VISIBLE -> {
                    binding.btnAttach.requestFocus(); true
                }
                else -> false
            }
        }
        binding.btnSim.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) {
                binding.rvMessages.focusLastItem()
                true
            } else false
        }
        binding.btnSim.setOnClickListener { showSimPicker() }

        // Attachment preview strip
        binding.btnRemoveAttachment.setOnClickListener { clearAttachment() }

        // Enable/disable send button and update character counter based on text
        binding.etMessage.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { updateSendButtonState() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // IME_ACTION_SEND always sends; ENTER key only sends when sendOnEnter is enabled
        binding.etMessage.setOnEditorActionListener { _, actionId, event ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else if (event != null
                && event.keyCode == KeyEvent.KEYCODE_ENTER
                && event.action == KeyEvent.ACTION_DOWN
                && !event.isShiftPressed
                && Prefs.get().sendOnEnter
            ) {
                sendMessage()
                true
            } else false
        }

        binding.btnSend.setOnClickListener { sendMessage() }

        // Open image picker
        binding.btnAttach.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        // Restore any saved draft
        lifecycleScope.launch {
            val draft = withContext(Dispatchers.IO) {
                App.get().database.draftsDao().getDraft(threadId)
            }
            if (draft != null) {
                binding.etMessage.setText(draft.body)
                binding.etMessage.setSelection(draft.body.length)
            }
        }

        // Compose bar gets initial focus
        binding.etMessage.requestFocus()
    }

    // ─── Attachment preview ─────────────────────────────────────────────────

    private fun showAttachmentPreview(uri: Uri) {
        binding.attachmentPreviewBar.visibility = View.VISIBLE
        Glide.with(this)
            .load(uri)
            .centerCrop()
            .into(binding.ivAttachmentPreview)
        updateSendButtonState()
    }

    private fun clearAttachment() {
        pendingAttachmentUri = null
        binding.attachmentPreviewBar.visibility = View.GONE
        Glide.with(this).clear(binding.ivAttachmentPreview)
        updateSendButtonState()
    }

    // ─── Send button state ──────────────────────────────────────────────────

    /**
     * Enables the send button when there is text OR a pending attachment.
     * Also updates the character counter for SMS segment tracking.
     */
    private fun updateSendButtonState() {
        val hasText       = binding.etMessage.text?.isNotBlank() == true
        val hasAttachment = pendingAttachmentUri != null
        val enabled       = hasText || hasAttachment

        binding.btnSend.isEnabled = enabled
        binding.btnSend.setColorFilter(
            if (enabled) getColor(R.color.sendButtonEnabled)
            else         getColor(R.color.sendButtonDisabled)
        )

        // Character counter (only meaningful for plain SMS, no attachment)
        if (Prefs.get().characterCounter && hasText && !hasAttachment) {
            val text = binding.etMessage.text?.toString() ?: ""
            @Suppress("DEPRECATION")
            val result = android.telephony.SmsMessage.calculateLength(text, false)
            // result[0] = segments, result[2] = remaining chars in last segment
            binding.tvCharCount.text = "${result[2]}/${result[0]}"
            binding.tvCharCount.visibility = View.VISIBLE
        } else {
            binding.tvCharCount.visibility = View.GONE
        }
    }

    // ─── Data ───────────────────────────────────────────────────────────────

    private fun loadMessages() {
        Log.d("DPAD_MSG", "ThreadActivity.loadMessages() called for threadId=$threadId")
        lifecycleScope.launch {
            val messages = withContext(Dispatchers.IO) {
                getMessagesForThread(threadId, App.get().contactHelper)
            }
            Log.d("DPAD_MSG", "ThreadActivity.loadMessages() got ${messages.size} messages for threadId=$threadId")
            displayMessages(messages)
        }
    }

    private fun displayMessages(messages: List<Message>) {
        val items = ThreadItem.fromMessages(messages)
        threadAdapter.submitList(items) {
            // After the list is drawn, scroll to bottom
            binding.rvMessages.scrollToPosition(threadAdapter.itemCount - 1)
        }
    }

    private fun markThreadRead() {
        lifecycleScope.launch(Dispatchers.IO) {
            App.get().database.messagesDao().markThreadRead(threadId)
            App.get().database.conversationsDao().markAsRead(threadId)
            markThreadAsReadInTelephony(threadId)
        }
    }

    private fun saveDraft() {
        val body = binding.etMessage.text?.toString() ?: ""
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = App.get().database.draftsDao()
            if (body.isBlank()) {
                dao.deleteDraft(threadId)
            } else {
                dao.insertDraft(
                    com.dpad.messaging.models.Draft(threadId = threadId, body = body)
                )
            }
        }
    }

    // ─── Send ────────────────────────────────────────────────────────────────

    private fun sendMessage() {
        val body       = binding.etMessage.text?.toString()?.trim() ?: ""
        val attachment = pendingAttachmentUri

        if (body.isBlank() && attachment == null) return
        if (phoneNumber.isBlank() && participants.isEmpty()) return

        Log.d("DPAD_MSG", "ThreadActivity.sendMessage() body='${body.take(20)}' attachment=${attachment != null} participants=$participants isGroup=${participants.size > 1}")

        binding.etMessage.text?.clear()
        clearAttachment()
        binding.etMessage.requestFocus()

        lifecycleScope.launch(Dispatchers.IO) {
            val isGroup = participants.size > 1
            val hasAttachment = attachment != null
            val sendGroupTextAsMms = Prefs.get().sendGroupMessageMms
            
            when {
                // Attachment (image/video) → always send as group MMS
                hasAttachment && isGroup -> {
                    MmsSender.send(
                        context        = this@ThreadActivity,
                        recipients     = participants,
                        body           = body,
                        imageUri       = attachment,
                        threadId       = threadId,
                        subscriptionId = selectedSubId
                    )
                }
                // Text-only group → optional group MMS to keep a single thread together.
                !hasAttachment && isGroup && sendGroupTextAsMms -> {
                    Log.d("DPAD_MSG", "ThreadActivity.sendMessage() text-only group: sending as group MMS to keep thread together")
                    MmsSender.send(
                        context        = this@ThreadActivity,
                        recipients     = participants,
                        body           = body,
                        imageUri       = null,
                        threadId       = threadId,
                        subscriptionId = selectedSubId
                    )
                }
                // Text-only group → send as individual SMS to each recipient (more compatible)
                !hasAttachment && isGroup -> {
                    Log.d("DPAD_MSG", "ThreadActivity.sendMessage() text-only group: sending individual SMS to ${participants.size} recipients")
                    for (recipient in participants) {
                        SmsSender.send(
                            context        = this@ThreadActivity,
                            phoneNumber    = recipient,
                            body           = body,
                            threadId       = threadId,
                            subscriptionId = selectedSubId
                        )
                    }
                }
                // 1:1 with attachment → send as MMS
                hasAttachment && !isGroup -> {
                    MmsSender.send(
                        context        = this@ThreadActivity,
                        recipients     = listOf(phoneNumber),
                        body           = body,
                        imageUri       = attachment,
                        threadId       = threadId,
                        subscriptionId = selectedSubId
                    )
                }
                // 1:1 text-only → send as SMS
                else -> {
                    SmsSender.send(
                        context        = this@ThreadActivity,
                        phoneNumber    = phoneNumber,
                        body           = body,
                        threadId       = threadId,
                        subscriptionId = selectedSubId
                    )
                }
            }
            withContext(Dispatchers.Main) { loadMessages() }
        }
    }

    // ─── Context menus ──────────────────────────────────────────────────────

    private fun showMessageContextMenu(message: Message) {
        val options = buildList {
            add(getString(R.string.copy_text))
            if (!message.isIncoming) {
                if (message.type == Message.TYPE_FAILED) add(getString(R.string.retry_send))
            }
            add(getString(R.string.forward))
            add(getString(R.string.move_to_recycle_bin))
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (options[which]) {
                    getString(R.string.copy_text) -> copyMessageText(message.body)
                    getString(R.string.retry_send) -> retryMessage(message)
                    getString(R.string.forward) -> forwardMessage(message)
                    getString(R.string.move_to_recycle_bin) -> deleteMessage(message)
                }
            }
            .create()
            .show()
    }

    private fun copyMessageText(body: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("message", body))
        android.widget.Toast.makeText(this, R.string.message_copied, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun forwardMessage(message: Message) {
        val intent = Intent(this, NewConversationActivity::class.java).apply {
            putExtra(NewConversationActivity.EXTRA_PREFILL_BODY, message.body)
        }
        startActivity(intent)
    }

    private fun deleteMessage(message: Message) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (Prefs.get().recycleBinEnabled) {
                App.get().database.messagesDao()
                    .insertRecycleBinMessage(RecycleBinMessage(id = message.id))
            } else {
                // Hard-delete directly from the Telephony SMS Content Provider.
                try {
                    contentResolver.delete(
                        ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, message.id),
                        null, null
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            withContext(Dispatchers.Main) { loadMessages() }
        }
    }

    /**
     * Retry a failed message: hard-delete the FAILED row from Telephony CP, then
     * re-send via SmsSender (which will insert a fresh OUTBOX row).
     */
    private fun retryMessage(message: Message) {
        lifecycleScope.launch(Dispatchers.IO) {
            // Remove the stale FAILED row
            try {
                contentResolver.delete(
                    ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, message.id),
                    null, null
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            // Re-send
            SmsSender.send(
                context        = this@ThreadActivity,
                phoneNumber    = phoneNumber,
                body           = message.body,
                threadId       = threadId,
                subscriptionId = message.subscriptionId
            )
            withContext(Dispatchers.Main) { loadMessages() }
        }
    }

    // ─── SIM picker ──────────────────────────────────────────────────────────

    /**
     * Reads active SIM subscriptions. On single-SIM devices (or when permission is
     * absent) the SIM button stays hidden. On multi-SIM devices the button appears
     * and shows the currently-selected SIM slot label.
     */
    @SuppressLint("MissingPermission")
    private fun loadSimInfo() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            // No permission — use default SMS subscription ID
            selectedSubId = getDefaultSmsSubscriptionId()
            return
        }

        val sm = getSystemService(SubscriptionManager::class.java) ?: return
        val subs = try { sm.activeSubscriptionInfoList } catch (_: Exception) { null }
        if (subs == null || subs.isEmpty()) {
            // No active subscriptions — use system default
            selectedSubId = getDefaultSmsSubscriptionId()
            return
        }

        if (subs.size < 2) {
            // Single-SIM — use that subscription's ID instead of -1
            selectedSubId = subs[0].subscriptionId
            Log.d("DPAD_MSG", "ThreadActivity.loadSimInfo() single-SIM, selectedSubId=$selectedSubId")
            return   // single-SIM: hide button
        }

        // Multi-SIM: show picker and default to first SIM
        simEntries = subs.mapIndexed { idx, info ->
            val label = info.displayName?.toString()?.ifBlank { null }
                ?: getString(R.string.sim_label, idx + 1)
            Pair(info.subscriptionId, label)
        }

        // Default to the first (lowest index) SIM
        selectedSubId = simEntries.first().first
        binding.btnSim.text = simEntries.first().second
        binding.btnSim.visibility = View.VISIBLE
        Log.d("DPAD_MSG", "ThreadActivity.loadSimInfo() multi-SIM count=${subs.size}, selectedSubId=$selectedSubId")
    }

    private fun getDefaultSmsSubscriptionId(): Int {
        return try {
            SubscriptionManager.getDefaultSmsSubscriptionId()
        } catch (e: Exception) {
            Log.w("DPAD_MSG", "Failed to get default SMS subscription ID", e)
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
    }

    private fun showSimPicker() {
        if (simEntries.isEmpty()) return
        val labels = simEntries.map { it.second }.toTypedArray()
        val currentIdx = simEntries.indexOfFirst { it.first == selectedSubId }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.select_sim)
            .setSingleChoiceItems(labels, currentIdx) { dialog, which ->
                selectedSubId = simEntries[which].first
                binding.btnSim.text = simEntries[which].second
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ─── EventBus ──────────────────────────────────────────────────────────

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onRefreshMessages(event: RefreshMessages) {
        Log.d("DPAD_MSG", "ThreadActivity.onRefreshMessages() event.threadId=${event.threadId} local threadId=$threadId match=${event.threadId == threadId}")
        if (event.threadId == threadId) loadMessages()
    }

    // ─── Key handling ───────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    companion object {
        const val EXTRA_THREAD_ID    = "extra_thread_id"
        const val EXTRA_THREAD_TITLE = "extra_thread_title"
        const val EXTRA_PHONE_NUMBER = "extra_phone_number"
        /** Comma-separated participant numbers; present for group threads. */
        const val EXTRA_PARTICIPANTS = "extra_participants"
    }
}
