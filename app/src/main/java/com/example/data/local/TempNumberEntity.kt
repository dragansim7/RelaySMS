package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "temp_numbers",
    foreignKeys = [
        ForeignKey(
            entity = NumberEntity::class,
            parentColumns = ["id"],
            childColumns = ["numberId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["mode"]),
        Index(value = ["isPinned"]),
    ]
)
data class TempNumberEntity(
    @PrimaryKey val numberId: String,
    val e164: String,
    val mode: String = MODE_AUTO,
    val sourceType: String = SOURCE_AUTO_DETECT,
    val resolvedSource: String? = null,
    val customSourceId: Long? = null,
    val label: String,
    val notes: String = "",
    val lastSyncEpochMs: Long? = null,
    val status: String = STATUS_SYNCING,
    val lastError: String? = null,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
) {
    companion object {
        const val MODE_AUTO = "AUTO"
        const val MODE_MANUAL = "MANUAL"

        const val SOURCE_AUTO_DETECT = "AUTO_DETECT"
        const val SOURCE_GET_VIRTUAL_PHONE_API = "GET_VIRTUAL_PHONE_API"
        const val SOURCE_ANOSIM = "ANOSIM"
        const val SOURCE_SMS24_ME = "SMS24_ME"
        const val SOURCE_RECEIVE_SMSS_MIRROR = "RECEIVE_SMSS_MIRROR"
        const val SOURCE_QUACKR = "QUACKR"
        const val SOURCE_TEMPSMSS = "TEMPSMSS"
        const val SOURCE_RECEIVE_SMS_FREE = "RECEIVE_SMS_FREE"
        const val SOURCE_RECEIVE_SMS_ONLINE = "RECEIVE_SMS_ONLINE"
        const val SOURCE_PUBLIC_MIRROR = "PUBLIC_MIRROR"
        const val SOURCE_CUSTOM = "CUSTOM"
        const val SOURCE_MANUAL = "MANUAL"

        const val STATUS_ONLINE = "ONLINE"
        const val STATUS_SYNCING = "SYNCING"
        const val STATUS_OFFLINE = "OFFLINE"
        const val STATUS_RATE_LIMITED = "RATE_LIMITED"
        const val STATUS_BOT_BLOCKED = "BOT_BLOCKED"
        const val STATUS_NOT_FOUND = "NOT_FOUND"
        const val STATUS_ERROR = "ERROR"
        const val STATUS_MANUAL = "MANUAL"
    }
}
