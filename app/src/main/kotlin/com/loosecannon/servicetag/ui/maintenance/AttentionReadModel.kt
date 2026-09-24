package com.loosecannon.servicetag.ui.maintenance

import com.loosecannon.servicetag.core.condition.ConditionHistory
import com.loosecannon.servicetag.core.health.HealthBand
import com.loosecannon.servicetag.core.health.SubjectValue
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.ConditionRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.ui.health.AssetHealthReadModel
import com.loosecannon.servicetag.ui.health.inService

/** What an asset-level attention row is about: the unit's condition, or one of its health subjects. */
enum class AttentionKind { CONDITION, HEALTH }

/**
 * One asset-level row that **no schedule stands behind** (spec §9.1, §10.2; master plan §11.2):
 * a DOWN or DEGRADED asset or component, or an independent (AGE) health subject scoring CRITICAL or
 * WARNING. `/v1/attention` serialises it and the dashboard draws it beside the schedule rows.
 *
 * Every field is present and null when absent. A CONDITION row carries the current condition row's
 * [condition], [reason] and [occurredOn]; a HEALTH row carries the subject, its [band] and [score].
 * A component names its parent ([parentAssetId], [parentName]; inv. 122) — the parent's name still
 * resolves when the parent itself has left service.
 *
 * [section] is ATTENTION or UPCOMING only; [rank] is the row's position in the one total order
 * (DOWN, DEGRADED, CRITICAL, WARNING; then asset name, asset id, subject order), dense from 0.
 */
data class AttentionItem(
    val kind: AttentionKind,
    val section: AttentionSection,
    val assetId: AssetId,
    val assetName: String,
    val parentAssetId: AssetId?,
    val parentName: String?,
    val condition: OperationalCondition?,
    val reason: String?,
    val occurredOn: String?,
    val healthSubjectId: HealthSubjectId?,
    val subjectName: String?,
    val band: HealthBand?,
    val score: Int?,
    val rank: Int,
)

/**
 * The asset-level half of "what needs me" (master plan §13.1): in-service assets and components
 * only, each on its own lifecycle (status ACTIVE, not retired).
 *
 * Per asset: a current DOWN or DEGRADED condition is one CONDITION row; each **AGE** subject
 * scoring CRITICAL is a HEALTH row in ATTENTION and each scoring WARNING one in UPCOMING. A
 * MAINTENANCE_OVERDUE subject never yields a row here — it rides its schedule's row as
 * `DueItem.health` (plan decision 21) — and a subject NOT TRACKED yields none. A DOWN asset with a
 * CRITICAL subject is two rows, the DOWN one first, so its health never appears without its
 * condition (inv. 119).
 *
 * **It reads and never writes** (inv. 105): health comes from [AssetHealthReadModel], the one
 * computation every health surface shares, and conditions through `ConditionHistory`.
 */
class AttentionReadModel(
    private val assets: AssetRepository,
    private val conditions: ConditionRepository,
    private val health: AssetHealthReadModel,
    private val today: Today,
) {

    suspend fun items(): List<AttentionItem> {
        val t = today.localDate()
        val all = assets.all()
        val byId = all.associateBy { it.id }
        val rows = conditions.all().groupBy { it.assetId }
        val ranked = mutableListOf<Pending>()
        for (asset in all.filter { it.inService }) {
            val parent = asset.parentAssetId?.let { byId[it] }
            val current = ConditionHistory.of(rows[asset.id].orEmpty()).current
            when (current?.condition) {
                OperationalCondition.DOWN, OperationalCondition.DEGRADED -> ranked += Pending(
                    group = if (current.condition == OperationalCondition.DOWN) DOWN else DEGRADED,
                    asset = asset,
                    subjectOrder = 0,
                    subjectId = "",
                    item = asset.item(AttentionKind.CONDITION, AttentionSection.ATTENTION, parent).copy(
                        condition = current.condition,
                        reason = current.reason,
                        occurredOn = current.occurredOn,
                    ),
                )
                OperationalCondition.OPERATIONAL, null -> Unit
            }
            for (subject in health.resultFor(asset, t).subjects) {
                if (subject.subject.driver != HealthDriver.AGE) continue
                val scored = subject.value as? SubjectValue.Scored ?: continue
                val (group, section) = when (scored.band) {
                    HealthBand.CRITICAL -> CRITICAL to AttentionSection.ATTENTION
                    HealthBand.WARNING -> WARNING to AttentionSection.UPCOMING
                    HealthBand.NOMINAL -> continue
                }
                ranked += Pending(
                    group = group,
                    asset = asset,
                    subjectOrder = subject.subject.sortOrder,
                    subjectId = subject.subject.id.value,
                    item = asset.item(AttentionKind.HEALTH, section, parent).copy(
                        healthSubjectId = subject.subject.id,
                        subjectName = subject.subject.name,
                        band = scored.band,
                        score = scored.score,
                    ),
                )
            }
        }
        return ranked.sortedWith(ORDER).mapIndexed { index, pending -> pending.item.copy(rank = index) }
    }

    private fun Asset.item(kind: AttentionKind, section: AttentionSection, parent: Asset?) = AttentionItem(
        kind = kind,
        section = section,
        assetId = id,
        assetName = name,
        parentAssetId = parentAssetId,
        parentName = parent?.name,
        condition = null,
        reason = null,
        occurredOn = null,
        healthSubjectId = null,
        subjectName = null,
        band = null,
        score = null,
        rank = 0,
    )

    /** A row with the keys it sorts on, before its rank is known. */
    private class Pending(
        val group: Int,
        val asset: Asset,
        val subjectOrder: Int,
        val subjectId: String,
        val item: AttentionItem,
    )

    private companion object {
        const val DOWN = 0
        const val DEGRADED = 1
        const val CRITICAL = 2
        const val WARNING = 3

        /** Plan decision 36's total order: the group, asset name, asset id, subject order and id. */
        val ORDER: Comparator<Pending> = compareBy<Pending> { it.group }
            .thenBy { it.asset.name.lowercase() }
            .thenBy { it.asset.id.value }
            .thenBy { it.subjectOrder }
            .thenBy { it.subjectId }
    }
}
