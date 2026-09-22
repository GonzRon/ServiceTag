package com.loosecannon.servicetag.reminders

import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.prefs.AppPrefs
import com.loosecannon.servicetag.prefs.KeyValueStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which actions a notification carries, and the nonce each build issues.
 *
 * Everything here is off-device: the decision is a value — a label and a [QuickActionTarget] — and
 * the `PendingIntent` that carries it out is [AndroidQuickActionIntents]' one job, proved by the
 * gate's anchored greps and by the connected class. That split is deliberate: "which actions
 * appear, and what each one is aimed at" is the part a reviewer needs asserted, and it would
 * otherwise need a real `Context` to look at.
 */
class QuickActionsTest {

    private val delivery = FakeDeliveryRepository()
    private val clock = Clock { Fixture.NOW }
    private var issued = 0
    private val nonces = NonceStore(delivery, IdGenerator { "nonce-${++issued}" }, clock)

    private var shape: QuickActionShape? = QuickActionShape(
        groupTargeted = false,
        completionMode = CompletionMode.QUICK,
        meterRule = false,
    )
    private val actions = QuickActions(QuickActionShapeSource { shape }, nonces)

    private val id = ScheduleId("s1")

    /**
     * §12.1's first row: a plain `QUICK` schedule gets all three, "Done" is the **broadcast** one,
     * and both broadcasts carry the same nonce — one notification, one value, so spending it on
     * either action retires the other too.
     */
    @Test
    fun aQuickScheduleOffersDoneAsABroadcastThenSnoozeThenOpen() = runTest {
        val built = actions.forSchedule(id)

        assertEquals(
            listOf(DigestPolicy.ACTION_DONE, DigestPolicy.ACTION_SNOOZE_ONE_DAY, DigestPolicy.ACTION_OPEN),
            built.map { it.label },
        )
        assertEquals(QuickActionTarget.Complete(id, "nonce-1"), built[0].target)
        assertEquals(QuickActionTarget.Snooze(id, "nonce-1"), built[1].target)
        assertEquals(QuickActionTarget.OpenSchedule(id), built[2].target)
    }

    /**
     * #11 AC 2 and invariant 55: a `FORM` schedule's "Done" is an **activity** target and carries
     * no nonce, because it writes nothing — the form does, later, through the ordinary path. A
     * broadcast here would have to invent the structured data the form exists to collect.
     */
    @Test
    fun aFormScheduleRoutesDoneIntoTheFormAsAnActivityAndCarriesNoNonceOnIt() = runTest {
        shape = QuickActionShape(groupTargeted = false, completionMode = CompletionMode.FORM, meterRule = false)

        val built = actions.forSchedule(id)

        assertEquals(QuickActionTarget.CompletionForm(id), built[0].target)
        assertEquals("the snooze is still a broadcast", QuickActionTarget.Snooze(id, "nonce-1"), built[1].target)
        assertEquals(DigestPolicy.ACTION_DONE, built[0].label)
    }

    /**
     * The §12.1 carve-out (master plan N6): a `QUICK` schedule that carries a **meter rule** cannot
     * complete without its reading, so its "Done" goes through the canonical flow as an activity
     * exactly as a `FORM` one's does. A broadcast would have to fabricate a reading, and #21's whole
     * point is that the reading is the fact.
     */
    @Test
    fun aQuickScheduleWithAMeterRuleRoutesDoneThroughTheFormToo() = runTest {
        shape = QuickActionShape(groupTargeted = false, completionMode = CompletionMode.QUICK, meterRule = true)

        assertEquals(QuickActionTarget.CompletionForm(id), actions.forSchedule(id)[0].target)
    }

    /**
     * D-7's group clause: **"Open" and only "Open"**. And no nonce is issued for it — there is no
     * broadcast action to authorise, and a spendable nonce sitting behind a notification that
     * offers no write is exactly the value a forged broadcast would hunt for (invariant 56).
     */
    @Test
    fun aGroupTargetedScheduleOffersOpenAndOnlyOpenAndIssuesNoNonce() = runTest {
        shape = QuickActionShape(groupTargeted = true, completionMode = CompletionMode.QUICK, meterRule = false)

        val built = actions.forSchedule(id)

        assertEquals(listOf(DigestPolicy.ACTION_OPEN), built.map { it.label })
        assertEquals(listOf(QuickActionTarget.OpenSchedule(id)), built.map { it.target })
        assertNull("no nonce was issued for a notification with nothing to authorise", delivery.get(id))
    }

