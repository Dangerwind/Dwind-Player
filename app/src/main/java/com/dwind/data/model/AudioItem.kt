package com.dwind.data.model

data class AudioItem(
    val id: Long,
    val uri: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val duration: Long,
    val fileName: String,
    val dataPath: String,
    val parentPath: String,
    val bucketId: String?,
    val bucketName: String?
)
