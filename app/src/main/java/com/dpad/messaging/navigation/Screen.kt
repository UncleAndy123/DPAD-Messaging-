package com.dpad.messaging.navigation

sealed class Screen(val route: String) {
    object Conversations : Screen("conversations")
    object Chat : Screen("chat/{threadId}/{address}/{name}") {
        fun createRoute(threadId: Long, address: String, name: String) =
            "chat/$threadId/${encode(address)}/${encode(name)}"
        private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    }
    object NewMessage : Screen("new_message")
    object Settings : Screen("settings")
}
