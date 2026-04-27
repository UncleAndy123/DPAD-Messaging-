package com.dpad.messaging.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.dpad.messaging.ui.chat.ChatScreen
import com.dpad.messaging.ui.conversations.ConversationsScreen
import com.dpad.messaging.ui.newmessage.NewMessageScreen
import com.dpad.messaging.ui.settings.SettingsScreen
import java.net.URLDecoder

@Composable
fun AppNavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Screen.Conversations.route) {

        composable(Screen.Conversations.route) {
            ConversationsScreen(
                onOpenChat = { threadId, address, name ->
                    navController.navigate(Screen.Chat.createRoute(threadId, address, name))
                },
                onNewMessage = { navController.navigate(Screen.NewMessage.route) },
                onSettings = { navController.navigate(Screen.Settings.route) }
            )
        }

        composable(
            route = Screen.Chat.route,
            arguments = listOf(
                navArgument("threadId") { type = NavType.LongType },
                navArgument("address") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val threadId = backStackEntry.arguments?.getLong("threadId") ?: -1L
            val address = URLDecoder.decode(backStackEntry.arguments?.getString("address") ?: "", "UTF-8")
            val name = URLDecoder.decode(backStackEntry.arguments?.getString("name") ?: "", "UTF-8")
            ChatScreen(
                threadId = threadId,
                address = address,
                contactName = name,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.NewMessage.route) {
            NewMessageScreen(
                onBack = { navController.popBackStack() },
                onStartChat = { address, name ->
                    navController.navigate(Screen.Chat.createRoute(-1L, address, name)) {
                        popUpTo(Screen.NewMessage.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
