package com.dpad.messaging.activities

import android.Manifest
import android.app.role.RoleManager
import android.content.res.ColorStateList
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.view.KeyEvent
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.dpad.messaging.App
import com.dpad.messaging.R
import com.dpad.messaging.adapters.ConversationsAdapter
import com.dpad.messaging.databinding.ActivityMainBinding
import com.dpad.messaging.events.RefreshConversations
import com.dpad.messaging.extensions.getConversationsFromTelephony
import com.dpad.messaging.helpers.Prefs
import com.dpad.messaging.helpers.ThemeManager
import com.dpad.messaging.models.Conversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var conversationsAdapter: ConversationsAdapter
    private lateinit var requestRoleLauncher: ActivityResultLauncher<Intent>

    private val requiredPermissions = buildList {
        add(Manifest.permission.READ_SMS)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.RECEIVE_MMS)
        add(Manifest.permission.RECEIVE_WAP_PUSH)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_MEDIA_IMAGES)
        }
    }

    // ─── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Activity Result API for requesting SMS role (Android Q+)
        requestRoleLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                // Role granted — re-check and refresh UI
                checkDefaultSmsApp()
                loadConversations()
            }
        }

        setupConversationList()
        setupToolbar()
        setupSearch()
        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        EventBus.getDefault().register(this)
        applyAccent()
        loadConversations()
        checkDefaultSmsApp()
    }

    override fun onPause() {
        EventBus.getDefault().unregister(this)
        super.onPause()
    }

    // ─── Setup ─────────────────────────────────────────────────────────────

    private fun setupConversationList() {
        conversationsAdapter = ConversationsAdapter(
            onConversationClick = { conversation -> openThread(conversation) },
            onConversationLongClick = { conversation -> showConversationContextMenu(conversation) },
            onConversationMenuClick = { conversation -> showConversationContextMenu(conversation) }
        )

        binding.rvConversations.apply {
            adapter = conversationsAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
            // When D-Pad UP leaves the top of the list, move focus to toolbar
            onTopEdgeReached = {
                binding.btnNewConversation.requestFocus()
            }
        }
    }

    private fun setupToolbar() {
        binding.btnNewConversation.setOnClickListener {
            startActivity(Intent(this, NewConversationActivity::class.java))
        }
        binding.btnSearch.setOnClickListener { showSearch() }
        binding.btnOverflow.setOnClickListener { showOverflowMenu() }

        // D-Pad DOWN from any toolbar button → focus first conversation
        val enterList = View.OnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                binding.rvConversations.focusFirstItem()
                true
            } else false
        }
        binding.btnNewConversation.setOnKeyListener(enterList)
        binding.btnSearch.setOnKeyListener(enterList)
        binding.btnOverflow.setOnKeyListener(enterList)

        applyAccent()
    }

    private fun applyAccent() {
        val accent = ThemeManager.accentColor(this)
        val tint = ColorStateList.valueOf(accent)

        binding.btnNewConversation.imageTintList = tint
        binding.btnSearch.imageTintList = tint
        binding.btnOverflow.imageTintList = tint
        binding.btnSearchClose.imageTintList = tint

        binding.btnNewConversation.backgroundTintList = tint
        binding.btnSearch.backgroundTintList = tint
        binding.btnOverflow.backgroundTintList = tint
        binding.btnSearchClose.backgroundTintList = tint
    }

    private fun setupSearch() {
        binding.btnSearchClose.setOnClickListener { hideSearch() }
        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                filterConversations(s?.toString() ?: "")
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        binding.etSearch.setOnEditorActionListener { _, _, _ -> false }
    }

    // ─── Data loading ───────────────────────────────────────────────────────

    private fun loadConversations() {
        if (!hasRequiredPermissions()) return

        lifecycleScope.launch {
            val conversations = withContext(Dispatchers.IO) {
                getConversationsFromTelephony(App.get().contactHelper)
            }
            displayConversations(conversations)
        }
    }

    private fun displayConversations(conversations: List<Conversation>) {
        conversationsAdapter.submitList(conversations)
        binding.tvEmpty.visibility = if (conversations.isEmpty()) View.VISIBLE else View.GONE

        // Give focus to first item (or the new conversation button if list is empty)
        if (conversations.isEmpty()) {
            binding.btnNewConversation.requestFocus()
        } else {
            binding.rvConversations.post {
                binding.rvConversations.focusFirstItem()
            }
        }
    }

    private fun filterConversations(query: String) {
        // Phase 2: Full search — for now just client-side title/snippet filter
        if (query.length < 2) {
            loadConversations()
            return
        }
        lifecycleScope.launch {
            val all = withContext(Dispatchers.IO) {
                getConversationsFromTelephony(App.get().contactHelper)
            }
            val lower = query.lowercase()
            val filtered = all.filter {
                it.title.lowercase().contains(lower) ||
                it.snippet.lowercase().contains(lower) ||
                it.phoneNumber.contains(lower)
            }
            conversationsAdapter.submitList(filtered)
            binding.tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    // ─── Navigation ─────────────────────────────────────────────────────────

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

    // ─── Search overlay ─────────────────────────────────────────────────────

    private fun showSearch() {
        binding.toolbar.visibility = View.GONE
        binding.searchBar.visibility = View.VISIBLE
        binding.etSearch.requestFocus()
    }

    private fun hideSearch() {
        binding.searchBar.visibility = View.GONE
        binding.toolbar.visibility = View.VISIBLE
        binding.etSearch.text?.clear()
        loadConversations()
        binding.btnSearch.requestFocus()
    }

    private val isSearchVisible get() = binding.searchBar.visibility == View.VISIBLE

    // ─── Context menus ──────────────────────────────────────────────────────

    private fun showConversationContextMenu(conversation: Conversation) {
        val isMuted = Prefs.get().isThreadMuted(conversation.threadId)
        val options = arrayOf(
            if (conversation.read) getString(R.string.mark_as_unread)
            else getString(R.string.mark_as_read),
            if (conversation.pinned) getString(R.string.unpin) else getString(R.string.pin),
            if (conversation.archived) getString(R.string.unarchive)
            else getString(R.string.archive),
            if (isMuted) getString(R.string.unmute_conversation)
            else getString(R.string.mute_conversation),
            getString(R.string.copy_number),
            getString(R.string.move_to_recycle_bin)
        )

        AlertDialog.Builder(this)
            .setTitle(conversation.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> toggleReadState(conversation)
                    1 -> togglePin(conversation)
                    2 -> toggleArchive(conversation)
                    3 -> toggleMute(conversation)
                    4 -> copyNumber(conversation.phoneNumber)
                    5 -> moveToRecycleBin(conversation)
                }
            }
            .create()
            .show()
    }

    private fun showOverflowMenu() {
        val options = arrayOf(
            getString(R.string.archived),
            getString(R.string.recycle_bin),
            getString(R.string.settings)
        )

        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, ArchivedConversationsActivity::class.java))
                    1 -> startActivity(Intent(this, RecycleBinActivity::class.java))
                    2 -> startActivity(Intent(this, SettingsActivity::class.java))
                }
            }
            .show()
    }

    // ─── Conversation actions ───────────────────────────────────────────────

    private fun toggleReadState(conversation: Conversation) {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = App.get().database.conversationsDao()
            dao.markAsRead(conversation.threadId)
        }
        loadConversations()
    }

    private fun togglePin(conversation: Conversation) {
        lifecycleScope.launch(Dispatchers.IO) {
            App.get().database.conversationsDao().setPinned(
                conversation.threadId, !conversation.pinned
            )
        }
        loadConversations()
    }

    private fun toggleArchive(conversation: Conversation) {
        lifecycleScope.launch(Dispatchers.IO) {
            App.get().database.conversationsDao().setArchived(
                conversation.threadId, !conversation.archived
            )
        }
        loadConversations()
    }

    private fun toggleMute(conversation: Conversation) {
        val currentlyMuted = Prefs.get().isThreadMuted(conversation.threadId)
        Prefs.get().setThreadMuted(conversation.threadId, !currentlyMuted)
    }

    private fun copyNumber(phoneNumber: String) {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText("phone", phoneNumber)
        )
    }

    private fun moveToRecycleBin(conversation: Conversation) {
        AlertDialog.Builder(this)
            .setTitle(R.string.move_to_recycle_bin)
            .setMessage(conversation.title)
            .setPositiveButton(R.string.yes) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    App.get().database.conversationsDao().deleteConversation(conversation.threadId)
                }
                loadConversations()
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    // ─── Permissions ────────────────────────────────────────────────────────

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) loadConversations()
    }

    private fun checkDefaultSmsApp() {
        val isDefault = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            roleManager.isRoleHeld(RoleManager.ROLE_SMS)
        } else {
            Telephony.Sms.getDefaultSmsPackage(this) == packageName
        }

        if (!isDefault) {
            requestDefaultSmsApp()
        }
    }

    private fun requestDefaultSmsApp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (!roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
                val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                requestRoleLauncher.launch(intent)
            }
        } else {
            val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
            }
            startActivity(intent)
        }
    }

    // ─── EventBus ──────────────────────────────────────────────────────────

    @Subscribe(threadMode = ThreadMode.MAIN)
    @Suppress("UNUSED_PARAMETER")
    fun onRefreshConversations(event: RefreshConversations) {
        loadConversations()
    }

    // ─── Key handling ───────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                when {
                    isSearchVisible -> { hideSearch(); true }
                    else -> super.onKeyDown(keyCode, event)
                }
            }
            KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_F -> {
                showSearch(); true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
        private const val REQUEST_DEFAULT_SMS = 1002
    }
}
