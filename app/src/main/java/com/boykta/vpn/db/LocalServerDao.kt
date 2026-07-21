package com.boykta.vpn.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalServerDao {

    @Query("SELECT * FROM local_servers ORDER BY importedAt DESC")
    fun getAll(): Flow<List<LocalServer>>

    @Query("SELECT * FROM local_servers WHERE id = :id")
    suspend fun getById(id: Int): LocalServer?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(server: LocalServer): Long

    @Delete
    suspend fun delete(server: LocalServer)

    @Query("DELETE FROM local_servers WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM local_servers WHERE expiresAt < :now")
    suspend fun deleteExpired(now: Long = System.currentTimeMillis())

    /** Fetch the encrypted URI for a given local server id (internal use only) */
    @Query("SELECT encryptedUri FROM local_servers WHERE id = :id")
    suspend fun getEncryptedUri(id: Int): String?
}
