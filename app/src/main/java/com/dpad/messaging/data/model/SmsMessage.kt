package com.dpad.messaging.data.model

enum class MsgType { SMS_IN, SMS_OUT, MMS_IN, MMS_OUT }

data class SmsMessage(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val type: MsgType,
    val mmsPartUris: List<String> = emptyList()   // image/video URIs for MMS
)
