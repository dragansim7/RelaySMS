package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = NumberEntity::class,
            parentColumns = ["id"],
            childColumns = ["numberId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["numberId", "receivedAtEpochMs"]),
        Index(value = ["numberId", "dedupHash"]),
    ]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val numberId: String,
    val fromNumber: String,
    val senderName: String,
    val body: String,
    val source: String,
    val receivedAtEpochMs: Long,
    val dedupHash: String = "",
    val explicitCode: String? = null,
    val serviceName: String? = null,
    val isManual: Boolean = false,
)
