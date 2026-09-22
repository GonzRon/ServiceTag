package com.loosecannon.servicetag.reminders

import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleState
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.ScheduleLocalDelivery
import com.loosecannon.servicetag.core.ports.ScheduleLocalDeliveryRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.reminders.HealthFinding
import com.loosecannon.servicetag.core.reminders.ProviderId
import com.loosecannon.servicetag.core.reminders.ReconcileReport
import com.loosecannon.servicetag.core.reminders.ReminderProvider
import com.loosecannon.servicetag.core.reminders.ReminderSubject
import com.loosecannon.servicetag.core.reminders.RemoteChange
import com.loosecannon.servicetag.core.reminders.RepairAction
import com.loosecannon.servicetag.core.reminders.Severity
import com.loosecannon.servicetag.core.reminders.SubjectKey
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.core.schedule.statusOf
import com.loosecannon.servicetag.prefs.AppPrefs
import java.time.LocalDate

/**
 * Derived due state, **read only** (master plan decision 41's shape).
 *
 * The delivery path has to read `schedule_state` — a status word cannot be derived without it, and
 * a meter-only subject has no date to derive one from — and it must never write it (invariant 17).
 * A seam with one read method is how that is a property of the type rather than of a reviewer's
 * attention: the write method is not reachable from here, and no file under `reminders/` names the
 * repository that has one.
 */
fun interface ScheduleStateReader {
    suspend fun stateOf(id: ScheduleId): ScheduleState?
}

/** What a notification needs about a subject that the provider-neutral port does not carry. */
fun interface DeliveryFactsSource {
    suspend fun factsFor(key: SubjectKey): DeliveryFacts?
}

/**
 * The [DeliveryFactsSource] over the real store: the schedule row, its derived state, the name of
 * whatever it is aimed at, and the meter definition's unit.
 *
 * The status comes from `statusOf` and is never re-derived from `ReminderSubject.dueOn` (carry-
 * forward (c) of this brief's dispatch): a meter-only schedule arrives Active with no date, and a
 * date-shaped derivation would make it silent for ever, which is exactly what #21 AC 6 forbids.
 *
 * A meter reading is reported as the reason only when the **meter side** is what came due and the
 * time side has not — a schedule that is overdue by date and also over its threshold is overdue,
 * and "Overdue since <date>." is the sentence that says so.
 */
class ScheduleDeliveryFacts(
    private val schedules: ScheduleRepository,
    private val states: ScheduleStateReader,
    private val assets: AssetRepository,
    private val groups: GroupRepository,
    private val definitions: DefinitionRepository,
    private val today: Today,
) : DeliveryFactsSource {

    override suspend fun factsFor(key: SubjectKey): DeliveryFacts? {
        val id = (key as SubjectKey.Schedule).scheduleId
        val schedule = schedules.get(id) ?: return null
        val state = states.stateOf(id) ?: return null
        val on = today.localDate()
        return DeliveryFacts(
            ownerName = ownerNameOf(schedule) ?: return null,
            status = statusOf(schedule, state, on),
            meter = meterCrossingOf(schedule, state, on),
            groupTargeted = schedule.target is ScheduleTarget.GroupTarget,
        )
    }

    /** The `<asset>` slot. A group stands in it by name: a group-targeted schedule has no asset. */
    private suspend fun ownerNameOf(schedule: MaintenanceSchedule): String? =
        when (val target = schedule.target) {
            is ScheduleTarget.AssetTarget -> assets.get(target.assetId)?.name
            is ScheduleTarget.GroupTarget -> groups.get(target.groupId)?.name
        }

    private suspend fun meterCrossingOf(
        schedule: MaintenanceSchedule,
        state: ScheduleState,
        on: LocalDate,
    ): MeterReading? {
        val definitionId = schedule.meterDefinitionId ?: return null
        val threshold = state.computedDueMeter ?: return null
        val current = state.currentMeter ?: return null
        if (current < threshold) return null
        // The time side already came due, so the date is the better sentence.
        val dueOn = state.effectiveDueOn?.let(LocalDate::parse)
        if (dueOn != null && !dueOn.isAfter(on)) return null
        val definition = definitions.get(definitionId)
        return MeterReading(
            dueAt = format(threshold),
            now = format(current),
            unit = definition?.unit.orEmpty(),
        )
    }

    /**
     * The same digits B08's dashboard row shows for the same two values, and **locale-independent**
     * (fix round 1, nit 9).
     *
     * `"%.2f".format(v)` resolves `Locale.getDefault()`, so on a comma-decimal locale the ratified
     * "Due at \<n\> \<unit\>, now \<n\>." would read "Due at 500,0 hours, now 512,0." — a comma
     * inside a sentence whose own separator is a comma. `Long.toString` and `Double.toString` are
     * locale-invariant, which is why this is the shape `ui/journal/JournalFormat.kt:70`'s
     * `formatNumber` uses and the shape `ui/maintenance/DueItemRow.kt:141`'s `meterLine` renders
     * this very string with. Transcribed rather than imported: the delivery path does not depend on
     * a UI formatting file, and the two must agree — a reviewer changing one should change both.
     */
    private fun format(value: Double): String {
        val whole = value.toLong()
        return if (value == whole.toDouble()) whole.toString() else value.toString()
    }
}

