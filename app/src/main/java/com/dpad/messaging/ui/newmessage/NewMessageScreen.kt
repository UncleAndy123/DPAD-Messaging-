package com.dpad.messaging.ui.newmessage

import android.content.Context
import android.provider.ContactsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dpad.messaging.util.dpadFocusableItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ContactSuggestion(val name: String, val number: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewMessageScreen(
    onBack: () -> Unit,
    onStartChat: (address: String, name: String) -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<ContactSuggestion>>(emptyList()) }
    val selectedContacts = remember { mutableStateListOf<ContactSuggestion>() }
    val inputFocus = remember { FocusRequester() }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            suggestions = emptyList()
        } else {
            val res = searchContacts(context, query)
            // Filter out already selected contacts
            suggestions = res.filter { c -> selectedContacts.none { it.number == c.number } }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selectedContacts.size > 1) "New Group" else "New Message") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            if (selectedContacts.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = {
                        val addresses = selectedContacts.joinToString(",") { it.number }
                        val names = selectedContacts.joinToString(", ") { it.name }
                        onStartChat(addresses, names)
                    },
                    modifier = Modifier.dpadFocusableItem(
                        onClick = {
                            val addresses = selectedContacts.joinToString(",") { it.number }
                            val names = selectedContacts.joinToString(", ") { it.name }
                            onStartChat(addresses, names)
                        },
                        shape = RoundedCornerShape(16.dp),
                        borderWidth = 3.dp
                    )
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Start chat")
                    Spacer(Modifier.width(8.dp))
                    Text("Start Chat")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
        ) {
            // Selected Contacts Area
            if (selectedContacts.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(selectedContacts) { contact ->
                        InputChip(
                            selected = false,
                            onClick = { selectedContacts.remove(contact) },
                            label = { Text(contact.name) },
                            trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) },
                            modifier = Modifier.dpadFocusableItem(
                                onClick = { selectedContacts.remove(contact) },
                                shape = RoundedCornerShape(8.dp),
                                borderWidth = 3.dp
                            )
                        )
                    }
                }
            }

            // Input Area
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Phone number or name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        if (query.isNotBlank()) {
                            selectedContacts.add(ContactSuggestion(query, query))
                            query = ""
                            inputFocus.requestFocus()
                        }
                    }),
                    modifier = Modifier.weight(1f).focusRequester(inputFocus),
                    shape = RoundedCornerShape(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (query.isNotBlank()) {
                            selectedContacts.add(ContactSuggestion(query, query))
                            query = ""
                            inputFocus.requestFocus()
                        }
                    },
                    enabled = query.isNotBlank(),
                    modifier = Modifier
                        .dpadFocusableItem(
                            onClick = {
                                if (query.isNotBlank()) {
                                    selectedContacts.add(ContactSuggestion(query, query))
                                    query = ""
                                    inputFocus.requestFocus()
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            borderWidth = 3.dp
                        )
                ) {
                    Icon(Icons.Default.Add, "Add to chat")
                }
            }

            Spacer(Modifier.height(12.dp))

            // Suggestions List
            if (suggestions.isEmpty() && query.isBlank()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.GroupAdd, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text("Search for contacts or enter a number", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn {
                    itemsIndexed(suggestions) { _, contact ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .dpadFocusableItem(
                                    onClick = {
                                        selectedContacts.add(contact)
                                        query = ""
                                        inputFocus.requestFocus()
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    borderWidth = 3.dp,
                                    padding = 0.dp
                                )
                                .padding(vertical = 12.dp, horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(contact.name, fontWeight = FontWeight.Medium)
                                Text(contact.number, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) { inputFocus.requestFocus() }
}

private suspend fun searchContacts(context: Context, query: String): List<ContactSuggestion> =
    withContext(Dispatchers.IO) {
        val results = mutableListOf<ContactSuggestion>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val cursor = try {
            context.contentResolver.query(
                uri, projection,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$query%"), "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )
        } catch (e: Exception) { null }
        cursor?.use { c ->
            while (c.moveToNext() && results.size < 30) {
                val name = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                results.add(ContactSuggestion(name, number))
            }
        }
        results.distinctBy { it.number.replace(Regex("[^0-9+]"), "") }
    }
