package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "custom_sources")
data class CustomSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val urlTemplate: String,
    val parserType: String = PARSER_JSON,
    val jsonArrayPath: String = "",
    val jsonFromKey: String = "from",
    val jsonTextKey: String = "text",
    val jsonCodeKey: String = "code",
    val jsonTimeKey: String = "received",
    val jsonServiceKey: String = "service",
    val htmlRegexPattern: String = "",
    val cacheSeconds: Int = 15,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
) {
    companion object {
        const val PARSER_JSON = "JSON"
        const val PARSER_HTML_REGEX = "HTML_REGEX"
    }
}
