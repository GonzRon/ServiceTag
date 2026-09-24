package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.journal.SeedTemplates
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.HealthSubjectRepository
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.SeasonActivationRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.schedule.SeasonPhase

/**
 * Everything the asset editor saves in one tap (spec §10.4): the asset, its season, its break and its
 * health policy.
 */
data class AssetSettingsCommand(
    val asset: AssetCommand,
    val seasonMode: SeasonModeCommand,
    val maintenanceBreak: BreakCommand,
    val healthPolicy: HealthPolicyCommand,
)

/**
 * **The asset editor's one save** (spec §10.4; master plan §10.1, plan decision 15): the asset, its
 * season mode, its maintenance break and its health policy, validated together and written in **one**
 * `uow.write`. Any refusal writes nothing — not the asset, not the mode, not the break, not the policy.
 * The editor therefore holds no transaction logic of its own.
 *
 * Each part is judged by the rule its own command uses, so the editor, the API and this save refuse
 * the same things: the season mode by [seasonModeProblems], the break by [breakProblems], the policy by
 * [healthPolicyProblems], the asset by [validateAsset]. Every 422 comes before every 409.
 *
 * - **The season-mode part is authoritative.** The asset part keeps the stored `MM-DD` pair (none, on a
 *   create), so the legacy translation [UpdateAsset] applies to a pair is a no-op here.
 * - **The strands rule is judged on the combined result** (master plan §7.2): the boundary kind before
 *   the save against the kind after the mode **and** the break both apply ([strandedBy]). A refusal is
 *   [SeasonModeStrandsPolicy] when the change leaves or enters a calendar season, which is what
 *   re-kinds the boundary then, and [BreakStrandsPolicy] otherwise.
 * - **A switch into MANUAL** writes exactly one activation dated today, as [SetSeasonMode] does
 *   (inv. 92); a create counts as coming from YEAR_ROUND, so creating a MANUAL asset writes its first row.
 * - **Unchanged writes nothing.** A save equal to the stored row writes no asset, no activation and
 *   recomputes nothing; a changed season or break recomputes the asset's schedules, and nothing else
 *   does. No event, closure or schedule column is touched (inv. 86).
 * - **`id == null` creates the asset**, seeding [templateKey]'s definitions and quick actions in the
 *   same transaction, as [CreateAsset] does.
 */
class SaveAssetSettings(
    private val assets: AssetRepository,
    private val schedules: ScheduleRepository,
    private val subjects: HealthSubjectRepository,
    private val activations: SeasonActivationRepository,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val today: Today,
    private val recompute: RecomputeSchedules,
    private val applyTemplate: ApplyTemplate,
) {
    suspend fun run(id: AssetId?, cmd: AssetSettingsCommand, templateKey: String? = null): Asset = uow.write {
        val all = assets.all()
        val current = id?.let { wanted -> all.firstOrNull { it.id == wanted } ?: throw NoSuchAsset(wanted) }

        // The 422s, each part by its own command's rule.
        val mode = cmd.seasonMode.trimmed()
        val pause = cmd.maintenanceBreak.trimmed()
        val modeBefore = current?.seasonMode ?: SeasonMode.YEAR_ROUND
        val seasonProblems = seasonModeProblems(modeBefore, mode) + breakProblems(pause)
        if (seasonProblems.isNotEmpty()) throw SeasonValidation(seasonProblems)
        val policyProblems = healthPolicyProblems(current?.id, cmd.healthPolicy, subjects)
        if (policyProblems.isNotEmpty()) throw HealthValidation(policyProblems)
        // The stored pair, not the command's: the season-mode part decides the season.
        val asset = validateAsset(
            cmd.asset.copy(seasonStartMmdd = current?.seasonStartMmdd, seasonEndMmdd = current?.seasonEndMmdd),
            all,
            id,
        )

        val now = clock.nowMillis()
        val base = current ?: Asset(id = AssetId(ids.newId()), name = asset.name, createdAt = now, updatedAt = now)
        val next = base.applying(asset, now).copy(
            seasonMode = mode.seasonMode,
            seasonStartMmdd = mode.seasonStartMmdd,
            seasonEndMmdd = mode.seasonEndMmdd,
            blackoutStartMmdd = pause.blackoutStartMmdd,
            blackoutEndMmdd = pause.blackoutEndMmdd,
            healthAggregation = cmd.healthPolicy.healthAggregation,
            healthPrimarySubjectId = cmd.healthPolicy.healthPrimarySubjectId,
        )

        if (current != null) {
            if (next.copy(updatedAt = current.updatedAt) == current) return@write current
            // The 409: the kind before this save against the kind after both parts apply.
            val stranded = strandedBy(current, next, schedules.forAsset(current.id))
            if (stranded.isNotEmpty()) {
                val calendarChanged =
                    (current.seasonMode == SeasonMode.CALENDAR) != (next.seasonMode == SeasonMode.CALENDAR)
                throw if (calendarChanged) {
                    SeasonModeStrandsPolicy(current.id, stranded)
                } else {
                    BreakStrandsPolicy(current.id, stranded)
                }
            }
        }

        assets.upsert(next)
        if (next.seasonMode == SeasonMode.MANUAL && modeBefore != SeasonMode.MANUAL) {
            activations.insert(
                SeasonActivation(
                    id = ids.newId(),
                    assetId = next.id,
                    action = if (mode.manualPhase == SeasonPhase.IN_SEASON) SeasonAction.START else SeasonAction.END,
                    occurredOn = today.localDate().toString(),
                    eventId = null,
                    createdAt = now,
                ),
            )
        }
        if (current == null) {
            templateKey?.let { key ->
                applyTemplate.applyInTransaction(next.id, SeedTemplates.byKey(key) ?: throw UnknownTemplate(key))
            }
        } else if (current.seasonChangedTo(next)) {
            recompute.forAsset(next.id)
        }
        // The row as stored: a template stamps its key on the asset it seeds.
        assets.get(next.id) ?: next
    }

    /** Whether the season or the break — the inputs every schedule's state reads from the asset — changed. */
    private fun Asset.seasonChangedTo(next: Asset): Boolean =
        seasonMode != next.seasonMode ||
            seasonStartMmdd != next.seasonStartMmdd ||
            seasonEndMmdd != next.seasonEndMmdd ||
            blackoutStartMmdd != next.blackoutStartMmdd ||
            blackoutEndMmdd != next.blackoutEndMmdd
}
