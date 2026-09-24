package com.loosecannon.servicetag.data.room

import androidx.room3.Database
import androidx.room3.RoomDatabase
import com.loosecannon.servicetag.data.room.dao.AssetConditionDao
import com.loosecannon.servicetag.data.room.dao.AssetDao
import com.loosecannon.servicetag.data.room.dao.AssetReferenceDao
import com.loosecannon.servicetag.data.room.dao.AttachmentDao
import com.loosecannon.servicetag.data.room.dao.DefinitionDao
import com.loosecannon.servicetag.data.room.dao.EventDao
import com.loosecannon.servicetag.data.room.dao.ExternalLinkDao
import com.loosecannon.servicetag.data.room.dao.HealthSubjectDao
import com.loosecannon.servicetag.data.room.dao.MaintenanceGroupDao
import com.loosecannon.servicetag.data.room.dao.MaintenanceScheduleDao
import com.loosecannon.servicetag.data.room.dao.NfcTagDao
import com.loosecannon.servicetag.data.room.dao.OccurrenceClosureDao
import com.loosecannon.servicetag.data.room.dao.ProfileDao
import com.loosecannon.servicetag.data.room.dao.ScheduleLocalDeliveryDao
import com.loosecannon.servicetag.data.room.dao.ScheduleStateDao
import com.loosecannon.servicetag.data.room.dao.SeasonActivationDao
import com.loosecannon.servicetag.data.room.entities.AssetConditionEntity
import com.loosecannon.servicetag.data.room.entities.AssetEntity
import com.loosecannon.servicetag.data.room.entities.AssetEventEntity
import com.loosecannon.servicetag.data.room.entities.AssetReferenceEntity
import com.loosecannon.servicetag.data.room.entities.AssetSeasonActivationEntity
import com.loosecannon.servicetag.data.room.entities.AttachmentEntity
import com.loosecannon.servicetag.data.room.entities.ConsumableUsageEntity
import com.loosecannon.servicetag.data.room.entities.EventProfileEntity
import com.loosecannon.servicetag.data.room.entities.ExternalLinkEntity
import com.loosecannon.servicetag.data.room.entities.HealthSubjectEntity
import com.loosecannon.servicetag.data.room.entities.MaintenanceGroupEntity
import com.loosecannon.servicetag.data.room.entities.MaintenanceGroupMemberEntity
import com.loosecannon.servicetag.data.room.entities.MaintenanceScheduleEntity
import com.loosecannon.servicetag.data.room.entities.MeasurementDefinitionEntity
import com.loosecannon.servicetag.data.room.entities.MeasurementEntity
import com.loosecannon.servicetag.data.room.entities.NfcTagEntity
import com.loosecannon.servicetag.data.room.entities.OccurrenceClosureEntity
import com.loosecannon.servicetag.data.room.entities.ProfileConsumableEntity
import com.loosecannon.servicetag.data.room.entities.ProfileFieldEntity
import com.loosecannon.servicetag.data.room.entities.ScheduleLocalDeliveryEntity
import com.loosecannon.servicetag.data.room.entities.ScheduleProviderEntity
import com.loosecannon.servicetag.data.room.entities.ScheduleStateEntity

@Database(
    entities = [
        AssetEntity::class,
        NfcTagEntity::class,
        ExternalLinkEntity::class,
        MeasurementDefinitionEntity::class,
        EventProfileEntity::class,
        ProfileFieldEntity::class,
        ProfileConsumableEntity::class,
        AssetEventEntity::class,
        MeasurementEntity::class,
        ConsumableUsageEntity::class,
        AttachmentEntity::class,
        MaintenanceGroupEntity::class,
        MaintenanceGroupMemberEntity::class,
        MaintenanceScheduleEntity::class,
        ScheduleProviderEntity::class,
        OccurrenceClosureEntity::class,
        ScheduleStateEntity::class,
        ScheduleLocalDeliveryEntity::class,
        AssetReferenceEntity::class,
        AssetSeasonActivationEntity::class,
        AssetConditionEntity::class,
        HealthSubjectEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun assetDao(): AssetDao
    abstract fun nfcTagDao(): NfcTagDao
    abstract fun externalLinkDao(): ExternalLinkDao
    abstract fun definitionDao(): DefinitionDao
    abstract fun profileDao(): ProfileDao
    abstract fun eventDao(): EventDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun maintenanceGroupDao(): MaintenanceGroupDao
    abstract fun maintenanceScheduleDao(): MaintenanceScheduleDao
    abstract fun occurrenceClosureDao(): OccurrenceClosureDao
    abstract fun scheduleStateDao(): ScheduleStateDao
    abstract fun scheduleLocalDeliveryDao(): ScheduleLocalDeliveryDao
    abstract fun assetReferenceDao(): AssetReferenceDao
    abstract fun seasonActivationDao(): SeasonActivationDao
    abstract fun assetConditionDao(): AssetConditionDao
    abstract fun healthSubjectDao(): HealthSubjectDao
}
