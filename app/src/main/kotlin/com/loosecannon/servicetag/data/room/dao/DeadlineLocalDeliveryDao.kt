package com.loosecannon.servicetag.data.room.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import com.loosecannon.servicetag.data.room.entities.DeadlineLocalDeliveryEntity

/** #79: a deadline's device-local delivery stamp. Nothing here is canonical, exported or merged. */
@Dao
interface DeadlineLocalDeliveryDao {
    @Transaction
    suspend fun upsert(e: DeadlineLocalDeliveryEntity) {
        if (update(e) == 0) insert(e)
    }

    @Update suspend fun update(e: DeadlineLocalDeliveryEntity): Int

    @Insert suspend fun insert(e: DeadlineLocalDeliveryEntity)

    @Query("SELECT * FROM deadline_local_delivery WHERE kind = :kind AND subject_id = :subjectId")
    suspend fun byKey(kind: String, subjectId: String): DeadlineLocalDeliveryEntity?

    @Query("SELECT * FROM deadline_local_delivery ORDER BY kind, subject_id")
    suspend fun all(): List<DeadlineLocalDeliveryEntity>

    @Query("DELETE FROM deadline_local_delivery WHERE kind = :kind AND subject_id = :subjectId")
    suspend fun delete(kind: String, subjectId: String)

    @Query("DELETE FROM deadline_local_delivery")
    suspend fun deleteAll()
}
