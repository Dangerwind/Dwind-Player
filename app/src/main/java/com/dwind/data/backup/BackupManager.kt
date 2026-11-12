package com.dwind.data.backup

import android.content.Context
import android.net.Uri
import com.dwind.BuildConfig
import com.dwind.data.local.AppDatabase
import com.dwind.data.local.entity.BpmEntity
import com.dwind.data.local.entity.PlaylistEntity
import com.dwind.data.local.entity.PlaylistTrackCrossRef
import com.dwind.data.local.entity.TrackEntity
import com.dwind.data.model.AudioItem
import com.dwind.data.repo.AudioRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Итог экспорта или импорта — используется для сообщения пользователю. */
data class BackupSummary(
    val playlists: Int = 0,
    val tracks: Int = 0,
    val bpm: Int = 0,
    val skippedTracks: Int = 0,
    val skippedBpm: Int = 0
)

/** Файл не является резервной копией Dwind Player. */
class BackupFormatException(message: String) : Exception(message)

@Serializable
data class BackupFile(
    val format: String = BACKUP_FORMAT,
    val version: Int = BACKUP_VERSION,
    val exportedAt: Long = 0L,
    val appVersion: String = "",
    val playlists: List<BackupPlaylist> = emptyList(),
    val bpm: List<BackupBpm> = emptyList()
)

@Serializable
data class BackupPlaylist(
    val id: String,
    val name: String,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val tracks: List<BackupTrack> = emptyList()
)

@Serializable
data class BackupTrack(
    val trackId: String,
    val position: Int = 0,
    val uri: String,
    val fileName: String,
    val title: String? = null,
    val artist: String? = null,
    val durationMs: Long? = null,
    val folderPath: String? = null
)

@Serializable
data class BackupBpm(
    val uri: String,
    val fileName: String,
    val title: String? = null,
    val folderPath: String? = null,
    val bpm: Double,
    val source: String = "auto",
    val confidence: Double? = null,
    val method: String? = null,
    val genre: String? = null,
    val genreConfidence: Double? = null,
    val lastUpdated: Long = 0L
)

private const val BACKUP_FORMAT = "dwind-backup"
private const val BACKUP_VERSION = 1

/**
 * Экспорт и импорт плейлистов и известных значений BPM в JSON-файл.
 *
 * Формат не привязан к id медиатеки: при импорте треки заново сопоставляются
 * с текущей библиотекой по URI, а если он изменился — по имени файла, папке
 * и названию. Это позволяет восстановить данные после переустановки.
 */
class BackupManager(private val context: Context) {
    private val db = AppDatabase.getInstance(context)

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun exportTo(uri: Uri): BackupSummary = withContext(Dispatchers.IO) {
        val playlists = db.playlistDao().getAllPlaylistsOnce()
        val refsByPlaylist = db.playlistDao().getAllCrossRefsOnce().groupBy { it.playlistId }
        val tracksById = db.trackDao().getAllTracksOnce().associateBy { it.id }
        val bpmEntries = db.bpmDao().getAllOnce().filter { it.bpm?.isFinite() == true }

        val backup = BackupFile(
            exportedAt = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            playlists = playlists.map { playlist ->
                val tracks = refsByPlaylist[playlist.id].orEmpty()
                    .sortedBy { it.position }
                    .mapNotNull { ref ->
                        val track = tracksById[ref.trackId] ?: return@mapNotNull null
                        BackupTrack(
                            trackId = track.id,
                            position = ref.position,
                            uri = track.uri,
                            fileName = track.fileName,
                            title = track.title,
                            artist = track.artist,
                            durationMs = track.durationMs,
                            folderPath = track.folderPath
                        )
                    }
                BackupPlaylist(
                    id = playlist.id,
                    name = playlist.name,
                    createdAt = playlist.createdAt,
                    updatedAt = playlist.updatedAt,
                    tracks = tracks
                )
            },
            bpm = bpmEntries.map { entry ->
                val track = tracksById[entry.trackId]
                BackupBpm(
                    uri = entry.uri,
                    fileName = entry.fileName,
                    title = track?.title,
                    folderPath = track?.folderPath,
                    bpm = entry.bpm!!,
                    source = entry.source,
                    confidence = entry.confidence,
                    method = entry.method,
                    genre = entry.genre,
                    genreConfidence = entry.genreConfidence,
                    lastUpdated = entry.lastUpdated
                )
            }
        )

        val payload = json.encodeToString(backup)
        context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
            writer.write(payload)
        } ?: throw IllegalStateException("Cannot open output file")

