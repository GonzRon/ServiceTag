package com.loosecannon.servicetag.data.room.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity

/**
 * Schema v11 (#79, C17; R79-14): a deadline subject's device-local delivery stamp — **never exported,
 * never merged**, the sibling of `schedule_local_delivery`. That table's row cascades from its
 * schedule, so a deadline, which has no schedule, needs its own.
 *
 * Keyed by `(kind, subject_id)`: the deadline kind's name and the id of what it is about (an asset,
 * for a warranty). There is **no foreign key**: a row for an unknown subject is accepted and outlives
 * its subject, because the delivery path forgets a row whose subject is no longer active, and losing
 * the whole table costs at most one repeated warning.
 */
@Entity(tableName = "deadline_local_delivery", primaryKeys = ["kind", "subject_id"])
data class DeadlineLocalDeliveryEntity(
    val kind: String,
    @ColumnInfo(name = "subject_id") val subjectId: String,
    @ColumnInfo(name = "announced_hash") val announcedHash: String,
    @ColumnInfo(name = "announced_boot") val announcedBoot: Int?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
