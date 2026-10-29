package com.dwind.data.repo

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.dwind.data.model.AudioItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AudioRepository(private val context: Context) {
    suspend fun getAllAudio(): List<AudioItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<AudioItem>()
        val contentResolver: ContentResolver = context.contentResolver
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.BUCKET_ID,
            MediaStore.Audio.Media.BUCKET_DISPLAY_NAME
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.DISPLAY_NAME} ASC"

        try {
            contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.BUCKET_ID)
                val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val contentUri = Uri.withAppendedPath(uri, id.toString())
                    val dataPath = cursor.getString(dataCol) ?: ""
                    val parentPath = dataPath.substringBeforeLast("/", "")
                    list.add(
                        AudioItem(
                            id = id,
                            uri = contentUri.toString(),
                            title = cursor.getString(titleCol) ?: "",
                            artist = cursor.getString(artistCol),
                            album = cursor.getString(albumCol),
                            duration = cursor.getLong(durCol),
                            fileName = cursor.getString(nameCol) ?: "",
                            dataPath = dataPath,
                            parentPath = parentPath,
                            bucketId = cursor.getString(bucketIdCol),
                            bucketName = cursor.getString(bucketNameCol)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        list
    }
}
