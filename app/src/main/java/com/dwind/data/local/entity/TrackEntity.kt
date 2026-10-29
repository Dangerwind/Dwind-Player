package com.dwind.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val uri: String,
    val fileName: String,
    val title: String?,
    val artist: String?,
    val durationMs: Long?,
    val folderPath: String?
)
