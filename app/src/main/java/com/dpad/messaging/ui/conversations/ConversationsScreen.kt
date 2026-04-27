package com.dpad.messaging.ui.conversations

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Block
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpad.messaging.data.model.SmsThread
import com.dpad.messaging.util.dpadFocusableItem
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    onOpenChat: (threadId: Long, address: String, name: String) -> Unit,
    onNewMessage: () -> Unit,
    onSettings: () -> Unit,
    viewModel: ConversationsViewModel = viewModel()
) {
    val threads by viewModel.threads.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadThreads() }

    val fabFocus = remember { FocusRequester() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Messages") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            var focused by remember { mutableStateOf(false) }
            FloatingActionButton(
                onClick = onNewMessage,
                modifier = Modifier
                    .focusRequester(fabFocus)
                    .dpadFocusableItem(
                        onClick = onNewMessage,
                        shape = androidx.compose.foundation.shape.CircleShape,
                        borderWidth = 3.dp
                    )
            ) {
                Icon(Icons.Default.Edit, contentDescription = "New message")
            }
        }
    ) { padding ->
        when {
            isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            threads.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No messages yet.\nPress the pencil button to start a conversation.",
                    style = MaterialTheme.typography.bodyLarge)
            }
            else -> {
                val listState = rememberLazyListState()
                val focusRequesters = remember(threads.size) { List(threads.size) { FocusRequester() } }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(padding)
                ) {
                    itemsIndexed(threads) { index, thread ->
                        ThreadItem(
                            thread = thread,
                            focusRequester = focusRequesters.getOrNull(index),
                            isFirst = index == 0,
                            onClick = { onOpenChat(thread.threadId, thread.address, thread.contactName) },
                            onPin = { viewModel.togglePin(thread.threadId) },
                            onArchive = { viewModel.toggleArchive(thread.threadId) },
                            onMute = { viewModel.toggleMute(thread.threadId) },
                            onBlock = { viewModel.toggleBlock(thread.threadId) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadItem(
    thread: SmsThread,
    focusRequester: FocusRequester?,
    isFirst: Boolean,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onMute: () -> Unit,
    onBlock: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    var showOptionsDialog by remember { mutableStateOf(false) }

    if (showOptionsDialog) {
        AlertDialog(
            onDismissRequest = { showOptionsDialog = false },
            title = { Text("Conversation Options") },
            text = {
                Column {
                    DialogOption(if (thread.isPinned) "Unpin from Top" else "Pin to Top") { showOptionsDialog = false; onPin() }
                    DialogOption(if (thread.isArchived) "Unarchive" else "Archive") { showOptionsDialog = false; onArchive() }
                    DialogOption(if (thread.isMuted) "Unmute Notifications" else "Mute Notifications") { showOptionsDialog = false; onMute() }
                    DialogOption(if (thread.isBlocked) "Unblock Number" else "Block Number") { showOptionsDialog = false; onBlock() }
                }
            },
            confirmButton = {
                TextButton(onClick = { showOptionsDialog = false }) { Text("Close") }
            }
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .dpadFocusableItem(
                onClick = onClick,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                borderWidth = 3.dp,
                padding = 4.dp
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Initials avatar
        val initials = thread.contactName.split(" ").mapNotNull { it.firstOrNull()?.uppercaseChar() }.take(2).joinToString("")
        val avatarColor = avatarColor(thread.contactName)
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(avatarColor, shape = androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(initials.ifBlank { "?" }, color = Color.White, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = thread.contactName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (thread.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f)
                )
                if (thread.isPinned) {
                    Icon(Icons.Default.PushPin, contentDescription = "Pinned", modifier = Modifier.size(16.dp).padding(end = 4.dp), tint = MaterialTheme.colorScheme.primary)
                }
                if (thread.isArchived) {
                    Icon(Icons.Default.Archive, contentDescription = "Archived", modifier = Modifier.size(16.dp).padding(end = 4.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (thread.isMuted) {
                    Icon(Icons.Default.NotificationsOff, contentDescription = "Muted", modifier = Modifier.size(16.dp).padding(end = 4.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (thread.isBlocked) {
                    Icon(Icons.Default.Block, contentDescription = "Blocked", modifier = Modifier.size(16.dp).padding(end = 4.dp), tint = MaterialTheme.colorScheme.error)
                }
                Text(
                    text = formatDate(thread.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = thread.snippet,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (thread.unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (thread.unreadCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Badge { Text(thread.unreadCount.toString()) }
                }
            }
        }
        
        IconButton(
            onClick = { showOptionsDialog = true },
            modifier = Modifier
                .dpadFocusableItem(
                    onClick = { showOptionsDialog = true },
                    shape = androidx.compose.foundation.shape.CircleShape,
                    borderWidth = 3.dp
                )
        ) {
            Icon(Icons.Default.MoreVert, "Options", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    LaunchedEffect(isFirst) {
        if (isFirst) focusRequester?.requestFocus()
    }
}

@Composable
private fun DialogOption(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .dpadFocusableItem(
                onClick = onClick,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                borderWidth = 3.dp,
                padding = 4.dp
            )
            .padding(16.dp)
    )
}

private fun avatarColor(name: String): androidx.compose.ui.graphics.Color {
    val colors = listOf(
        androidx.compose.ui.graphics.Color(0xFF1976D2),
        androidx.compose.ui.graphics.Color(0xFF388E3C),
        androidx.compose.ui.graphics.Color(0xFFF57C00),
        androidx.compose.ui.graphics.Color(0xFF7B1FA2),
        androidx.compose.ui.graphics.Color(0xFFD32F2F),
        androidx.compose.ui.graphics.Color(0xFF0288D1)
    )
    return colors[name.hashCode().and(0x7FFFFFFF) % colors.size]
}

private fun formatDate(ms: Long): String {
    val now = Calendar.getInstance()
    val cal = Calendar.getInstance().apply { timeInMillis = ms }
    return if (now.get(Calendar.DATE) == cal.get(Calendar.DATE))
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
    else
        SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ms))
}
