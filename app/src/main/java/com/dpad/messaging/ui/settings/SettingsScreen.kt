package com.dpad.messaging.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpad.messaging.util.dpadFocusableItem

val presetColors = listOf(
    0xFF1565C0.toInt(), // Default Blue
    0xFF388E3C.toInt(), // Green
    0xFFF57C00.toInt(), // Orange
    0xFF7B1FA2.toInt(), // Purple
    0xFFD32F2F.toInt(), // Red
    0xFF00838F.toInt(), // Cyan
    0xFF4E342E.toInt()  // Brown
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val deliveryReports by viewModel.deliveryReports.collectAsState()
    val useCustomColors by viewModel.useCustomColors.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val primaryColor by viewModel.primaryColor.collectAsState()
    val fontSizeScale by viewModel.fontSizeScale.collectAsState()
    val alwaysShowTimestamps by viewModel.alwaysShowTimestamps.collectAsState()
    val preferSim2 by viewModel.preferSim2.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleSmall) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", modifier = Modifier.size(20.dp))
                    }
                },
                windowInsets = WindowInsets(0.dp)
            )
        },
        contentWindowInsets = WindowInsets(0.dp)
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            item { CategoryHeader("Appearance") }
            item {
                var expanded by remember { mutableStateOf(false) }
                val currentThemeName = when (themeMode) {
                    0 -> "System Default"
                    1 -> "Light Theme"
                    2 -> "Dark Theme"
                    3 -> "AMOLED Pure Black"
                    else -> "System Default"
                }

                SettingsAction(
                    title = "App Theme",
                    subtitle = "Currently: $currentThemeName",
                    onClick = { expanded = !expanded }
                )

                if (expanded) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        listOf(
                            0 to "System Default",
                            1 to "Light Theme",
                            2 to "Dark Theme",
                            3 to "AMOLED Pure Black"
                        ).forEach { (mode, name) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .dpadFocusableItem(
                                        onClick = {
                                            viewModel.setThemeMode(mode)
                                            expanded = false
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        padding = 4.dp
                                    )
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = themeMode == mode, onClick = null)
                                Spacer(Modifier.width(8.dp))
                                Text(name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            item {
                SettingsSwitch(
                    title = "Use Custom Accent Colors",
                    subtitle = "Overrides system material colors",
                    checked = useCustomColors,
                    onCheckedChange = viewModel::toggleCustomColors
                )
            }
            if (useCustomColors) {
                item {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                            Text("Accent Color", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(4.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(presetColors) { colorInt ->
                                    val color = Color(colorInt)
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(color)
                                            .dpadFocusableItem(
                                                onClick = { viewModel.setPrimaryColor(colorInt) },
                                                shape = CircleShape,
                                                borderWidth = 3.dp
                                            )
                                            .clickable { viewModel.setPrimaryColor(colorInt) }
                                    ) {
                                        if (primaryColor == colorInt) {
                                            Icon(Icons.Default.Check, "Selected", tint = Color.White, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                }
            }

            item { CategoryHeader("Conversation & UI") }
            item {
                SettingsSlider(
                    title = "Font Size Scale: ${String.format("%.1fx", fontSizeScale)}",
                    subtitle = "Adjust text size for TV viewing distance",
                    value = fontSizeScale,
                    valueRange = 0.8f..2.0f,
                    steps = 11,
                    onValueChange = viewModel::setFontSize
                )
            }
            item {
                SettingsSwitch(
                    title = "Always Show Timestamps",
                    subtitle = "Show time on every message vs grouped",
                    checked = alwaysShowTimestamps,
                    onCheckedChange = viewModel::toggleTimestamps
                )
            }

            item { CategoryHeader("Advanced Sending") }
            item {
                SettingsSwitch(
                    title = "Prefer SIM 2",
                    subtitle = "Default to SIM 2 for sending SMS",
                    checked = preferSim2,
                    onCheckedChange = viewModel::toggleSimPreference
                )
            }
            item {
                SettingsSwitch(
                    title = "Delivery Reports",
                    subtitle = "Request network confirmation for sent SMS",
                    checked = deliveryReports,
                    onCheckedChange = viewModel::toggleDeliveryReports
                )
            }

            item { CategoryHeader("Privacy & Utility") }
            item {
                SettingsAction(
                    title = "Backup Messages (Stub)",
                    subtitle = "Export to a local XML file",
                    onClick = { /* TODO: Backup */ }
                )
            }
            item {
                SettingsAction(
                    title = "Restore Messages (Stub)",
                    subtitle = "Import from a local XML file",
                    onClick = { /* TODO: Restore */ }
                )
            }
            item {
                SettingsAction(
                    title = "Manage Blocked Numbers (Stub)",
                    subtitle = "View and edit blacklisted numbers",
                    onClick = { /* TODO: Blocklist */ }
                )
            }
        }
    }
}

@Composable
private fun CategoryHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
    )
}

@Composable
private fun SettingsSwitch(
    title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .dpadFocusableItem(
                onClick = { onCheckedChange(!checked) },
                shape = RoundedCornerShape(8.dp),
                borderWidth = 3.dp,
                padding = 4.dp
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SettingsSlider(
    title: String, subtitle: String, value: Float, valueRange: ClosedFloatingPointRange<Float>, steps: Int, onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .dpadFocusableItem(
                onClick = {},
                shape = RoundedCornerShape(8.dp),
                borderWidth = 3.dp,
                padding = 4.dp
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, steps = steps)
    }
}

@Composable
private fun SettingsAction(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .dpadFocusableItem(
                onClick = onClick,
                shape = RoundedCornerShape(8.dp),
                borderWidth = 3.dp,
                padding = 4.dp
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
