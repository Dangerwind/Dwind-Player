package com.dwind.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dwind.data.local.entity.BpmEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BpmDao {
    @Query("SELECT * FROM bpm_library")
    fun getAll(): Flow<List<BpmEntity>>

    @Query("SELECT * FROM bpm_library WHERE trackId = :trackId")
    suspend fun getByTrackId(trackId: String): BpmEntity?

    @Query("SELECT * FROM bpm_library WHERE uri = :uri")
    suspend fun getByUri(uri: String): BpmEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: BpmEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<BpmEntity>)

    @Query("DELETE FROM bpm_library WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM bpm_library WHERE trackId = :trackId")
    suspend fun deleteByTrackId(trackId: String)

    @Query("DELETE FROM bpm_library")
    suspend fun deleteAll()
}
