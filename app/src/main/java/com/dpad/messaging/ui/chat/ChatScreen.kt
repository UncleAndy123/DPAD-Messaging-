package com.dpad.messaging.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            // Compact top bar — contact name + back only, no extra actions to save width
            TopAppBar(
                title = {
                    Text(
                        contactName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            "Back",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                windowInsets = WindowInsets(0.dp)
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    // Attachment preview — only shown when image is selected
                    if (selectedImageUri != null) {
                        Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            AsyncImage(
                                model = selectedImageUri,
                                contentDescription = "Attachment preview",
                                modifier = Modifier
                                    .size(72.dp) // smaller preview
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            IconButton(
                                onClick = { selectedImageUri = null; inputFocus.requestFocus() },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(20.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape)
                                    .dpadFocusableItem(
                                        onClick = { selectedImageUri = null; inputFocus.requestFocus() },
                                        shape = androidx.compose.foundation.shape.CircleShape,
                                        borderWidth = 2.dp
                                    )
                            ) {
                                Icon(Icons.Default.Close, "Remove", tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }
                    }

                    // Single compact input row: [attach] [emoji] [text field] [send]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
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
                                .size(36.dp)
                                .dpadFocusableItem(
                                    onClick = {
                                        photoPickerLauncher.launch(
                                            androidx.activity.result.PickVisualMediaRequest(
                                                ActivityResultContracts.PickVisualMedia.ImageOnly
                                            )
                                        )
                                    },
                                    shape = RoundedCornerShape(6.dp),
                                    borderWidth = 3.dp
                                )
                        ) {
                            Icon(Icons.Default.AttachFile, "Attach", modifier = Modifier.size(18.dp))
                        }

                        IconButton(
                            onClick = { showEmojiDialog = true },
                            modifier = Modifier
                                .size(36.dp)
                                .dpadFocusableItem(
                                    onClick = { showEmojiDialog = true },
                                    shape = RoundedCornerShape(6.dp),
                                    borderWidth = 3.dp
                                )
                        ) {
                            Icon(Icons.Default.AddReaction, "Emoji", modifier = Modifier.size(18.dp))
                        }

                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            placeholder = { Text("Message", style = MaterialTheme.typography.bodySmall) },
                            singleLine = false,
                            maxLines = 3,
                            textStyle = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 2.dp)
                                .focusRequester(inputFocus),
                            shape = RoundedCornerShape(20.dp),
                        )

                        IconButton(
                            onClick = {
                                if (inputText.isNotBlank() || selectedImageUri != null) {
                                    viewModel.send(inputText, selectedImageUri)
                                    inputText = ""
                                    selectedImageUri = null
                                }
                            },
                            enabled = (inputText.isNotBlank() || selectedImageUri != null) && !isSending,
                            modifier = Modifier
                                .size(36.dp)
                                .focusRequester(sendFocus)
                                .dpadFocusableItem(
                                    onClick = {
                                        if (inputText.isNotBlank() || selectedImageUri != null) {
                                            viewModel.send(inputText, selectedImageUri)
                                            inputText = ""
                                            selectedImageUri = null
                                        }
                                    },
                                    shape = RoundedCornerShape(6.dp),
                                    borderWidth = 3.dp
                                )
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Send", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0.dp)
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(vertical = 6.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageBubble(msg, onDeleteMessage = { viewModel.deleteMessage(it) })
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
        title = { Text("Emoji / Symbol", style = MaterialTheme.typography.titleSmall) },
        text = {
            var isEmojiTab by remember { mutableStateOf(true) }
            Column(Modifier.fillMaxWidth()) {
                TabRow(selectedTabIndex = if (isEmojiTab) 0 else 1) {
                    Tab(
                        selected = isEmojiTab,
                        onClick = { isEmojiTab = true },
                        text = { Text("Emoji", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.dpadFocusableItem(onClick = { isEmojiTab = true }, shape = RoundedCornerShape(4.dp))
                    )
                    Tab(
                        selected = !isEmojiTab,
                        onClick = { isEmojiTab = false },
                        text = { Text("Symbol", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.dpadFocusableItem(onClick = { isEmojiTab = false }, shape = RoundedCornerShape(4.dp))
                    )
                }
                Spacer(Modifier.height(4.dp))
                val items = if (isEmojiTab) commonEmojis else commonSymbols
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    modifier = Modifier.height(180.dp) // reduced from 250dp
                ) {
                    items(items) { item ->
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp) // reduced from 48dp
                                .dpadFocusableItem(
                                    onClick = { onSelect(item) },
                                    shape = RoundedCornerShape(6.dp),
                                    borderWidth = 3.dp
                                )
                        ) {
                            Text(text = item, fontSize = 18.sp) // down from headlineMedium
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.dpadFocusableItem(onClick = onDismiss, shape = RoundedCornerShape(6.dp))
            ) {
                Text("Close", style = MaterialTheme.typography.labelMedium)
            }
        }
    )
}

@Composable
private fun MessageBubble(msg: SmsMessage, onDeleteMessage: (SmsMessage) -> Unit) {
    val isOwn = msg.type == MsgType.SMS_OUT || msg.type == MsgType.MMS_OUT
    val alignment = if (isOwn) Alignment.End else Alignment.Start
    val bubbleColor = if (isOwn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isOwn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    var showMsgOptions by remember { mutableStateOf(false) }

    if (showMsgOptions) {
        AlertDialog(
            onDismissRequest = { showMsgOptions = false },
            title = { Text("Message", style = MaterialTheme.typography.titleSmall) },
            text = {
                TextButton(onClick = { showMsgOptions = false; onDeleteMessage(msg) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = { showMsgOptions = false }) {
                    Text("Close", style = MaterialTheme.typography.labelMedium)
                }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 220.dp) // reduced from 280dp — screen is only 320dp wide
                .background(bubbleColor, RoundedCornerShape(12.dp))
                .dpadFocusableItem(
                    onClick = { showMsgOptions = true },
                    shape = RoundedCornerShape(12.dp),
                    borderWidth = 2.dp
                )
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Column {
                msg.mmsPartUris.forEach { uri ->
                    AsyncImage(
                        model = uri,
                        contentDescription = "MMS image",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp)
                    )
                    Spacer(Modifier.height(2.dp))
                }
                if (msg.body.isNotBlank()) {
                    Text(msg.body, color = textColor, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Timestamp + status on same compact row
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.date)),
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isOwn) {
                Spacer(Modifier.width(2.dp))
                when (msg.state) {
                    com.dpad.messaging.data.model.DeliveryState.SENDING ->
                        CircularProgressIndicator(strokeWidth = 1.5.dp, modifier = Modifier.size(10.dp))
                    com.dpad.messaging.data.model.DeliveryState.SENT ->
                        Icon(Icons.Default.Check, "Sent", modifier = Modifier.size(10.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    com.dpad.messaging.data.model.DeliveryState.FAILED ->
                        Text("!", fontSize = 9.sp, color = MaterialTheme.colorScheme.error)
                    else -> {}
                }
            }
        }
    }
}
