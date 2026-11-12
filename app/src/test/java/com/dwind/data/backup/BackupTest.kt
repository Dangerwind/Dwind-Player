package com.dwind.data.backup

import com.dwind.data.local.entity.TrackEntity
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupTest {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private fun track(
        id: String,
        uri: String,
        fileName: String,
        folderPath: String? = null,
        title: String? = null
    ) = TrackEntity(
        id = id,
        uri = uri,
        fileName = fileName,
        title = title,
        artist = null,
        durationMs = null,
        folderPath = folderPath
    )

    @Test
    fun matcherPrefersUri() {
        val matcher = TrackMatcher(
            listOf(
                track("1", "content://media/1", "song.mp3", "/Music"),
                track("2", "content://media/2", "song.mp3", "/Music")
            )
        )
        val result = matcher.match(
            BackupTrack(trackId = "old", uri = "content://media/2", fileName = "song.mp3", folderPath = "/Music")
        )
        assertEquals("2", result?.id)
    }

    @Test
    fun matcherFallsBackToFileNameAndFolder() {
        val matcher = TrackMatcher(
            listOf(
                track("1", "content://media/10", "song.mp3", "/Music/A"),
                track("2", "content://media/20", "song.mp3", "/Music/B")
            )
        )
        val result = matcher.match(
            BackupTrack(
                trackId = "old",
                uri = "content://media/99",
                fileName = "song.mp3",
                folderPath = "/Music/B"
            )
        )
        assertEquals("2", result?.id)
    }

    @Test
    fun matcherUsesUniqueFileNameWhenFolderUnknown() {
        val matcher = TrackMatcher(
            listOf(track("1", "content://media/10", "unique.mp3", "/Music/A"))
        )
        val result = matcher.match(
            BackupTrack(trackId = "old", uri = "content://media/99", fileName = "unique.mp3")
        )
        assertEquals("1", result?.id)
    }

    @Test
    fun matcherRejectsAmbiguousFileName() {
        val matcher = TrackMatcher(
            listOf(
                track("1", "content://media/10", "same.mp3", "/Music/A"),
                track("2", "content://media/20", "same.mp3", "/Music/B")
            )
        )
        val result = matcher.match(
            BackupTrack(trackId = "old", uri = "content://media/99", fileName = "same.mp3")
        )
        assertNull(result)
    }

    @Test
    fun matcherFindsBpmEntryByFolder() {
        val matcher = TrackMatcher(
            listOf(
                track("1", "content://media/10", "song.mp3", "/Music/A"),
                track("2", "content://media/20", "song.mp3", "/Music/B")
            )
        )
        val result = matcher.matchBpm(
            BackupBpm(
                uri = "content://media/99",
                fileName = "song.mp3",
                folderPath = "/Music/B",
                bpm = 128.0
            )
        )
        assertEquals("2", result?.id)
    }

    @Test
    fun backupJsonRoundTrip() {
        val original = BackupFile(
            exportedAt = 123L,
            appVersion = "1.3.0",
            playlists = listOf(
                BackupPlaylist(
                    id = "playlist-1",
                    name = "Party",
                    createdAt = 10L,
                    updatedAt = 20L,
                    tracks = listOf(
                        BackupTrack(
                            trackId = "track-1",
                            position = 0,
                            uri = "content://media/1",
                            fileName = "song.mp3",
                            title = "Song",
                            artist = "Artist",
                            durationMs = 180_000L,
                            folderPath = "/Music"
                        )
                    )
                )
            ),
            bpm = listOf(
                BackupBpm(
                    uri = "content://media/1",
                    fileName = "song.mp3",
                    title = "Song",
                    folderPath = "/Music",
                    bpm = 128.0,
                    source = "manual",
                    genre = "Rock",
                    lastUpdated = 30L
                )
            )
        )

        val decoded = json.decodeFromString<BackupFile>(json.encodeToString(original))

        assertEquals(original, decoded)
    }
}
