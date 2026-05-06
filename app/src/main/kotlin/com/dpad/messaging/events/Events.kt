package com.dpad.messaging.events

/** Fired when the conversation list should be refreshed (e.g. new SMS received). */
class RefreshConversations

/** Fired when the message thread for [threadId] should be refreshed. */
class RefreshMessages(val threadId: Long)

/** Fired when a conversation's read state changes. */
class ConversationReadChanged(val threadId: Long)
