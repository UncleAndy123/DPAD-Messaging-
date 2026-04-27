package com.dpad.messaging.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddReaction
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.dpad.messaging.data.model.MsgType
import com.dpad.messaging.data.model.SmsMessage
import com.dpad.messaging.util.dpadFocusableItem
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    threadId: Long,
    address: String,
    contactName: String,
    onBack: () -> Unit,
    viewModel: ChatViewModel = viewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    var inputText by remember { mutableStateOf("") }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var showEmojiDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val inputFocus = remember { FocusRequester() }
    val sendFocus = remember { FocusRequester() }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> selectedImageUri = uri }
    )

    LaunchedEffect(threadId) { viewModel.init(threadId, address) }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(contactName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { /* TODO: Schedule Message */ },
                        modifier = Modifier
                            .dpadFocusableItem(
                                onClick = { /* TODO */ },
                                shape = androidx.compose.foundation.shape.CircleShape,
                                borderWidth = 3.dp
                            )
                    ) {
                        Icon(Icons.Default.Schedule, "Schedule Message")
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column {
                    // Sim Selector stub (Fossify Advanced Sending feature)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { /* TODO: Toggle SIM */ },
                            modifier = Modifier
                                .dpadFocusableItem(
                                    onClick = { /* TODO */ },
                                    shape = RoundedCornerShape(12.dp),
                                    borderWidth = 3.dp
                                )
                        ) {
                            Icon(Icons.Default.SimCard, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("SIM 1")
                        }
                    }
                    if (selectedImageUri != null) {
                        Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            AsyncImage(
                                model = selectedImageUri,
                                contentDescription = "Attachment preview",
                                modifier = Modifier
                                    .size(100.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            IconButton(
                                onClick = {
                                    selectedImageUri = null
                                    inputFocus.requestFocus()
                                },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(24.dp)
                                    .dpadFocusableItem(
                                        onClick = {
                                            selectedImageUri = null
                                            inputFocus.requestFocus()
                                        },
                                        shape = androidx.compose.foundation.shape.CircleShape,
                                        borderWidth = 2.dp
                                    )
                                    .background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape)
                            ) {
                                Icon(Icons.Default.Close, "Remove", tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .navigationBarsPadding()
                            .imePadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        modifier = Modifier
                            .dpadFocusableItem(
                                onClick = {
                                    photoPickerLauncher.launch(
                                        androidx.activity.result.PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                },
                                shape = RoundedCornerShape(8.dp),
                                borderWidth = 3.dp
                            )
                    ) {
                        Icon(Icons.Default.AttachFile, "Attach Photo")
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = { showEmojiDialog = true },
                        modifier = Modifier
                            .dpadFocusableItem(
                                onClick = { showEmojiDialog = true },
                                shape = RoundedCornerShape(8.dp),
                                borderWidth = 3.dp
                            )
                    ) {
                        Icon(Icons.Default.AddReaction, "Insert Emoji or Symbol")
                    }
                    Spacer(Modifier.width(4.dp))
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("Text message") },
                        singleLine = false,
                        maxLines = 4,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(inputFocus),
                        shape = RoundedCornerShape(24.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = {
                            viewModel.send(inputText, selectedImageUri)
                            inputText = ""
                            selectedImageUri = null
                        },
                        enabled = (inputText.isNotBlank() || selectedImageUri != null) && !isSending,
                        modifier = Modifier
                            .focusRequester(sendFocus)
                            .dpadFocusableItem(
                                onClick = {
                                    if (inputText.isNotBlank() || selectedImageUri != null) {
                                        viewModel.send(inputText, selectedImageUri)
                                        inputText = ""
                                        selectedImageUri = null
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                borderWidth = 3.dp
                            )
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send")
                    }
                }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageBubble(msg)
            }
        }
    }

    if (showEmojiDialog) {
        EmojiSymbolPicker(
            onSelect = { char ->
                inputText += char
                showEmojiDialog = false
                inputFocus.requestFocus()
            },
            onDismiss = {
                showEmojiDialog = false
                inputFocus.requestFocus()
            }
        )
    }

    LaunchedEffect(Unit) { inputFocus.requestFocus() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiSymbolPicker(onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val commonEmojis = listOf(
        "😀", "😂", "🤣", "😊", "😍", "😘", "🥰", "😎", "🤔", "🙄",
        "👍", "👎", "👏", "🙌", "🤝", "🙏", "💪", "👊", "👋", "✌️",
        "❤️", "💔", "🔥", "✨", "💯", "🎉", "🎊", "🎂", "🎈", "🎁",
        "👀", "🤷", "🤦", "🤦‍♂️", "🤦‍♀️", "🤷‍♂️", "🤷‍♀️", "😢", "😭", "😤"
    )
    val commonSymbols = listOf(
        "!", "?", ".", ",", "@", "#", "$", "%", "&", "*",
        "(", ")", "-", "+", "=", "/", ":", ";", "\"", "'",
        "~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insert Emoji / Symbol") },
        text = {
            var isEmojiTab by remember { mutableStateOf(true) }
            Column(Modifier.fillMaxWidth()) {
                TabRow(selectedTabIndex = if (isEmojiTab) 0 else 1) {
                    Tab(
                        selected = isEmojiTab,
                        onClick = { isEmojiTab = true },
                        text = { Text("Emojis") },
                        modifier = Modifier.dpadFocusableItem(onClick = { isEmojiTab = true }, shape = RoundedCornerShape(4.dp))
                    )
                    Tab(
                        selected = !isEmojiTab,
                        onClick = { isEmojiTab = false },
                        text = { Text("Symbols") },
                        modifier = Modifier.dpadFocusableItem(onClick = { isEmojiTab = false }, shape = RoundedCornerShape(4.dp))
                    )
                }
                Spacer(Modifier.height(8.dp))
                val items = if (isEmojiTab) commonEmojis else commonSymbols
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    modifier = Modifier.height(250.dp)
                ) {
                    items(items) { item ->
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .dpadFocusableItem(
                                    onClick = { onSelect(item) },
                                    shape = RoundedCornerShape(8.dp),
                                    borderWidth = 3.dp
                                )
                        ) {
                            Text(text = item, style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.dpadFocusableItem(onClick = onDismiss, shape = RoundedCornerShape(8.dp))
            ) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun MessageBubble(msg: SmsMessage) {
    val isOwn = msg.type == MsgType.SMS_OUT || msg.type == MsgType.MMS_OUT
    val alignment = if (isOwn) Alignment.End else Alignment.Start
    val bubbleColor = if (isOwn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isOwn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(bubbleColor, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column {
                msg.mmsPartUris.forEach { uri ->
                    AsyncImage(
                        model = uri,
                        contentDescription = "MMS image",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                }
                if (msg.body.isNotBlank()) {
                    Text(msg.body, color = textColor)
                }
            }
        }
        Text(
            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.date)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = if (isOwn) TextAlign.End else TextAlign.Start,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}
