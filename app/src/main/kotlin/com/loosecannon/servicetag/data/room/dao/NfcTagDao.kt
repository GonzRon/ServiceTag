package com.loosecannon.servicetag.data.room.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import com.loosecannon.servicetag.data.room.entities.NfcTagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NfcTagDao {
    /** See [AssetDao.upsert] for why this is not `@Upsert`. */
    @Transaction
    suspend fun upsert(e: NfcTagEntity) {
        if (update(e) == 0) insert(e)
    }

    @Update suspend fun update(e: NfcTagEntity): Int

    @Insert suspend fun insert(e: NfcTagEntity)

    @Query("SELECT * FROM nfc_tag WHERE id = :id")
    suspend fun byId(id: String): NfcTagEntity?

    @Query("SELECT * FROM nfc_tag WHERE payload_format = :format AND payload_key = :key")
    suspend fun byPayload(format: String, key: String): NfcTagEntity?

    @Query("SELECT * FROM nfc_tag WHERE asset_id = :assetId")
    suspend fun forAsset(assetId: String): List<NfcTagEntity>

    @Query("SELECT * FROM nfc_tag WHERE link_id = :linkId")
    suspend fun forLink(linkId: String): List<NfcTagEntity>

    @Query("SELECT * FROM nfc_tag WHERE asset_id = :assetId ORDER BY created_at")
    fun observeForAsset(assetId: String): Flow<List<NfcTagEntity>>

    @Query("SELECT * FROM nfc_tag WHERE link_id = :linkId ORDER BY created_at")
    fun observeForLink(linkId: String): Flow<List<NfcTagEntity>>

    @Query("SELECT * FROM nfc_tag ORDER BY created_at")
    suspend fun all(): List<NfcTagEntity>

    @Query("SELECT * FROM nfc_tag ORDER BY created_at")
    fun observeAll(): Flow<List<NfcTagEntity>>

    @Query("DELETE FROM nfc_tag WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM nfc_tag")
    suspend fun deleteAll()
}
