package com.dpad.messaging.activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
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
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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
import com.dpad.messaging.helpers.SendingMode
import com.dpad.messaging.helpers.SendingRouter
import com.dpad.messaging.helpers.ThemeManager
import com.dpad.messaging.helpers.MessageSenders
import com.dpad.messaging.models.Message
import com.dpad.messaging.models.RecycleBinMessage
import com.dpad.messaging.models.ThreadItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.io.File

class ThreadActivity : BaseActivity() {

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
    private var pendingCameraUri: Uri? = null

    private lateinit var attachmentPickerLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var contactPickerLauncher: ActivityResultLauncher<Void?>
    private lateinit var cameraCaptureLauncher: ActivityResultLauncher<Uri>
    private var hasInitializedList = false

    // ─── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyAccentColor(this)
        binding = ActivityThreadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        extractThreadExtras(intent)

        if (threadId == -1L) { finish(); return }

        // Register before setupComposeBar() (must be called before onStart)
        attachmentPickerLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    // Not all providers offer persistable permissions.
                }
                pendingAttachmentUri = uri
                showAttachmentPreview(uri)
            }
        }

        // Dedicated contact picker — contacts are not files so OpenDocument can't reach them.
        contactPickerLauncher = registerForActivityResult(
            ActivityResultContracts.PickContact()
        ) { contactUri ->
            if (contactUri != null) {
                // Build a vCard URI from the lookup key so openInputStream() works.
                contentResolver.query(
                    contactUri,
                    arrayOf(ContactsContract.Contacts.LOOKUP_KEY),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val lookupKey = cursor.getString(0)
                        val vCardUri = Uri.withAppendedPath(
                            ContactsContract.Contacts.CONTENT_VCARD_URI,
                            Uri.encode(lookupKey)
                        )
                        pendingAttachmentUri = vCardUri
                        showAttachmentPreview(vCardUri)
                    }
                }
            }
        }

        cameraCaptureLauncher = registerForActivityResult(
            ActivityResultContracts.TakePicture()
        ) { success ->
            val uri = pendingCameraUri
            if (success && uri != null) {
                pendingAttachmentUri = uri
                showAttachmentPreview(uri)
            } else if (uri != null) {
                runCatching { contentResolver.delete(uri, null, null) }
            }
            pendingCameraUri = null
        }

        setupToolbar()
        setupMessageList()
        setupComposeBar()
        applyPrefillAttachmentFromIntent(intent)
        loadSimInfo()
        loadMessages()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractThreadExtras(intent)
        applyPrefillAttachmentFromIntent(intent)
        setupToolbar()
        loadMessages()
        markThreadRead()
    }

    override fun onResume() {
        super.onResume()
        EventBus.getDefault().register(this)
        applyAccent()
        loadMessages()   // Bug #1 fix: refresh thread when returning from background
    }

    override fun onPause() {
        markThreadRead()
        saveDraft()
        EventBus.getDefault().unregister(this)
        super.onPause()
    }

    // ─── Setup ─────────────────────────────────────────────────────────────

    private fun setupToolbar() {
        val titleForToolbar = if (participants.size > 1) {
            participants.joinToString(", ") { App.get().contactHelper.getDisplayName(it) }
        } else {
            threadTitle
        }
        binding.tvContactName.text = titleForToolbar

        binding.btnBack.setOnClickListener { finish() }

        binding.btnCall.setOnClickListener {
            handleCallAction()
        }

        binding.btnDetails.setOnClickListener {
            val intent = Intent(this, ConversationDetailsActivity::class.java).apply {
                putExtra(EXTRA_THREAD_ID, threadId)
                putExtra(EXTRA_THREAD_TITLE, threadTitle)
                putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
                putExtra(EXTRA_PARTICIPANTS, participants.joinToString(","))
            }
            startActivity(intent)
        }

        // D-Pad DOWN from any toolbar button → focus the message list (or compose bar when empty)
        val goToMessages = View.OnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                if (threadAdapter.itemCount > 0) binding.rvMessages.focusLastItem()
                else binding.etMessage.requestFocus()
                true
            } else false
        }
        binding.btnBack.setOnKeyListener(goToMessages)
        binding.btnCall.setOnKeyListener(goToMessages)
        binding.btnDetails.setOnKeyListener(goToMessages)

        applyAccent()
    }

    private fun applyAccent() {
        val accent = ThemeManager.accentColor(this)
        val tint = ColorStateList.valueOf(accent)

        binding.btnBack.imageTintList = tint
        binding.btnCall.imageTintList = tint
        binding.btnDetails.imageTintList = tint
        binding.btnAttach.imageTintList = tint
        binding.btnSim.setTextColor(accent)
        // btnRemoveAttachment uses its XML fill color — no tinting needed, keeps icon always visible.

        binding.btnBack.backgroundTintList = tint
        binding.btnCall.backgroundTintList = tint
        binding.btnDetails.backgroundTintList = tint
        binding.btnAttach.backgroundTintList = tint
        binding.btnSend.backgroundTintList = tint
        binding.btnSim.backgroundTintList = tint

        updateSendButtonState()
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
        // D-Pad UP from compose:
        // 1) If chips are visible, go to the chips container.
        // 2) If an attachment is visible, go to the remove-attachment button.
        // 3) Otherwise go to the message list (or back button when list is empty).
        val goUpFromCompose = { ->
            if (binding.chipsContainerScroll.visibility == View.VISIBLE) {
                binding.chipsContainer.getChildAt(binding.chipsContainer.childCount - 1)?.requestFocus()
                    ?: binding.chipsContainerScroll.requestFocus()
            } else if (binding.attachmentPreviewBar.visibility == View.VISIBLE) {
                binding.btnRemoveAttachment.requestFocus()
            } else if (threadAdapter.itemCount > 0) {
                binding.rvMessages.focusLastItem()
            } else {
                binding.btnBack.requestFocus()
            }
        }
        binding.etMessage.setOnKeyListener { _, keyCode, event ->
            when {
                keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN -> {
                    goUpFromCompose(); true
                }
                // DPAD CENTER: extract numbers and create chips
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER && event.action == KeyEvent.ACTION_DOWN -> {
                    createNumberChip(); true
                }
                else -> false
            }
        }
        binding.btnAttach.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) {
                goUpFromCompose(); true
            } else false
        }
        binding.btnSend.setOnKeyListener { _, keyCode, event ->
            when {
                keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN -> {
                    goUpFromCompose(); true
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
                goUpFromCompose(); true
            } else false
        }
        binding.btnSim.setOnClickListener { showSimPicker() }

        // Attachment preview strip
        binding.btnRemoveAttachment.setOnClickListener { clearAttachment() }
        binding.btnRemoveAttachment.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                binding.etMessage.requestFocus()
                true
            } else false
        }

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

        // Attach button — show menu to choose between media or contact.
        binding.btnAttach.setOnClickListener { anchor ->
            val popup = PopupMenu(ThemeManager.popupMenuContext(this), anchor)
            popup.menu.add(0, 1, 0, getString(R.string.attach_image_audio))
            popup.menu.add(0, 2, 0, getString(R.string.attach_contact))
            popup.menu.add(0, 3, 0, getString(R.string.attach_camera))
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> attachmentPickerLauncher.launch(arrayOf("image/*", "audio/*"))
                    2 -> contactPickerLauncher.launch(null)
                    3 -> launchCameraAttachment()
                }
                true
            }
            popup.show()
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
        val mimeType = contentResolver.getType(uri)?.lowercase().orEmpty()
        if (mimeType.startsWith("image/")) {
            Glide.with(this)
                .load(uri)
                .centerCrop()
                .into(binding.ivAttachmentPreview)
        } else {
            binding.ivAttachmentPreview.setImageResource(R.drawable.ic_attach)
        }
        binding.btnRemoveAttachment.requestFocus()
        updateSendButtonState()
    }

    private fun clearAttachment() {
        pendingAttachmentUri = null
        binding.attachmentPreviewBar.visibility = View.GONE
        Glide.with(this).clear(binding.ivAttachmentPreview)
        updateSendButtonState()
    }

    private fun applyPrefillAttachmentFromIntent(intent: Intent?) {
        val uriString = intent?.getStringExtra(EXTRA_PREFILL_ATTACHMENT_URI)
        if (uriString.isNullOrBlank()) return
        val uri = Uri.parse(uriString)
        pendingAttachmentUri = uri
        showAttachmentPreview(uri)
    }

    private fun launchCameraAttachment() {
        val imageFile = File(cacheDir, "camera_capture/thread_${threadId}_${System.currentTimeMillis()}.jpg")
        imageFile.parentFile?.mkdirs()
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", imageFile)
        pendingCameraUri = uri
        cameraCaptureLauncher.launch(uri)
    }

    // ─── Number chips ──────────────────────────────────────────────────────

    /**
     * Extracts numbers from the compose input and converts them into visual chips.
     * When DPAD center is pressed, any digits in the input are converted to a chip
     * and the number is removed from the text.
     */
    private fun createNumberChip() {
        val text = binding.etMessage.text?.toString() ?: ""
        if (text.isEmpty()) return

        // Extract all numbers from the text
        val numbers = text.filter { it.isDigit() }
        if (numbers.isEmpty()) return

        // Create a chip for the number
        addNumberChip(numbers)

        // Remove the numbers from the input
        val cleanedText = text.filter { !it.isDigit() }.trim()
        binding.etMessage.setText(cleanedText)
        if (cleanedText.isNotEmpty()) {
            binding.etMessage.setSelection(cleanedText.length)
        }
        updateSendButtonState()
    }

    /**
     * Adds a visual chip for the given number to the chips container.
     */
    private fun addNumberChip(number: String) {
        val chipView = android.widget.Button(this).apply {
            text = number
            isAllCaps = false
            textSize = 12f
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 6.dpToPx()
                marginStart = 6.dpToPx()
            }
            background = getDrawable(R.drawable.button_focusable_bg)
            val accent = ThemeManager.accentColor(this@ThreadActivity)
            backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@ThreadActivity, R.color.colorOnPrimary))
            setOnClickListener { removeNumberChip(this, number) }
            isFocusable = true
            isFocusableInTouchMode = true
            contentDescription = getString(R.string.tap_to_remove_number, number)
        }

        binding.chipsContainer.addView(chipView)

        // Show the chips container
        if (binding.chipsContainerScroll.visibility != View.VISIBLE) {
            binding.chipsContainerScroll.visibility = View.VISIBLE
            // Update the attachment preview bar to point to chips container instead of compose
            binding.btnRemoveAttachment.nextFocusDownId = R.id.chips_container_scroll
        }
    }

    /**
     * Removes a number chip and restores it to the input.
     */
    private fun removeNumberChip(chipView: View, number: String) {
        binding.chipsContainer.removeView(chipView)

        // If no more chips, hide the container
        if (binding.chipsContainer.childCount == 0) {
            binding.chipsContainerScroll.visibility = View.GONE
            binding.btnRemoveAttachment.nextFocusDownId = R.id.btn_attach
        }

        // Restore the number to the input
        val currentText = binding.etMessage.text?.toString() ?: ""
        binding.etMessage.setText("$currentText $number".trim())
        binding.etMessage.setSelection(binding.etMessage.text?.length ?: 0)
        updateSendButtonState()
    }

    /**
     * Extension function to convert DP to pixels.
     */
    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    // ─── Send button state ──────────────────────────────────────────────────

    /**
     * Enables the send button when there is text OR a pending attachment OR chips.
     * Also updates the character counter for SMS segment tracking.
     */
    private fun updateSendButtonState() {
        val hasText       = binding.etMessage.text?.isNotBlank() == true
        val hasAttachment = pendingAttachmentUri != null
        val hasChips      = binding.chipsContainer.childCount > 0
        val enabled       = hasText || hasAttachment || hasChips
        val accentColor   = ThemeManager.accentColor(this)

        binding.btnSend.isEnabled = enabled
        binding.btnSend.setColorFilter(
            if (enabled) accentColor
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
            // Keep initial auto-scroll behavior, but avoid stealing D-pad focus on every refresh
            if (!hasInitializedList) {
                binding.rvMessages.scrollToPosition(threadAdapter.itemCount - 1)
                hasInitializedList = true
            }
        }
    }

    private fun extractThreadExtras(intent: Intent?) {
        threadId = intent?.getLongExtra(EXTRA_THREAD_ID, -1L) ?: -1L
        threadTitle = intent?.getStringExtra(EXTRA_THREAD_TITLE) ?: ""
        phoneNumber = intent?.getStringExtra(EXTRA_PHONE_NUMBER) ?: ""
        participants = intent?.getStringExtra(EXTRA_PARTICIPANTS)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            ?: listOf(phoneNumber).filter { it.isNotBlank() }
    }

    private fun handleCallAction() {
        val candidates = (participants + phoneNumber)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        when {
            candidates.isEmpty() -> return
            candidates.size == 1 -> startDialIntent(candidates.first())
            else -> showCallContactChooser(candidates)
        }
    }

    private fun showCallContactChooser(numbers: List<String>) {
        val labels = numbers.map { number ->
            val display = App.get().contactHelper.getDisplayName(number)
            if (display.equals(number, ignoreCase = true)) number else "$display ($number)"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.call)
            .setItems(labels) { _, which ->
                numbers.getOrNull(which)?.let { startDialIntent(it) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startDialIntent(number: String) {
        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
    }

    private fun markThreadRead() {
        lifecycleScope.launch(Dispatchers.IO) {
            App.get().database.messagesDao().markThreadRead(threadId)
            App.get().database.conversationsDao().markAsRead(threadId)
            markThreadAsReadInTelephony(threadId)
        }
    }

    private fun saveDraft() {
        var body = binding.etMessage.text?.toString() ?: ""
        
        // Include chips in the draft
        val chipNumbers = mutableListOf<String>()
        for (i in 0 until binding.chipsContainer.childCount) {
            val chipButton = binding.chipsContainer.getChildAt(i) as? android.widget.Button
            chipButton?.text?.toString()?.let { chipNumbers.add(it) }
        }
        if (chipNumbers.isNotEmpty()) {
            body = if (body.isNotBlank()) {
                "$body ${chipNumbers.joinToString(" ")}"
            } else {
                chipNumbers.joinToString(" ")
            }
        }
        
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
        var body       = binding.etMessage.text?.toString()?.trim() ?: ""
        val attachment = pendingAttachmentUri

        // Collect numbers from chips and append to message
        val chipNumbers = mutableListOf<String>()
        for (i in 0 until binding.chipsContainer.childCount) {
            val chipButton = binding.chipsContainer.getChildAt(i) as? android.widget.Button
            chipButton?.text?.toString()?.let { chipNumbers.add(it) }
        }
        if (chipNumbers.isNotEmpty()) {
            body = if (body.isNotBlank()) {
                "$body ${chipNumbers.joinToString(" ")}"
            } else {
                chipNumbers.joinToString(" ")
            }
        }

        if (body.isBlank() && attachment == null) return
        if (phoneNumber.isBlank() && participants.isEmpty()) return

        Log.d("DPAD_MSG", "ThreadActivity.sendMessage() body='${body.take(20)}' attachment=${attachment != null} participants=$participants isGroup=${participants.size > 1}")

        binding.etMessage.text?.clear()
        binding.chipsContainer.removeAllViews()
        binding.chipsContainerScroll.visibility = View.GONE
        clearAttachment()
        binding.etMessage.requestFocus()

        lifecycleScope.launch(Dispatchers.IO) {
            val hasAttachment = attachment != null
            val mode = SendingRouter.decideSendingMode(
                hasAttachment = hasAttachment,
                recipientCount = participants.size,
                sendGroupMessageMms = Prefs.get().sendGroupMessageMms
            )
            
            when (mode) {
                SendingMode.MMS_GROUP -> {
                    Log.d("DPAD_MSG", "ThreadActivity.sendMessage() routing: MMS_GROUP")
                    MessageSenders.unified.sendMms(
                        context        = this@ThreadActivity,
                        recipients     = participants,
                        body           = body,
                        attachmentUri  = attachment,
                        threadId       = threadId,
                        subscriptionId = selectedSubId
                    )
                }
                SendingMode.MMS_SINGLE -> {
                    Log.d("DPAD_MSG", "ThreadActivity.sendMessage() routing: MMS_SINGLE")
                    MessageSenders.unified.sendMms(
                        context        = this@ThreadActivity,
                        recipients     = listOf(phoneNumber),
                        body           = body,
                        attachmentUri  = attachment,
                        threadId       = threadId,
                        subscriptionId = selectedSubId
                    )
                }
                SendingMode.SMS_FANOUT_GROUP -> {
                    Log.d("DPAD_MSG", "ThreadActivity.sendMessage() routing: SMS_FANOUT_GROUP to ${participants.size} recipients")
                    MessageSenders.unified.sendGroupSmsFanout(
                        context = this@ThreadActivity,
                        recipients = participants,
                        body = body,
                        fallbackThreadId = threadId,
                        subscriptionId = selectedSubId
                    )
                }
                SendingMode.SMS_SINGLE -> {
                    Log.d("DPAD_MSG", "ThreadActivity.sendMessage() routing: SMS_SINGLE")
                    MessageSenders.unified.sendSms(
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
            MessageSenders.unified.sendSms(
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
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_STAR -> { finish(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    companion object {
        const val EXTRA_THREAD_ID    = "extra_thread_id"
        const val EXTRA_THREAD_TITLE = "extra_thread_title"
        const val EXTRA_PHONE_NUMBER = "extra_phone_number"
        const val EXTRA_PREFILL_ATTACHMENT_URI = "extra_prefill_attachment_uri"
        /** Comma-separated participant numbers; present for group threads. */
        const val EXTRA_PARTICIPANTS = "extra_participants"
    }
}
