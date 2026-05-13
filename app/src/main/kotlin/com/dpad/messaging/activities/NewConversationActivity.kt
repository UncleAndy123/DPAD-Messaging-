package com.dpad.messaging.activities

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.dpad.messaging.App
import com.dpad.messaging.R
import com.dpad.messaging.databinding.ActivityNewConversationBinding
import com.dpad.messaging.helpers.ThemeManager
import com.dpad.messaging.models.Draft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "New Conversation" screen.
 *
 * Phase 1: enter a phone number directly → opens ThreadActivity.
 * Phase 2: add contact-lookup suggestions as the user types.
 *
 * D-Pad flow: Back → et_recipient → et_message → btn_send
 */
class NewConversationActivity : BaseActivity() {

    private lateinit var binding: ActivityNewConversationBinding
    private var pendingAttachmentUri: Uri? = null
    private var pendingPrefillBody: String = ""
    private val selectedRecipients = mutableListOf<String>()
    private val suggestions = mutableListOf<com.dpad.messaging.helpers.ContactHelper.ContactSuggestion>()
    private lateinit var suggestionsAdapter: ArrayAdapter<String>

    private lateinit var contactPickerLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyAccentColor(this)
        binding = ActivityNewConversationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        contactPickerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { addPickedContact(it) }
            }
        }

        setupToolbar()
        setupInputs()
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        applyAccent()
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener { finish() }

        // D-Pad DOWN from back button → recipient field
        binding.btnBack.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                binding.btnAddRecipient.requestFocus()
                true
            } else false
        }

        applyAccent()
    }

    private fun applyAccent() {
        val accent = ThemeManager.accentColor(this)
        val tint = ColorStateList.valueOf(accent)

        binding.btnBack.imageTintList = tint

        binding.btnBack.backgroundTintList = tint
        binding.btnSend.backgroundTintList = tint
        binding.btnAddRecipient.backgroundTintList = tint
        binding.btnAddRecipient.setTextColor(accent)
        updateSendButton()
    }

    private fun setupInputs() {
        // Enable Next button when recipients are present and show type-ahead.
        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                updateSendButton()
                updateSuggestions(s?.toString().orEmpty())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        binding.etRecipient.addTextChangedListener(watcher)

        binding.etRecipient.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (addRecipientFromInput()) {
                    return@setOnKeyListener true
                }
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                binding.lvSuggestions.visibility == View.VISIBLE) {
                binding.lvSuggestions.requestFocus()
                binding.lvSuggestions.setSelection(0)
                return@setOnKeyListener true
            }
            false
        }

        binding.btnAddRecipient.setOnClickListener {
            contactPickerLauncher.launch(
                Intent(Intent.ACTION_PICK, Phone.CONTENT_URI)
            )
        }

        binding.btnSend.setOnClickListener { sendAndOpen() }

        // ENTER on recipient field → go to Next flow.
        binding.etRecipient.setOnEditorActionListener { _, _, event ->
            if (event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN) {
                sendAndOpen(); true
            } else false
        }

        binding.etRecipient.requestFocus()

        // Suggestions list
        suggestionsAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_2,
            android.R.id.text1, mutableListOf<String>())
        binding.lvSuggestions.adapter = suggestionsAdapter
        binding.lvSuggestions.setOnItemClickListener { _, _, position, _ ->
            if (position < suggestions.size) {
                val s = suggestions[position]
                addRecipient(s.phoneNumber)
                binding.etRecipient.text?.clear()
                hideSuggestions()
            }
        }
        binding.lvSuggestions.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP &&
                binding.lvSuggestions.selectedItemPosition == 0) {
                binding.etRecipient.requestFocus()
                return@setOnKeyListener true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                val pos = binding.lvSuggestions.selectedItemPosition
                if (pos >= 0 && pos < suggestions.size) {
                    val s = suggestions[pos]
                    addRecipient(s.phoneNumber)
                    binding.etRecipient.text?.clear()
                    hideSuggestions()
                    return@setOnKeyListener true
                }
            }
            false
        }
    }

    private fun updateSendButton() {
        val hasRecipients = selectedRecipients.isNotEmpty() ||
            parseRecipients(binding.etRecipient.text?.toString().orEmpty()).isNotEmpty()
        val accentColor = ThemeManager.accentColor(this)
        binding.btnSend.isEnabled = hasRecipients
        binding.btnSend.setColorFilter(
            if (hasRecipients) accentColor
            else getColor(R.color.sendButtonDisabled)
        )
    }

    private fun sendAndOpen() {
        addRecipientFromInput()
        val recipients = selectedRecipients.toList()
        if (recipients.isEmpty()) return

        val attachment = pendingAttachmentUri

        // Contacts-first flow: resolve/create the thread and open it.
        lifecycleScope.launch {
            val threadId = withContext(Dispatchers.IO) {
                resolveOrCreateThreadId(recipients)
            }
            if (threadId != null) {
                withContext(Dispatchers.IO) {
                    // Preserve forwarded/shared text as a draft body in thread compose.
                    if (pendingPrefillBody.isNotBlank()) {
                        App.get().database.draftsDao().insertDraft(
                            Draft(threadId = threadId, body = pendingPrefillBody)
                        )
                    }
                }

                val intent = Intent(this@NewConversationActivity, ThreadActivity::class.java).apply {
                    putExtra(ThreadActivity.EXTRA_THREAD_ID, threadId)
                    val title = recipients.joinToString(", ") { App.get().contactHelper.getDisplayName(it) }
                    putExtra(ThreadActivity.EXTRA_THREAD_TITLE, title)
                    putExtra(ThreadActivity.EXTRA_PHONE_NUMBER, recipients.first())
                    if (recipients.size > 1) {
                        putExtra(ThreadActivity.EXTRA_PARTICIPANTS, recipients.joinToString(","))
                    }
                    if (attachment != null) {
                        putExtra(ThreadActivity.EXTRA_PREFILL_ATTACHMENT_URI, attachment.toString())
                    }
                }
                startActivity(intent)
                finish()
            } else {
                Toast.makeText(
                    this@NewConversationActivity,
                    R.string.error_sending_message,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Returns the existing or created thread ID for one-to-one or group recipients.
     *
     * Phase 2: call SmsManager.sendTextMessage() here before returning the thread ID.
     */
    private fun resolveOrCreateThreadId(recipients: List<String>): Long? {
        return try {
            if (recipients.size == 1) {
                Telephony.Threads.getOrCreateThreadId(this, recipients.first())
            } else {
                Telephony.Threads.getOrCreateThreadId(this, recipients.toSet())
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun updateSuggestions(query: String) {
        if (query.length < 2) { hideSuggestions(); return }
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                App.get().contactHelper.search(query)
            }
            suggestions.clear()
            suggestions.addAll(results)
            suggestionsAdapter.clear()
            suggestionsAdapter.addAll(results.map { "${it.displayName}\n${it.phoneNumber}" })
            suggestionsAdapter.notifyDataSetChanged()
            binding.lvSuggestions.visibility =
                if (results.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun hideSuggestions() {
        suggestions.clear()
        suggestionsAdapter.clear()
        binding.lvSuggestions.visibility = View.GONE
    }

    private fun parseRecipients(raw: String): List<String> {
        return raw.split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun addRecipient(recipient: String): Boolean {
        val normalized = recipient.trim()
        if (normalized.isBlank() || selectedRecipients.contains(normalized)) return false
        selectedRecipients.add(normalized)
        renderRecipientChips()
        updateSendButton()
        return true
    }

    private fun addRecipientFromInput(): Boolean {
        val rawInput = binding.etRecipient.text?.toString().orEmpty()
        val typedRecipients = parseRecipients(rawInput)
        if (typedRecipients.isEmpty()) return false
        var addedAny = false
        for (recipient in typedRecipients) {
            if (addRecipient(recipient)) {
                addedAny = true
            }
        }
        binding.etRecipient.text?.clear()
        hideSuggestions()
        return addedAny
    }

    private fun renderRecipientChips() {
        binding.recipientChipsContainer.removeAllViews()
        if (selectedRecipients.isEmpty()) {
            binding.recipientChipsScroll.visibility = View.GONE
            return
        }

        binding.recipientChipsScroll.visibility = View.VISIBLE
        selectedRecipients.forEach { recipient ->
            val label = App.get().contactHelper.getDisplayName(recipient)
            val accent = ThemeManager.accentColor(this@NewConversationActivity)
            val chip = TextView(this).apply {
                text = "$label  ×"
                contentDescription = getString(R.string.remove_recipient)
                val tint = ColorStateList.valueOf(accent)
                setBackgroundResource(R.drawable.button_focusable_bg)
                backgroundTintList = tint
                setTextColor(ContextCompat.getColor(this@NewConversationActivity, R.color.colorOnPrimary))
                textSize = 14f
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(24, 12, 24, 12)
                setOnClickListener {
                    selectedRecipients.remove(recipient)
                    renderRecipientChips()
                    updateSendButton()
                }
            }
            val params = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 12
            }
            binding.recipientChipsContainer.addView(chip, params)
        }
    }

    private fun addPickedContact(contactUri: Uri) {
        val projection = arrayOf(Phone.NUMBER, Phone.DISPLAY_NAME)
        val phoneNumber = try {
            contentResolver.query(contactUri, projection, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(Phone.NUMBER)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        } catch (e: Exception) {
            Log.w("DPAD_MSG", "NewConversationActivity: unable to read selected contact", e)
            null
        }

        if (phoneNumber.isNullOrBlank()) {
            Toast.makeText(this, R.string.error_picking_contact, Toast.LENGTH_SHORT).show()
            return
        }

        addRecipient(phoneNumber)
    }

    // Handles share/forward intents. Body and attachment are carried into the opened thread.
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) {
                    pendingPrefillBody = text
                }
                val stream = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
                if (stream != null) pendingAttachmentUri = stream
            }
            else -> {
                val prefill = intent.getStringExtra(EXTRA_PREFILL_BODY)
                if (!prefill.isNullOrBlank()) {
                    pendingPrefillBody = prefill
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_STAR) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        const val EXTRA_PREFILL_BODY = "extra_prefill_body"
    }
}