/**
 * The `LOCAL` reminder provider: notifications in the shade, an alarm in the system's alarm list,
 * and no projection rows anywhere.
 *
 * [reconcile] is the whole write surface and is **idempotent by construction**, not by discipline:
 * what it should be showing comes entirely from the list it is handed, and what it *is* showing
 * comes from the notification shade, whose tags carry each subject's content hash. Nothing is
 * remembered between calls, which is what makes a cleared app rebuild its whole posted set from
 * schedule state alone (invariant 44) and a second identical call inert (invariant 45).
 *
 * **Nothing here disables anything.** With notifications denied or the global switch off the run
 * still happens, the alarm stays armed, the backstop stays enqueued and the preferences stay
 * writable; what changes is that nothing is posted and [health] says why (D-22, invariant 61). A
 * guard that skipped arming would leave the machinery dead the moment the owner granted the
 * permission, which is the failure D-22 exists to prevent.
 */
class LocalReminderProvider(
    private val facts: DeliveryFactsSource,
    private val delivery: ScheduleLocalDeliveryRepository,
    private val notifications: ReminderNotifications,
    private val permission: NotificationPermission,
    private val platform: PlatformState,
    private val alarm: DigestAlarm,
    private val prefs: AppPrefs,
    private val clock: Clock,
    /**
     * B07's quick actions. Held here rather than inside [notifications], because issuing a
     * notification's nonce is a `suspend` write into the same row this class writes and the **order
     * of those two writes is the contract** — which is a statement about this method, not about the
     * shade. The call site below says which way round it has to be and why.
     */
    private val quickActions: QuickActions,
) : ReminderProvider {

    override val id: ProviderId = ProviderId.LOCAL

    override suspend fun reconcile(subjects: List<ReminderSubject>): ReconcileReport {
        // This provider's projection is "the posted notifications **plus the armed alarm**" (spec
        // §2.5, master plan §8), so restoring the alarm is part of reconciling and not a caller's
        // courtesy — it is what makes invariant 44 true of one `reconcile` on a cleared app. It
        // happens before any early return, because a denied permission must never leave the alarm
        // unarmed (D-22, invariant 61), and only when the alarm is gone, so a second identical call
        // still has no second effect (invariant 45).
        if (!alarm.armed()) alarm.arm()
        val now = clock.nowMillis()
        if (!deliveryEnabled()) return silence(now)
        val inputs = subjects.map { subject ->
            DeliveryInput(
                subject = subject,
                facts = facts.factsFor(subject.key),
                delivery = delivery.get((subject.key as SubjectKey.Schedule).scheduleId),
            )
        }

        val decision = DigestPolicy.decide(
            inputs = inputs,
            standingTags = notifications.standingItems(),
            standingSummaryTag = notifications.standingSummary(),
            nowMillis = now,
            // Per channel, not globally (fix round 1, finding 2): an owner who sets "Maintenance
            // due" to None must still get overdue reminders on the un-muted HIGH channel. Spec
            // §5.5 makes a muted channel *detectable*, and D-22's principle is that a platform
            // refusal is surfaced and never widened.
            channelDelivers = { channelId -> platform.channelImportance(channelId).delivers },
        )

        // Cancel before posting: a subject whose content moved has a stale tag standing beside its
        // new one for as long as both exist, and the stale one is the wrong answer.
        decision.cancelTags.forEach(notifications::cancelItem)
        if (decision.cancelSummary) notifications.cancelSummary()
        // The row writes and the nonce clearance both happen **before** anything is posted, and
        // that order is load-bearing (B07). `decision.rows` was computed from the rows as they
        // stood at the top of this method, so writing it after a nonce had been issued would write
        // the *old* nonce back over the new one and every action on the notification just posted
        // would be dead on arrival. Clearing runs before the posting for the same reason in the
        // other direction: a subject whose content moved is cancelled **and** re-posted in one run,
        // so its old nonce has to go before its new one is issued rather than after (D-21).
        decision.rows.forEach { delivery.upsert(it) }
        clearNoncesFor(decision.cancelTags, now)
        decision.posts.forEach { post ->
            notifications.postItem(post, quickActions.forSchedule((post.key as SubjectKey.Schedule).scheduleId))
        }
        decision.summary?.let(notifications::postSummary)

        return decision.report
    }

    /**
     * D-21: the nonce is cleared on successful use and **on replacement or reconcile**. A
     * notification that has just been cancelled has nothing left to authorise, so its nonce must go
     * with it — otherwise a forged broadcast carrying the last value the app issued would still be
     * accepted (invariant 56).
     *
     * It reads the schedule id back out of the cancelled **tag**, which is what lets it cover the
     * subject that left the list altogether: absence is the cancel, so an absent subject has no row
     * in the decision's own inputs and the digest policy never saw it.
     */
    private suspend fun clearNoncesFor(cancelledTags: List<String>, nowMillis: Long) {
        cancelledTags.map { it.scheduleIdOfTag() }.distinct().forEach { id ->
            val row = delivery.get(ScheduleId(id)) ?: return@forEach
            if (row.actionNonce == null && row.nonceIssuedAt == null) return@forEach
            delivery.upsert(row.copy(actionNonce = null, nonceIssuedAt = null, updatedAt = nowMillis))
        }
    }

    /** Nothing changes on this side but what [reconcile] put there (spec §2.5). */
    override suspend fun pullChanges(): List<RemoteChange> = emptyList()

    /**
     * The three findings whose facts this provider holds. The other four in the shipped set —
     * `BACKSTOP_WORK_MISSING`, `APP_RESTRICTED`, `SCHEDULE_NO_PROVIDER`, `NO_DATA` — are read from
     * WorkManager, the standby bucket and the store, and belong to the health screen (B10).
     *
     * `NOTIFICATIONS_BLOCKED` folds three different ways of being silenced by the system — the
     * runtime permission refused, the app-level toggle off, and a channel the owner muted or that
     * was never created — because the spec ships one code for them and the sentence is true of all
     * three (spec §5.5, §5.8). The sentences are RATIFIED verbatim (master plan §17.1a); the repair
     * **labels** are B10's to draw, so only the repair's code appears here.
     */
    override suspend fun health(): List<HealthFinding> = buildList {
        if (!notificationsAvailable()) {
            add(
                HealthFinding(
                    code = "NOTIFICATIONS_BLOCKED",
                    severity = Severity.ERROR,
                    message = "Notifications are turned off, so maintenance reminders will not arrive.",
                    repair = RepairAction.OpenSystemSettings("OPEN_NOTIFICATION_SETTINGS"),
                ),
            )
        }
        if (!prefs.remindersEnabled) {
            add(
                HealthFinding(
                    code = "REMINDERS_GLOBALLY_OFF",
                    severity = Severity.INFO,
                    message = "Reminders are turned off in ServiceTag.",
                    repair = RepairAction.OpenInApp("TURN_REMINDERS_ON"),
                ),
            )
        }
        if (!alarm.armed()) {
            add(
                HealthFinding(
                    code = "DIGEST_ALARM_MISSING",
                    severity = Severity.WARN,
                    message = "The daily reminder check is not scheduled, so today's maintenance may go unannounced.",
                    // Unambiguous and idempotent, which is the whole test for an automatic repair.
                    repair = RepairAction.Automatic("ARM_DIGEST_ALARM"),
                ),
            )
        }
    }

    /**
     * Reminders switched off, the permission refused, or notifications switched off for the whole
     * app: this provider stops showing anything, and **takes down what it was showing** (fix
     * round 1, finding 5).
     *
     * D-22 requires that nothing be *disabled* — the alarm was armed above this, the backstop is
     * enqueued elsewhere, the preferences stay writable and the recompute already ran — but it does
     * not require a stale posted set to outlive the switch that stopped the posting. Those
     * notifications carry no action and no content intent, so leaving them would leave the owner
     * inert text to swipe by hand while the health screen says reminders are off. Their nonces go
     * with them, which is D-21's "on … reconcile" clause.
     *
     * `cleared` honestly carries the count; `posted` and `unchanged` are zero because this provider
     * is now showing nothing.
     */
    private suspend fun silence(nowMillis: Long): ReconcileReport {
        val standing = notifications.standingItems().toList()
        standing.forEach(notifications::cancelItem)
        if (notifications.standingSummary() != null) notifications.cancelSummary()
        clearNoncesFor(standing, nowMillis)
        return ReconcileReport(0, standing.size, 0, listOf(SILENCED))
    }

    /**
     * Whether this provider runs at all: the owner's own switch, the runtime permission, and the
     * app-level notification toggle. **Not** a channel test — a muted channel is decided per post,
     * because muting one of the two must not silence the other (finding 2).
     */
    private fun deliveryEnabled(): Boolean =
        prefs.remindersEnabled && permission.granted() && platform.notificationsEnabled()

    /**
     * Whether the system will show anything at all, which is a wider question than
     * [deliveryEnabled] and is only asked by [health]: a muted or never-created channel folds into
     * `NOTIFICATIONS_BLOCKED` (R8) even though it no longer stops the run.
     */
    private fun notificationsAvailable(): Boolean =
        permission.granted() &&
            platform.notificationsEnabled() &&
            platform.channelImportance(NotificationChannels.DUE).delivers &&
            platform.channelImportance(NotificationChannels.OVERDUE).delivers

    private companion object {
        /**
         * A `problems` sentence, not a user-visible string: [ReconcileReport.problems] is what a
         * diagnostics report renders, and the owner-facing sentence for the same fact is the
         * ratified `NOTIFICATIONS_BLOCKED` finding above.
         */
        const val SILENCED = "reminders are switched off or notifications are blocked; nothing was posted"
    }
}

