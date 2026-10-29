package com.dwind.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bpm_library")
data class BpmEntity(
    @PrimaryKey val id: String,
    val trackId: String,
    val uri: String,
    val fileName: String,
    val bpm: Double?,
    val source: String, // "auto" or "manual"
    val status: String, // "ok", "unknown", "error"
    val confidence: Double? = null,
    val method: String? = null,
    val genre: String? = null,
    val genreConfidence: Double? = null,
    val lastUpdated: Long = System.currentTimeMillis()
)
