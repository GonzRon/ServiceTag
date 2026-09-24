package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.ports.ConditionRepository
import com.loosecannon.servicetag.core.ports.HealthSubjectRepository
import com.loosecannon.servicetag.core.ports.SeasonActivationRepository
import com.loosecannon.servicetag.data.room.dao.AssetConditionDao
import com.loosecannon.servicetag.data.room.dao.HealthSubjectDao
import com.loosecannon.servicetag.data.room.dao.SeasonActivationDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// The adapters for schema v8's three new ports. Thin, as every adapter here is: the rules about a
// season, a condition or a subject live in the use cases that write them, and nothing here decides
// anything. The two fact adapters are insert and query only, exactly as their ports and DAOs are.

class RoomSeasonActivationRepository(private val dao: SeasonActivationDao) : SeasonActivationRepository {
    override suspend fun insert(row: SeasonActivation) = dao.insert(row.toEntity())

    override suspend fun forAsset(assetId: AssetId): List<SeasonActivation> =
        dao.forAsset(assetId.value).map { it.toDomain() }

    override suspend fun all(): List<SeasonActivation> = dao.all().map { it.toDomain() }

    override fun observeForAsset(assetId: AssetId): Flow<List<SeasonActivation>> =
        dao.observeForAsset(assetId.value).map { rows -> rows.map { it.toDomain() } }
}

class RoomConditionRepository(private val dao: AssetConditionDao) : ConditionRepository {
    override suspend fun insert(row: AssetCondition) = dao.insert(row.toEntity())

    override suspend fun forAsset(assetId: AssetId): List<AssetCondition> =
        dao.forAsset(assetId.value).map { it.toDomain() }

    override suspend fun all(): List<AssetCondition> = dao.all().map { it.toDomain() }

    override fun observeForAsset(assetId: AssetId): Flow<List<AssetCondition>> =
        dao.observeForAsset(assetId.value).map { rows -> rows.map { it.toDomain() } }
}

class RoomHealthSubjectRepository(private val dao: HealthSubjectDao) : HealthSubjectRepository {
    override suspend fun upsert(subject: HealthSubject) = dao.upsert(subject.toEntity())

    override suspend fun get(id: HealthSubjectId): HealthSubject? = dao.byId(id.value)?.toDomain()

    override suspend fun forAsset(assetId: AssetId): List<HealthSubject> =
        dao.forAsset(assetId.value).map { it.toDomain() }

    override suspend fun forSchedule(id: ScheduleId): List<HealthSubject> =
        dao.forSchedule(id.value).map { it.toDomain() }

    override suspend fun all(): List<HealthSubject> = dao.all().map { it.toDomain() }

    override fun observeForAsset(assetId: AssetId): Flow<List<HealthSubject>> =
        dao.observeForAsset(assetId.value).map { rows -> rows.map { it.toDomain() } }
}