/** A channel that was muted, or never created, cannot deliver; the other two importances can. */
internal val ChannelImportance.delivers: Boolean
    get() = this == ChannelImportance.DEFAULT || this == ChannelImportance.HIGH

/**
 * The snooze, as B07's "Snooze 1 day" and B09's "Snooze" both perform it.
 *
 * It writes **one column of one device-local row** and nothing else: no due date moves, no event is
 * written, and the schedule's status is whatever it was (invariant 20, D-13). That is the whole
 * reason the snooze lives here rather than on `postponed_due_on`, which is a real reschedule.
 */
class ReminderSnooze(
    private val delivery: ScheduleLocalDeliveryRepository,
    private val clock: Clock,
) {
    suspend fun snooze(id: ScheduleId, until: Long) {
        val now = clock.nowMillis()
        val row = delivery.get(id) ?: ScheduleLocalDelivery(
            scheduleId = id,
            snoozedUntilAt = null,
            lastNotifiedAt = null,
            firstEntrySeen = false,
            actionNonce = null,
            nonceIssuedAt = null,
            updatedAt = now,
        )
        delivery.upsert(row.copy(snoozedUntilAt = until, updatedAt = now))
    }
}

/**
 * The per-notification nonce (D-21, invariant 56).
 *
 * Issued here, because the issuing side is the notification build; checked and consumed by B07's
 * receiver. It is **persisted** rather than held in process so an action still works after the
 * process that posted the notification has died, and it is cleared on successful use and whenever
 * the subject stops being shown — a nonce with nothing standing to authorise is a nonce a forged
 * broadcast could still spend.
 *
 * One column, one owner: splitting the store from the table would give two briefs write access to
 * `action_nonce`, which is the reason both this and [ReminderSnooze] are declared in B06 and
 * consumed by B07 rather than the other way round.
 */
