package com.loosecannon.servicetag.ui.maintenance

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.SeasonBehavior
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.reminders.ProviderId
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.GroupCommand
import com.loosecannon.servicetag.core.usecase.GroupMemberInput
import com.loosecannon.servicetag.core.usecase.ScheduleCommand
import com.loosecannon.servicetag.core.usecase.ScheduleProblem
import com.loosecannon.servicetag.core.usecase.ScheduleValidation
import com.loosecannon.servicetag.reminders.NotificationPermission
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.testing.meterDefinitionOf
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The schedule editor.
 *
 * Every rule it enforces is `SaveSchedule`'s, called; what is proved here is the narrower fact that
 * **this surface cannot reach a forbidden state** — one target and never two, no meter rule, no
 * profile and no `FOLLOW_ASSET` for a group, at most one provider row — plus D-11's non-blocking
 * warning and the permission request's timing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleEditViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    /** What the seam was asked for, so "asked once, on a creation, never at launch" is assertable. */
    private var requests = 0

    /**
     * Granted by default, so only the two tests that are *about* the permission see the rationale.
     * With it denied every first creation would stop on the rationale, which is the behaviour those
     * two assert and noise everywhere else.
     */
    private var granted = true

    private val permission = object : NotificationPermission {
        override fun granted(): Boolean = granted
        override fun shouldExplain(): Boolean = false
        override suspend fun request(): Boolean {
            requests += 1
            return granted
        }
    }

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        graph.today = LocalDate.parse("2026-04-15")
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(
        scheduleId: String? = null,
        targetAssetId: String? = null,
        targetGroupId: String? = null,
    ) = ScheduleEditViewModel(
        schedules = graph.schedules,
        assets = graph.assets,
        groups = graph.groups,
        definitions = graph.definitions,
        profiles = graph.profiles,
        saveSchedule = graph.saveSchedule,
        notifications = permission,
        today = graph.todayPort,
        scheduleId = scheduleId?.let(::ScheduleId),
        targetAssetId = targetAssetId?.let(::AssetId),
        targetGroupId = targetGroupId?.takeIf { targetAssetId == null }?.let(::GroupId),
    )

    /**
     * Subscribes to the one-shot `saved` signal **before** the save that fires it.
     *
     * `saved` has no replay, exactly as the shipped editors' does — a replayed one would pop the
     * screen again on the next recomposition — so a collector attached after the emit would wait for
     * ever. The screen avoids that with a `LaunchedEffect` that is already collecting; this is the
     * test's equivalent, started undispatched so the subscription is in place before `save()` runs.
     */
    private fun TestScope.savedId(vm: ScheduleEditViewModel): CompletableDeferred<ScheduleId> {
        val out = CompletableDeferred<ScheduleId>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { out.complete(vm.saved.first()) }
        return out
    }

    private suspend fun aGroupOf(assetIds: List<AssetId>) = graph.saveGroup.run(
        null,
        GroupCommand(name = "North run", members = assetIds.map { GroupMemberInput(assetId = it) }),
    )

    /**
     * Matrix row **"a schedule cannot be created at all"**, the asset half: a schedule is created
     * against an asset and is there afterwards.
     */
    @Test fun aScheduleIsCreatedAgainstAnAsset() = runTest {
        val mower = graph.createAsset.run(AssetCommand(name = "Mower", category = "Yard"))
        val vm = viewModel(targetAssetId = mower.id.value)
        vm.state.first { it.loaded }

        vm.onTitle("Blade sharpen")
        vm.onInterval("3")
        vm.onUnit(RecurrenceUnit.MONTH)
        vm.onAnchor("2026-01-01")
        val saved = savedId(vm)
        vm.save()
        val id = saved.await()

        val stored = graph.schedules.get(id)!!
        assertEquals(ScheduleTarget.AssetTarget(mower.id), stored.target)
        assertEquals("Blade sharpen", stored.title)
        assertEquals(3, stored.timeInterval)
        assertEquals(RecurrenceUnit.MONTH, stored.timeUnit)
        // And it is listed: the projection every due surface reads starts from `listedForDue()`.
        assertTrue(graph.schedules.all().any { it.id == id })
    }

    /**
     * Matrix row **"a schedule cannot be created at all"**, the group half — and the **anchor
     * default**: a new editor opens anchored on today, so the required field is never blank by
     * accident.
     */
    @Test fun aScheduleIsCreatedAgainstAGroup() = runTest {
        val head = graph.createAsset.run(AssetCommand(name = "Sprinkler 1", category = "Irrigation"))
        val group = aGroupOf(listOf(head.id))
        val vm = viewModel(targetGroupId = group.id.value)
        val opened = vm.state.first { it.loaded }
        assertEquals("2026-04-15", opened.anchorOn)
        assertEquals("North run", opened.targetName)

        vm.onTitle("Head check")
        vm.onInterval("3")
        val saved = savedId(vm)
        vm.save()

        assertEquals(ScheduleTarget.GroupTarget(group.id), graph.schedules.get(saved.await())!!.target)
    }

    /**
     * Matrix row **"both targets set"**, both halves.
     *
     * Structurally: the form holds **one** `ScheduleTarget`, so a command with both ids is not a
     * state this view model can be in. Behaviourally: a route carrying both — a serialised back
     * stack from an older process, or a hand-built key — resolves to the asset and drops the group
     * rather than sending both into a command the engine would refuse (invariant 1).
     */
    @Test fun noInteractionProducesACommandWithBothTargets() = runTest {
        val mower = graph.createAsset.run(AssetCommand(name = "Mower", category = "Yard"))
        val head = graph.createAsset.run(AssetCommand(name = "Sprinkler 1", category = "Irrigation"))
        val group = aGroupOf(listOf(head.id))

        val vm = ScheduleEditViewModel(
            graph.schedules, graph.assets, graph.groups, graph.definitions, graph.profiles,
            graph.saveSchedule, permission, graph.todayPort,
            scheduleId = null,
            targetAssetId = mower.id,
            // Both arrive; one has to win, and it is never both.
            targetGroupId = group.id,
        )
        val opened = vm.state.first { it.loaded }
        assertEquals(ScheduleTarget.AssetTarget(mower.id), opened.target)
        assertFalse(opened.isGroup)

        vm.onTitle("Blade sharpen")
        vm.onInterval("3")
        val saved = savedId(vm)
        vm.save()
        val stored = graph.schedules.get(saved.await())!!
        assertEquals(ScheduleTarget.AssetTarget(mower.id), stored.target)
    }

    /**
     * Matrix row **"an illegal group schedule"**, all three parts plus the refusal.
     *
     * A group target offers **no** meter rule, **no** profile and **no** `FOLLOW_ASSET` season: the
     * setters are guarded, so the state cannot hold one whatever is restored into the view model,
     * and the command is built with the legal values regardless. And if such a command is
     * constructed anyway, the use case refuses it (invariants 2, 3, 27; D-12, D-28).
     */
    @Test fun aGroupTargetOffersNoMeterNoProfileAndNoFollowAssetSeason() = runTest {
        val head = graph.createAsset.run(AssetCommand(name = "Sprinkler 1", category = "Irrigation"))
        val group = aGroupOf(listOf(head.id))
        val meter = meterDefinitionOf("d-hours", head.id.value)
        graph.definitions.upsert(meter)

        val vm = viewModel(targetGroupId = group.id.value)
        vm.state.first { it.loaded }

        // The three offers a group target does not get. Each setter is a no-op for one.
        vm.onMeterDefinition(meter.id)
        vm.onSeason(SeasonBehavior.FOLLOW_ASSET)
        vm.onCompletionMode(CompletionMode.FORM)
        val form = vm.state.value
        assertNull("no meter rule on a group target", form.meterDefinitionId)
        assertEquals(SeasonBehavior.IGNORE, form.seasonBehavior)
        assertEquals(CompletionMode.QUICK, form.completionMode)
        assertNull("and so no profile", form.profileId)
        assertFalse(form.hasMeterRule)

        vm.onTitle("Head check")
        vm.onInterval("3")
        val saved = savedId(vm)
        vm.save()
        val stored = graph.schedules.get(saved.await())!!
        assertNull(stored.meterDefinitionId)
        assertNull(stored.profileId)
        assertEquals(SeasonBehavior.IGNORE, stored.seasonBehavior)
        assertEquals(CompletionMode.QUICK, stored.completionMode)

        // The same command constructed by hand is refused, so the rule is not the UI's alone.
        val problems = runCatching {
            graph.saveSchedule.run(
                null,
                ScheduleCommand(
                    targetAssetId = null,
                    targetGroupId = group.id,
                    title = "Illegal",
                    timeInterval = 3,
                    timeUnit = RecurrenceUnit.MONTH,
                    anchorOn = "2026-01-01",
                    meterDefinitionId = meter.id,
                    meterInterval = 100.0,
                    seasonBehavior = SeasonBehavior.FOLLOW_ASSET,
                    completionMode = CompletionMode.FORM,
                ),
            )
        }.exceptionOrNull() as ScheduleValidation
        assertTrue(ScheduleProblem.MeterRuleOnGroupTarget in problems.problems)
        assertTrue(ScheduleProblem.SeasonFollowsAssetOnGroupTarget in problems.problems)
        assertTrue(ScheduleProblem.FormCompletionOnGroupTarget in problems.problems)
    }

    /**
     * Matrix row **"the permission asked at the wrong time"**, both halves.
     *
     * The request fires on the **first** schedule creation, with the ratified rationale in front of
     * it, and never at launch — building the view model and loading the form asks for nothing. And a
     * **denial** still creates the schedule and disables nothing (#24 AC 1, D-22, invariant 61).
     */
    @Test fun thePermissionIsAskedOnTheFirstCreationAndADenialIsNotFatal() = runTest {
        granted = false
        val mower = graph.createAsset.run(AssetCommand(name = "Mower", category = "Yard"))
        val vm = viewModel(targetAssetId = mower.id.value)
        vm.state.first { it.loaded }
        // Not at launch, and not while the form is merely open.
        assertEquals(0, requests)
        assertFalse(vm.state.value.askingForNotifications)

        vm.onTitle("Blade sharpen")
        vm.onInterval("3")
        vm.save()

        // The rationale is up, the schedule is **already written**, and nothing has been requested.
        val asking = vm.state.first { it.askingForNotifications }
        assertTrue(asking.askingForNotifications)
        assertEquals(0, requests)
        assertEquals(1, graph.schedules.all().size)

        granted = false
        val saved = savedId(vm)
        vm.requestNotifications()
        val id = saved.await()
        assertEquals(1, requests)
        // The denial cost nothing: the schedule is there, reminders are still switched on, and its
        // provider row is untouched.
        val stored = graph.schedules.get(id)!!
        assertTrue(stored.remindersEnabled)
        assertEquals(listOf("LOCAL"), stored.providers.map { it.provider })
        assertFalse(vm.state.value.askingForNotifications)
    }

    /** The nag #24 avoids: a **second** creation after an answered prompt does not re-ask. */
    @Test fun aSecondCreationDoesNotAskAgain() = runTest {
        granted = false
        val mower = graph.createAsset.run(AssetCommand(name = "Mower", category = "Yard"))
        val first = viewModel(targetAssetId = mower.id.value)
        first.state.first { it.loaded }
        first.onTitle("Blade sharpen")
        first.onInterval("3")
        first.save()
        first.state.first { it.askingForNotifications }
        val firstSaved = savedId(first)
        first.requestNotifications()
        firstSaved.await()
        assertEquals(1, requests)

        val second = viewModel(targetAssetId = mower.id.value)
        second.state.first { it.loaded }
        second.onTitle("Winter service")
        second.onInterval("1")
        second.onUnit(RecurrenceUnit.YEAR)
        val secondSaved = savedId(second)
        second.save()
        secondSaved.await()

        assertEquals("asked once, on the first creation only", 1, requests)
        assertFalse(second.state.value.askingForNotifications)
        assertEquals(2, graph.schedules.all().size)
    }

    /**
     * Matrix row **"the duplicate-operation warning"**, all three parts.
     *
     * It appears when the asset already has a similar operation **through a group** it is an open
     * member of; it is **non-blocking** — the schedule saves; and a same-titled schedule on an
     * *unrelated* asset raises nothing, because the comparison set is exactly this asset's groups
     * (D-11, decision 37).
     */
    @Test fun theDuplicateWarningIsScopedToTheAssetsOwnGroupsAndNeverBlocks() = runTest {
        val head = graph.createAsset.run(AssetCommand(name = "Sprinkler 1", category = "Irrigation"))
        val stranger = graph.createAsset.run(AssetCommand(name = "Sprinkler 9", category = "Irrigation"))
        val group = aGroupOf(listOf(head.id))
        graph.saveSchedule.run(
            null,
            ScheduleCommand(
                targetAssetId = null,
                targetGroupId = group.id,
                title = "Head check",
                timeInterval = 3,
                timeUnit = RecurrenceUnit.MONTH,
                anchorOn = "2026-01-01",
            ),
        )

        val vm = viewModel(targetAssetId = head.id.value)
        vm.state.first { it.loaded }
        assertFalse("nothing typed yet", vm.state.value.duplicateWarning)

        // Case-insensitively the same title as the group's schedule: the line appears.
        vm.onTitle("head CHECK")
        assertTrue(vm.state.first { it.duplicateWarning }.duplicateWarning)

        // And it does not block: the schedule saves with the warning on screen.
        vm.onInterval("3")
        val signal = savedId(vm)
        vm.save()
        assertEquals("head CHECK", graph.schedules.get(signal.await())!!.title)

        // A different title raises nothing.
        val other = viewModel(targetAssetId = head.id.value)
        other.state.first { it.loaded }
        other.onTitle("Nozzle swap")
        assertFalse(other.state.value.duplicateWarning)

        // Nor does the same title on an asset that is in no such group.
        val unrelated = viewModel(targetAssetId = stranger.id.value)
        unrelated.state.first { it.loaded }
        unrelated.onTitle("Head check")
        assertFalse("an unrelated asset warns nobody", unrelated.state.value.duplicateWarning)
    }

    /**
     * Matrix row **"a provider row multiplying"**: the editor writes **at most one** provider row —
     * and now exactly one, because the row has no option word to pick from.
     *
     * §17.1c ratifies the "Remind me through" label and, deliberately, **no option**, so with the
     * single `ProviderId` of decision 8 there is nothing for the screen to offer and the provider is
     * not settable from it at all. What the command must still never do is write two rows, or drop
     * the one it has — which is what the reminders-off round trip below is about (#4 "Provider
     * selection"; the multi-provider UI is #25).
     */
    @Test fun theEditorWritesExactlyOneProviderRowAndNeverLosesIt() = runTest {
        val mower = graph.createAsset.run(AssetCommand(name = "Mower", category = "Yard"))
        val vm = viewModel(targetAssetId = mower.id.value)
        vm.state.first { it.loaded }
        vm.onTitle("Blade sharpen")
        vm.onInterval("3")
        val saved = savedId(vm)
        vm.save()

        val stored = graph.schedules.get(saved.await())!!
        assertEquals(1, stored.providers.size)
        assertEquals(ProviderId.LOCAL.name, stored.providers.single().provider)
        assertTrue(stored.providers.single().enabled)

        // Reminders **off**, then opened and saved again: the row must survive, disabled. Reading it
        // back through an `enabled` filter dropped it here, and the next save wrote a schedule with
        // reminders on and no provider at all — B10's `SCHEDULE_NO_PROVIDER` through the editor's
        // ordinary path.
        val off = viewModel(scheduleId = stored.id.value)
        off.state.first { it.loaded }
        off.onReminders(false)
        val offSaved = savedId(off)
        off.save()
        val stopped = graph.schedules.get(offSaved.await())!!
        assertFalse(stopped.remindersEnabled)
        assertEquals("the row is kept, disabled", 1, stopped.providers.size)
        assertFalse(stopped.providers.single().enabled)

        // And switching them back on in a fresh session recovers a deliverable schedule.
        val on = viewModel(scheduleId = stopped.id.value)
        assertEquals(ProviderId.LOCAL, on.state.first { it.loaded }.provider)
        on.onReminders(true)
        val onSaved = savedId(on)
        on.save()
        val resumed = graph.schedules.get(onSaved.await())!!
        assertTrue(resumed.remindersEnabled)
        assertEquals(1, resumed.providers.size)
        assertTrue("reminders on with no provider is the finding this prevents", resumed.providers.single().enabled)
    }

    /**
     * Matrix row **"an edit clearing a postponement"**, the editor's half: editing the recurrence of
     * a **postponed** schedule clears `postponedDueOn` and moves the pin's floor to the edit date,
     * and the recorded completions stay as history (invariants 19, 25; D-9).
     *
     * The stored target wins on an edit, whatever the route carries, which is the other half of "the
     * choice is made once".
     */
    @Test fun editingTheRecurrenceClearsThePostponementAndKeepsTheStoredTarget() = runTest {
        val mower = graph.createAsset.run(AssetCommand(name = "Mower", category = "Yard"))
        val schedule = graph.saveSchedule.run(
            null,
            ScheduleCommand(
                targetAssetId = mower.id,
                targetGroupId = null,
                title = "Blade sharpen",
                timeInterval = 3,
                timeUnit = RecurrenceUnit.MONTH,
                anchorOn = "2026-01-01",
            ),
        )
        graph.postponeSchedule.run(schedule.id, "2026-05-20")
        assertEquals("2026-05-20", graph.schedules.get(schedule.id)!!.postponedDueOn)

        // The route is given the *wrong* target on purpose: an edit keeps the stored one.
        val other = graph.createAsset.run(AssetCommand(name = "Trimmer", category = "Yard"))
        val vm = viewModel(scheduleId = schedule.id.value, targetAssetId = other.id.value)
        val opened = vm.state.first { it.loaded }
        assertEquals(ScheduleTarget.AssetTarget(mower.id), opened.target)
        assertEquals("3", opened.timeInterval)
        assertEquals(TimeBasis.FIXED, opened.timeBasis)

        graph.now = dayMillis("2026-04-15")
        val saved = savedId(vm)
        vm.onInterval("6")
        vm.save()
        saved.await()

        val edited = graph.schedules.get(schedule.id)!!
        assertEquals(6, edited.timeInterval)
        assertNull("the postponement is gone", edited.postponedDueOn)
        assertEquals(ScheduleTarget.AssetTarget(mower.id), edited.target)
        // The floor moved to the edit date, so re-anchoring does not pin the schedule overdue.
        assertEquals(dayMillis("2026-04-15"), edited.updatedAt)
        // And no permission prompt: this is an edit, not a creation.
        assertEquals(0, requests)
    }

    /**
     * N12 — a typed **negative meter lead** marks its field instead of being dropped.
     *
     * The editor used to erase it: the save went through, the schedule was stored with **no** lead,
     * and the field still showed the number the owner had typed — a value silently turned into a
     * different one. Now the command carries what was typed, `NegativeMeterLead` refuses it, and
     * `METER_LEAD` is marked, which is also what makes that mark reachable at all (the review's S5
     * noted it was dead). Carry-forward (c) still holds — non-negative in the editor — by refusal
     * rather than by erasure.
     */
    @Test fun aNegativeMeterLeadMarksItsFieldRatherThanBeingDropped() = runTest {
        val tractor = graph.createAsset.run(AssetCommand(name = "Tractor", category = "Yard"))
        val hours = meterDefinitionOf("d-hours", tractor.id.value)
        graph.definitions.upsert(hours)

        val vm = viewModel(targetAssetId = tractor.id.value)
        vm.state.first { it.loaded }
        vm.onTitle("Oil change")
        vm.onMeterDefinition(hours.id)
        vm.onMeterInterval("100")
        vm.onMeterLead("-5")
        vm.save()

        val refused = vm.state.first { !it.saving && it.problems.isNotEmpty() }
        assertTrue(ScheduleProblem.NegativeMeterLead in refused.problems)
        assertTrue("the field is marked", ScheduleField.METER_LEAD in refused.marks)
        assertEquals("and nothing was stored", 0, graph.schedules.all().size)
        assertEquals("with what was typed still on screen", "-5", refused.meterLead)

        // Correcting it clears that field's mark and saves the lead as entered.
        vm.onMeterLead("5")
        assertFalse(ScheduleField.METER_LEAD in vm.state.value.marks)
        val saved = savedId(vm)
        vm.save()
        assertEquals(5.0, graph.schedules.get(saved.await())!!.meterLead)
    }

    /**
     * Every refusal maps to a control — and the three that map to a control the screen does **not**
     * mark are the three no interaction can provoke.
     *
     * The form renders a mark for TARGET, TITLE, INTERVAL, UNIT, ANCHOR, LEAD, METER,
     * METER_INTERVAL and PROFILE. It renders none for SEASON, COMPLETION_MODE or PROVIDER, and that
     * is safe only while those three refusals are unreachable through this editor: the season and
     * completion-mode setters refuse the illegal value for a group target outright, and the provider
     * is not settable at all. This test asserts the reachability rather than the mapping's
     * non-blankness, because a name being non-blank is true of a dead end too.
     */
    @Test fun everyRefusalMapsToAControlAndTheUnmarkedThreeAreUnreachable() = runTest {
        val problems = listOf(
            ScheduleProblem.TargetInvalid,
            ScheduleProblem.EmptyGroupTarget,
            ScheduleProblem.NoRuleSide,
            ScheduleProblem.TimeIntervalNotPositive,
            ScheduleProblem.TimeUnitRequired,
            ScheduleProblem.AnchorRequired,
            ScheduleProblem.BadAnchorDate,
            ScheduleProblem.NegativeLeadDays,
            ScheduleProblem.MeterRuleOnGroupTarget,
            ScheduleProblem.MeterIntervalRequired,
            ScheduleProblem.MeterIntervalNotPositive,
            ScheduleProblem.SeasonFollowsAssetOnGroupTarget,
            ScheduleProblem.FormCompletionOnGroupTarget,
            ScheduleProblem.ProfileOnGroupTarget,
            ScheduleProblem.PostponeNeedsTimeRule,
            ScheduleProblem.ForeignProfile(ProfileId("p")),
            ScheduleProblem.ForeignMeterDefinition(DefinitionId("d")),
            ScheduleProblem.MeterDefinitionNotAMeter(DefinitionId("d")),
            ScheduleProblem.UnknownProvider("TODOIST"),
        )
        problems.forEach { problem -> assertTrue("$problem maps nowhere", fieldOf(problem).isNotBlank()) }
        assertEquals(problems.map(::fieldOf).toSet(), ScheduleEditState(problems = problems).marks)

        // The three the screen does not mark, and why it does not have to.
        val head = graph.createAsset.run(AssetCommand(name = "Sprinkler 1", category = "Irrigation"))
        val group = aGroupOf(listOf(head.id))
        val vm = viewModel(targetGroupId = group.id.value)
        vm.state.first { it.loaded }

        vm.onSeason(SeasonBehavior.FOLLOW_ASSET)
        assertEquals("SEASON is unreachable: the setter refuses it", SeasonBehavior.IGNORE, vm.state.value.seasonBehavior)
        vm.onCompletionMode(CompletionMode.FORM)
        assertEquals("COMPLETION_MODE likewise", CompletionMode.QUICK, vm.state.value.completionMode)
        // PROVIDER: not settable from the screen at all, and the only value it can hold is legal.
        assertEquals(ProviderId.LOCAL, vm.state.value.provider)
        assertEquals(
            listOf(ScheduleField.SEASON, ScheduleField.COMPLETION_MODE, ScheduleField.PROVIDER),
            listOf(
                fieldOf(ScheduleProblem.SeasonFollowsAssetOnGroupTarget),
                fieldOf(ScheduleProblem.FormCompletionOnGroupTarget),
                fieldOf(ScheduleProblem.UnknownProvider("TODOIST")),
            ),
        )

        vm.onTitle("Head check")
        vm.onInterval("3")
        val saved = savedId(vm)
        vm.save()
        // The save goes through, so none of the three was provoked.
        assertTrue(vm.state.first { !it.saving }.problems.isEmpty())
        saved.await()
    }
}