        BackupSummary(
            playlists = backup.playlists.size,
            tracks = backup.playlists.sumOf { it.tracks.size },
            bpm = backup.bpm.size
        )
    }

    suspend fun importFrom(uri: Uri): BackupSummary = withContext(Dispatchers.IO) {
        val payload = context.contentResolver.openInputStream(uri)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?: throw IllegalStateException("Cannot open input file")

        val backup = try {
            json.decodeFromString<BackupFile>(payload)
        } catch (e: Exception) {
            throw BackupFormatException(e.message ?: "Invalid JSON")
        }
        if (backup.format != BACKUP_FORMAT) {
            throw BackupFormatException("Unknown backup format: ${backup.format}")
        }

        // После переустановки MediaStore-id могли измениться, поэтому сначала
        // обновляем локальную таблицу треков по текущей медиатеке.
        val mediaTracks = runCatching { AudioRepository(context).getAllAudio() }
            .getOrDefault(emptyList())
        if (mediaTracks.isNotEmpty()) {
            db.trackDao().upsertTracks(mediaTracks.map { it.toEntity() })
        }

        val matcher = TrackMatcher(db.trackDao().getAllTracksOnce())
        val existingPlaylists = db.playlistDao().getAllPlaylistsOnce().associateBy { it.id }
        val refsByPlaylist = db.playlistDao().getAllCrossRefsOnce().groupBy { it.playlistId }

        var importedTracks = 0
        var skippedTracks = 0

        for (playlist in backup.playlists) {
            val existing = existingPlaylists[playlist.id]
            if (existing == null) {
                db.playlistDao().insertPlaylist(
                    PlaylistEntity(
                        id = playlist.id,
                        name = playlist.name,
                        createdAt = playlist.createdAt,
                        updatedAt = playlist.updatedAt
                    )
                )
            }

            val existingIds = refsByPlaylist[playlist.id].orEmpty().map { it.trackId }.toSet()
            var nextPosition = (refsByPlaylist[playlist.id].orEmpty().maxOfOrNull { it.position } ?: -1) + 1
            val newRefs = mutableListOf<PlaylistTrackCrossRef>()

            for (track in playlist.tracks.sortedBy { it.position }) {
                val current = matcher.match(track)
                if (current == null) {
                    skippedTracks++
                    continue
                }
                if (current.id in existingIds) continue
                if (newRefs.any { it.trackId == current.id }) continue
                newRefs += PlaylistTrackCrossRef(
                    playlistId = playlist.id,
                    trackId = current.id,
                    position = nextPosition++
                )
            }

            if (newRefs.isNotEmpty()) {
                db.playlistDao().addTracksToPlaylist(newRefs)
                importedTracks += newRefs.size
            }
        }

        val existingBpm = db.bpmDao().getAllOnce().associateBy { it.trackId }
        val bpmToSave = mutableListOf<BpmEntity>()
        var skippedBpm = 0

        for (entry in backup.bpm) {
            if (!entry.bpm.isFinite()) {
                skippedBpm++
                continue
            }
            val current = matcher.matchBpm(entry)
            if (current == null) {
                skippedBpm++
                continue
            }
            val existing = existingBpm[current.id]
            // Ручное значение важнее автоматического из резервной копии.
            if (existing?.source == "manual" && entry.source != "manual") continue
            bpmToSave += BpmEntity(
                id = current.id,
                trackId = current.id,
                uri = current.uri,
                fileName = current.fileName,
                bpm = entry.bpm,
                source = entry.source,
                status = "ok",
                confidence = entry.confidence,
                method = entry.method,
                genre = entry.genre,
                genreConfidence = entry.genreConfidence,
                lastUpdated = entry.lastUpdated
            )
        }
        if (bpmToSave.isNotEmpty()) {
            db.bpmDao().upsertAll(bpmToSave)
        }

        BackupSummary(
            playlists = backup.playlists.size,
            tracks = importedTracks,
            bpm = bpmToSave.size,
            skippedTracks = skippedTracks,
            skippedBpm = skippedBpm
        )
    }
}

/**
 * Сопоставление треков из резервной копии с текущей библиотекой.
 * Приоритет: URI → имя файла и папка → имя файла и название → уникальное имя.
 */
internal class TrackMatcher(tracks: List<TrackEntity>) {
    private val byUri = tracks.associateBy { it.uri }
    private val byNameAndFolder = tracks.groupBy { key(it.fileName, it.folderPath) }
    private val byNameAndTitle = tracks.groupBy { key(it.fileName, it.title) }
    private val byName = tracks.groupBy { it.fileName.trim().lowercase() }

    fun match(track: BackupTrack): TrackEntity? =
        byUri[track.uri]
            ?: only(byNameAndFolder[key(track.fileName, track.folderPath)])
            ?: only(byNameAndTitle[key(track.fileName, track.title)])
            ?: only(byName[track.fileName.trim().lowercase()])

    fun matchBpm(entry: BackupBpm): TrackEntity? =
        byUri[entry.uri]
            ?: only(byNameAndFolder[key(entry.fileName, entry.folderPath)])
            ?: only(byNameAndTitle[key(entry.fileName, entry.title)])
            ?: only(byName[entry.fileName.trim().lowercase()])

    private fun only(candidates: List<TrackEntity>?): TrackEntity? =
        candidates?.takeIf { it.size == 1 }?.first()

    private fun key(name: String?, other: String?): String =
        "${name?.trim()?.lowercase().orEmpty()}\u0000${other?.trim()?.lowercase().orEmpty()}"
}

private fun AudioItem.toEntity(): TrackEntity = TrackEntity(
    id = id.toString(),
    uri = uri,
    fileName = fileName,
    title = title.ifBlank { null },
    artist = artist,
    durationMs = duration,
    folderPath = parentPath
)