class NonceStore(
    private val delivery: ScheduleLocalDeliveryRepository,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    suspend fun issue(id: ScheduleId): String {
        val now = clock.nowMillis()
        val nonce = ids.newId()
        val row = delivery.get(id) ?: ScheduleLocalDelivery(
            scheduleId = id,
            snoozedUntilAt = null,
            lastNotifiedAt = null,
            firstEntrySeen = false,
            actionNonce = null,
            nonceIssuedAt = null,
            updatedAt = now,
        )
        delivery.upsert(row.copy(actionNonce = nonce, nonceIssuedAt = now, updatedAt = now))
        return nonce
    }

    /**
     * False for a stale, missing or already-used nonce, and **nothing is written** in any of those
     * cases. True consumes it, so a replayed broadcast carrying the same value is refused.
     */
    suspend fun consume(id: ScheduleId, nonce: String): Boolean {
        val row = delivery.get(id) ?: return false
        if (row.actionNonce == null || row.actionNonce != nonce) return false
        val now = clock.nowMillis()
        delivery.upsert(row.copy(actionNonce = null, nonceIssuedAt = null, updatedAt = now))
        return true
    }

    suspend fun clear(id: ScheduleId) {
        val row = delivery.get(id) ?: return
        if (row.actionNonce == null && row.nonceIssuedAt == null) return
        delivery.upsert(row.copy(actionNonce = null, nonceIssuedAt = null, updatedAt = clock.nowMillis()))
    }
}
