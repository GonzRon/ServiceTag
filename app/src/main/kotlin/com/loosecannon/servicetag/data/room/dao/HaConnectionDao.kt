package com.loosecannon.servicetag.data.room.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import com.loosecannon.servicetag.data.room.entities.HaConnectionEntity

/** #16 (C9, C11): the device-local Home Assistant connection. Nothing here is exported, merged or packed. */
@Dao
interface HaConnectionDao {
    /**
     * An `UPDATE` in place, else an `INSERT` — never `INSERT OR REPLACE`, whose delete would take every binding on
     * the row by the CASCADE.
     */
    @Transaction
    suspend fun upsert(e: HaConnectionEntity) {
        if (update(e) == 0) insert(e)
    }

    @Update suspend fun update(e: HaConnectionEntity): Int

    @Insert suspend fun insert(e: HaConnectionEntity)

    @Query("SELECT * FROM ha_connection ORDER BY id")
    suspend fun all(): List<HaConnectionEntity>

    /** The schema's CASCADE takes every binding on the row (C9, R16-14). */
    @Query("DELETE FROM ha_connection WHERE id = :id")
    suspend fun delete(id: String)
}
