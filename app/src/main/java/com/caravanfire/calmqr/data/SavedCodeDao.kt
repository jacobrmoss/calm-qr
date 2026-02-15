package com.caravanfire.calmqr.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedCodeDao {

    @Query("SELECT * FROM saved_codes ORDER BY timestamp DESC")
    fun getAllCodes(): Flow<List<SavedCode>>

    @Query("SELECT * FROM saved_codes WHERE name LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchCodes(query: String): Flow<List<SavedCode>>

    @Query("SELECT * FROM saved_codes WHERE id = :id")
    suspend fun getCodeById(id: Long): SavedCode?

    @Insert
    suspend fun insertCode(code: SavedCode): Long

    @Delete
    suspend fun deleteCode(code: SavedCode)

    @Query("DELETE FROM saved_codes WHERE id IN (:ids)")
    suspend fun deleteCodesByIds(ids: List<Long>)

    @Query("UPDATE saved_codes SET name = :name WHERE id = :id")
    suspend fun updateName(id: Long, name: String)
}
