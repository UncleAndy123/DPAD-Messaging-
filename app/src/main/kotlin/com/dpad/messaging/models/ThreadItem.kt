package com.dpad.messaging.models

/**
 * Sealed class representing the different item types in the message thread RecyclerView.
 */
sealed class ThreadItem {

    /** Date / time separator displayed between messages on different days. */
    data class DateHeader(
        val date: Long
    ) : ThreadItem()

    /** A message currently being sent (spinner shown). */
    data class SendingMessage(
        val message: Message
    ) : ThreadItem()

    /** An outgoing message that was sent (or delivered / failed). */
    data class SentMessage(
        val message: Message
    ) : ThreadItem()

    /** An incoming message from a contact. */
    data class ReceivedMessage(
        val message: Message
    ) : ThreadItem()

    companion object {
        /** Build a flat list of ThreadItems from raw messages, inserting date headers. */
        fun fromMessages(messages: List<Message>): List<ThreadItem> {
            if (messages.isEmpty()) return emptyList()

            val items = mutableListOf<ThreadItem>()
            var lastDateKey = -1L

            for (message in messages) {
                val dayKey = dayKey(message.date)
                if (dayKey != lastDateKey) {
                    items.add(DateHeader(message.date))
                    lastDateKey = dayKey
                }
                items.add(
                    when {
                        message.type == Message.TYPE_OUTBOX ||
                        message.type == Message.TYPE_QUEUED -> SendingMessage(message)
                        message.isIncoming -> ReceivedMessage(message)
                        else -> SentMessage(message)
                    }
                )
            }
            return items
        }

        private fun dayKey(timestamp: Long): Long {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
    }
}