    /**
     * D-21's first and fourth rows: every build **issues and persists** a fresh value, and the new
     * one **overwrites** the old, so the previous notification's action no longer matches. The row
     * is the store — nothing here is held in a field — which is the same fact the process-death row
     * of `QuickActionReceiverTest` reads from the other end.
     */
    @Test
    fun everyNotificationBuildIssuesAFreshPersistedNonceAndTheOldOneStopsMatching() = runTest {
        val first = actions.forSchedule(id)
        assertEquals("nonce-1", delivery.get(id)?.actionNonce)
        assertEquals(Fixture.NOW, delivery.get(id)?.nonceIssuedAt)

        val second = actions.forSchedule(id)

        assertEquals("nonce-2", delivery.get(id)?.actionNonce)
        assertNotEquals(first[0].target, second[0].target)
        assertFalse(
            "the replaced notification's action is dead",
            nonces.consume(id, (first[0].target as QuickActionTarget.Complete).nonce),
        )
        assertTrue(nonces.consume(id, (second[0].target as QuickActionTarget.Complete).nonce))
    }

    /**
     * A schedule that has gone between the notification being decided on and the actions being
     * built offers **nothing** — and issues no nonce for a row whose schedule no longer exists.
     */
    @Test
    fun aScheduleThatHasGoneOffersNothing() = runTest {
        shape = null

        assertEquals(emptyList<QuickAction>(), actions.forSchedule(id))
        assertNull(delivery.get(id))
    }

    /**
     * The labels are the **ratified three** and they agree, word for word and in order, with the
     * list B06's notification build put in `ItemPost.actions` for the same schedule shape.
     *
     * Two places decide which actions a notification offers — the digest policy, which has the
     * facts, and this builder, which has the targets — so the review's real question is whether
     * they can disagree. This asserts they cannot, for both shapes that matter.
     */
    @Test
    fun theLabelsAreTheRatifiedThreeAndAgreeWithTheNotificationBuild() = runTest {
        assertEquals("Done", DigestPolicy.ACTION_DONE)
        assertEquals("Snooze 1 day", DigestPolicy.ACTION_SNOOZE_ONE_DAY)
        assertEquals("Open", DigestPolicy.ACTION_OPEN)

        shape = QuickActionShape(groupTargeted = false, completionMode = CompletionMode.QUICK, meterRule = false)
        assertEquals(labelsFromTheNotificationBuild(groupTargeted = false), actions.forSchedule(id).map { it.label })

        shape = QuickActionShape(groupTargeted = true, completionMode = CompletionMode.QUICK, meterRule = false)
        assertEquals(labelsFromTheNotificationBuild(groupTargeted = true), actions.forSchedule(id).map { it.label })
    }

    /**
     * The wiring, through the real provider: a posted notification carries its actions, and the
     * nonce the action holds is the one **left in the row** after the run — the row write and the
     * issue cannot be the wrong way round, which is the one ordering bug this whole path has.
     */
    @Test
    fun aReconcilePostsItsNotificationWithTheActionsAndTheRowKeepsTheIssuedNonce() = runTest {
        val notifications = FakeReminderNotifications()
        val provider = LocalReminderProvider(
            facts = DeliveryFactsSource { Fixture.facts(DueStatus.OVERDUE) },
            delivery = delivery,
            notifications = notifications,
            permission = GrantablePermission(),
            platform = MutablePlatformState(),
            alarm = RecordingDigestAlarm(isArmed = true),
            prefs = AppPrefs(QuickActionPrefsStore()),
            clock = clock,
            quickActions = actions,
        )

        provider.reconcile(listOf(Fixture.subject("s1", "2026-06-01")))

        val posted = notifications.postedActions.single()
        assertEquals(
            listOf(DigestPolicy.ACTION_DONE, DigestPolicy.ACTION_SNOOZE_ONE_DAY, DigestPolicy.ACTION_OPEN),
            posted.map { it.label },
        )
        val carried = (posted[0].target as QuickActionTarget.Complete).nonce
        assertEquals("the run's last word on the row is the nonce, not the stamp", carried, delivery.get(id)?.actionNonce)
        assertEquals("and the notify stamp survived it", Fixture.NOW, delivery.get(id)?.lastNotifiedAt)
    }

    private fun labelsFromTheNotificationBuild(groupTargeted: Boolean): List<String> {
        val decision = DigestPolicy.decide(
            inputs = listOf(
                DeliveryInput(
                    subject = Fixture.subject("s1", "2026-06-01"),
                    facts = Fixture.facts(DueStatus.OVERDUE, groupTargeted = groupTargeted),
                    delivery = null,
                ),
            ),
            standingTags = emptySet(),
            standingSummaryTag = null,
            nowMillis = Fixture.NOW,
        )
        return decision.posts.single().actions
    }
}

/** A `KeyValueStore` in a map: `AppPrefs` needs one and this brief's tests never read it back. */
internal class QuickActionPrefsStore : KeyValueStore {
    private val longs = mutableMapOf<String, Long>()
    private val strings = mutableMapOf<String, String>()
    override fun getLong(key: String): Long? = longs[key]
    override fun putLong(key: String, value: Long) { longs[key] = value }
    override fun getString(key: String): String? = strings[key]
    override fun putString(key: String, value: String) { strings[key] = value }
}
