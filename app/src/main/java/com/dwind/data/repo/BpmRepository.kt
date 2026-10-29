package com.dwind.data.repo

import android.content.Context
import android.net.Uri
import com.dwind.bpm.BpmResult
import com.dwind.bpm.MediaBpmAnalyzer
import com.dwind.data.local.AppDatabase
import com.dwind.data.local.entity.BpmEntity
import com.dwind.settings.AppSettings
import kotlinx.coroutines.flow.Flow

/**
 * BPM хранилище + расчёт. Определения кэшируются в Room (таблица bpm_library),
 * повторный расчёт для уже известной дорожки не выполняется (если force=false).
 */
class BpmRepository(private val context: Context) {
    private val db = AppDatabase.getInstance(context)
    private val analyzer = MediaBpmAnalyzer()

    fun watchAll(): Flow<List<BpmEntity>> = db.bpmDao().getAll()

    suspend fun getKnownBpm(trackId: String): Double? =
        db.bpmDao().getByTrackId(trackId)?.bpm

    suspend fun saveManual(trackId: String, uri: String, fileName: String, bpm: Double, genre: String? = null) {
        db.bpmDao().upsert(
            BpmEntity(
                id = trackId,
                trackId = trackId,
                uri = uri,
                fileName = fileName,
                bpm = bpm,
                source = "manual",
                status = "ok",
                confidence = null,
                method = "manual",
                genre = genre,
                genreConfidence = null,
                lastUpdated = System.currentTimeMillis()
            )
        )
    }

    suspend fun clearBpm(trackId: String) {
        db.bpmDao().deleteByTrackId(trackId)
    }

    suspend fun clearAllBpm() {
        db.bpmDao().deleteAll()
    }

    /**
     * Возвращает BPM дорожки: из кэша либо после расчёта.
     * Ручное значение (source="manual") имеет приоритет и не перезаписывается авто-расчётом без force.
     * @param force пересчитать даже если значение уже есть
     * @return null если распознать не удалось или расчёт отменён
     */
    suspend fun ensureBpm(
        trackId: String,
        uri: String,
        fileName: String,
        force: Boolean = false,
        isCancelled: () -> Boolean = { false }
    ): BpmEntity? {
        val existing = db.bpmDao().getByTrackId(trackId)
        if (!force && existing != null && existing.bpm != null) return existing
        if (isCancelled()) return existing

        val result: BpmResult = analyzer.analyze(
            context = context,
            uri = Uri.parse(uri),
            isCancelled = isCancelled,
            minBpm = AppSettings.bpmMin.toDouble(),
            maxBpm = AppSettings.bpmMax.toDouble()
        )
        if (result.error == "cancelled") return existing

        val entity = BpmEntity(
            id = trackId,
            trackId = trackId,
            uri = uri,
            fileName = fileName,
            bpm = result.bpm,
            source = "auto",
            status = if (result.bpm != null) "ok" else "unknown",
            confidence = result.confidence,
            method = result.method,
            genre = result.genre,
            genreConfidence = result.genreConfidence,
            lastUpdated = System.currentTimeMillis()
        )
        db.bpmDao().upsert(entity)
        return entity
    }
}
