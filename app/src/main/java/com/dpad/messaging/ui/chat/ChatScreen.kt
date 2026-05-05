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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
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
    val hasMore by viewModel.hasMore.collectAsState()
    val draftText by viewModel.draftText.collectAsState()
    val sendError by viewModel.sendError.collectAsState()
    var inputText by remember { mutableStateOf("") }
    var draftSeeded by remember { mutableStateOf(false) }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var showEmojiDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val inputFocus = remember { FocusRequester() }
    val sendFocus = remember { FocusRequester() }
    val snackbarHostState = remember { SnackbarHostState() }

    // Show send errors as a snackbar
    LaunchedEffect(sendError) {
        if (sendError != null) {
            snackbarHostState.showSnackbar(sendError!!)
            viewModel.clearSendError()
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> selectedImageUri = uri }
    )

    LaunchedEffect(threadId) { viewModel.init(threadId, address) }

    // Seed input once from saved draft
    LaunchedEffect(draftText) {
        if (!draftSeeded) {
            inputText = draftText
            draftSeeded = true
        }
    }

    // Scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    // DO NOT auto-focus the input field — it summons the soft keyboard and blocks the screen.
    // Focus starts on the send button (visible, non-keyboard) so the user can navigate freely.
    LaunchedEffect(Unit) {
        try { sendFocus.requestFocus() } catch (_: Exception) {}
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
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
                    // Attachment preview
                    if (selectedImageUri != null) {
                        Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            AsyncImage(
                                model = selectedImageUri,
                                contentDescription = "Attachment preview",
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            IconButton(
                                onClick = { selectedImageUri = null },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(20.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape)
                                    .dpadFocusableItem(
                                        onClick = { selectedImageUri = null },
                                        shape = androidx.compose.foundation.shape.CircleShape,
                                        borderWidth = 2.dp
                                    )
                            ) {
                                Icon(Icons.Default.Close, "Remove", tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }
                    }

                    // Input row: [attach] [emoji] [text field] [send]
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

                        val focusManager = LocalFocusManager.current
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { newText ->
                                inputText = newText
                                viewModel.onDraftChanged(newText)
                            },
                            placeholder = { Text("Message", style = MaterialTheme.typography.bodySmall) },
                            singleLine = false,
                            maxLines = 3,
                            textStyle = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 2.dp)
                                .focusRequester(inputFocus)
                                .onPreviewKeyEvent { event ->
                                    when {
                                        // Center / Enter key → send (most natural dumbphone action)
                                        event.type == KeyEventType.KeyUp &&
                                        (event.key == Key.DirectionCenter || event.key == Key.Enter) -> {
                                            if (inputText.isNotBlank() || selectedImageUri != null) {
                                                viewModel.send(inputText, selectedImageUri)
                                                inputText = ""
                                                selectedImageUri = null
                                            }
                                            true
                                        }
                                        // D-pad Down → escape to Send button
                                        event.type == KeyEventType.KeyUp &&
                                        event.key == Key.DirectionDown -> {
                                            sendFocus.requestFocus()
                                            true
                                        }
                                        // D-pad Up → escape upward to message list
                                        event.type == KeyEventType.KeyUp &&
                                        event.key == Key.DirectionUp -> {
                                            focusManager.moveFocus(FocusDirection.Up)
                                            true
                                        }
                                        else -> false
                                    }
                                },
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
            if (hasMore) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        TextButton(
                            onClick = { viewModel.loadEarlier() },
                            modifier = Modifier.dpadFocusableItem(
                                onClick = { viewModel.loadEarlier() },
                                shape = RoundedCornerShape(6.dp)
                            )
                        ) {
                            Text("Load earlier", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            items(messages, key = { it.id }) { msg ->
                MessageBubble(msg, onDeleteMessage = { viewModel.deleteMessage(it) })
            }
        }
    }

    if (showEmojiDialog) {
        EmojiSymbolPicker(
            onSelect = { char ->
                inputText += char
                viewModel.onDraftChanged(inputText)
                showEmojiDialog = false
                inputFocus.requestFocus()
            },
            onDismiss = {
                showEmojiDialog = false
            }
        )
    }
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
                    modifier = Modifier.height(180.dp)
                ) {
                    items(items) { item ->
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .dpadFocusableItem(
                                    onClick = { onSelect(item) },
                                    shape = RoundedCornerShape(6.dp),
                                    borderWidth = 3.dp
                                )
                        ) {
                            Text(text = item, fontSize = 18.sp)
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
    val context = LocalContext.current

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
                .widthIn(max = 220.dp)
                .background(bubbleColor, RoundedCornerShape(12.dp))
                .dpadFocusableItem(
                    onClick = { showMsgOptions = true },
                    shape = RoundedCornerShape(12.dp),
                    borderWidth = 2.dp
                )
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Column {
                // Prefer new richer mmsParts model. Fall back to the older string URIs for
                // backward compatibility. Each part will attempt to load via Coil; onError
                // we fallback to reading the provider stream directly (handled by a small
                // helper that we'll inline here for simplicity).
                val parts = if (msg.mmsParts.isNotEmpty()) msg.mmsParts else msg.mmsPartUris.mapIndexed { i, uri ->
                    // Create synthetic MmsPart descriptors for legacy string list
                    com.dpad.messaging.data.model.MmsPart(i.toLong(), uri, "image/*", null, true)
                }

                parts.forEach { part ->
                    // Track whether Coil reported a load failure for this part
                    val coilFailed = remember { mutableStateOf(false) }
                    // Hold bytes read from the provider for fallback rendering
                    val fallbackBytes = remember(part.uri) { mutableStateOf<ByteArray?>(null) }

                    val imageRequest = ImageRequest.Builder(context)
                        .data(part.uri)
                        .size(240, 160)
                        .listener(
                            onError = { _, result ->
                                coilFailed.value = true
                                android.util.Log.e("Coil", "Failed to load MMS image uri=${part.uri}", result.throwable)
                            },
                            onSuccess = { _, _ ->
                                coilFailed.value = false
                                android.util.Log.e("Coil", "Loaded MMS image uri=${part.uri}")
                            }
                        )
                        .build()

                    // Primary attempt: let Coil try to load the content URI
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = "MMS image",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 80.dp)
                    )

                    // If Coil failed, or the provider indicated the part has no data field,
                    // attempt to read the InputStream and decode bytes as a fallback.
                    if ((coilFailed.value || !part.hasData) && fallbackBytes.value == null) {
                        LaunchedEffect(part.uri, coilFailed.value) {
                            try {
                                val resolver = context.contentResolver
                                val uri = android.net.Uri.parse(part.uri)
                                // If this is a file:// URI (provider exposed _data path), read directly
                                if (uri.scheme == "file") {
                                    try {
                                        val f = java.io.File(uri.path ?: "")
                                        if (f.exists() && f.canRead()) {
                                            fallbackBytes.value = f.readBytes()
                                        } else {
                                            android.util.Log.e("MmsFallback", "file URI not readable ${part.uri}")
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("MmsFallback", "Failed to read file URI ${part.uri}", e)
                                    }
                                } else {
                                    resolver.openInputStream(uri)?.use { stream ->
                                        fallbackBytes.value = stream.readBytes()
                                    } ?: android.util.Log.e("MmsFallback", "openInputStream returned null for ${part.uri}")
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("MmsFallback", "Fallback failed to read ${part.uri}", e)
                            }
                        }
                    }

                    // If we obtained bytes from the provider, ask Coil to decode them so
                    // we reuse its decoding and caching behavior.
                    fallbackBytes.value?.let { bytes ->
                        val req = ImageRequest.Builder(context)
                            .data(bytes)
                            .size(240, 160)
                            .build()
                        AsyncImage(
                            model = req,
                            contentDescription = "MMS image",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 80.dp)
                        )
                    }

                    Spacer(Modifier.height(2.dp))
                }
                if (msg.body.isNotBlank()) {
                    Text(msg.body, color = textColor, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Timestamp + delivery state
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
