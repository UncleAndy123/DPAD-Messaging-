package com.dpad.messaging

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.dpad.messaging.navigation.AppNavGraph
import com.dpad.messaging.navigation.Screen
import com.dpad.messaging.ui.theme.DpadMessagingTheme
import com.dpad.messaging.data.repository.SettingsRepository
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Do NOT call enableEdgeToEdge() — this device has a persistent IME bar
        // that is always reported as visible, causing incorrect inset calculations.
        // Traditional window fitting gives correct layout without manual inset handling.
        
        val settingsRepository = SettingsRepository(applicationContext)
        
        setContent {
            val themeMode by settingsRepository.themeMode.collectAsState(initial = 0)
            val primaryColorInt by settingsRepository.primaryColor.collectAsState(initial = 0xFF1565C0.toInt())
            val useCustomColors by settingsRepository.useCustomColors.collectAsState(initial = false)
            val fontSizeScale by settingsRepository.fontSizeScale.collectAsState(initial = 1.0f)
            
            DpadMessagingTheme(
                themeMode = themeMode,
                customPrimaryColor = if (useCustomColors) primaryColorInt else null,
                fontSizeScale = fontSizeScale
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PermissionAndDefaultSmsGate()
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun PermissionAndDefaultSmsGate() {
    val context = androidx.compose.ui.platform.LocalContext.current
    
    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_CONTACTS
        )
    )

    // State to track if we are the default SMS app
    var isDefaultSms by remember { 
        mutableStateOf(Telephony.Sms.getDefaultSmsPackage(context) == context.packageName) 
    }

    val defaultSmsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        isDefaultSms = Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
    }

    LaunchedEffect(Unit) {
        if (!permissionsState.allPermissionsGranted) {
            permissionsState.launchMultiplePermissionRequest()
        }
    }

    when {
        !permissionsState.allPermissionsGranted -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Text(
                        "DPAD Messenger needs SMS and Contacts permissions to work.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { permissionsState.launchMultiplePermissionRequest() }) {
                        Text("Grant Permissions")
                    }
                }
            }
        }
        !isDefaultSms -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Text(
                        "To manage your messages properly, please set DPAD Messenger as your default SMS app.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val roleManager = context.getSystemService(RoleManager::class.java)
                            if (roleManager?.isRoleAvailable(RoleManager.ROLE_SMS) == true) {
                                defaultSmsLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS))
                            }
                        } else {
                            val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                            intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
                            defaultSmsLauncher.launch(intent)
                        }
                    }) {
                        Text("Set as Default SMS App")
                    }
                }
            }
        }
        else -> {
            val activity = context as? android.app.Activity
            val startRoute = remember {
                val tid = activity?.intent?.getLongExtra("threadId", -1L) ?: -1L
                val addr = activity?.intent?.getStringExtra("address") ?: ""
                if (tid > 0 && addr.isNotBlank()) {
                    Screen.Chat.createRoute(tid, addr, addr)
                } else {
                    Screen.Conversations.route
                }
            }
            val navController = rememberNavController()
            AppNavGraph(navController = navController, startRoute = startRoute)
        }
    }
}
