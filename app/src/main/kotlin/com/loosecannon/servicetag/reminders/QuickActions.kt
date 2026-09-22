package com.loosecannon.servicetag.reminders

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.loosecannon.servicetag.MainActivity
import com.loosecannon.servicetag.core.links.DeepLinkRoute
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.ScheduleId

/**
 * What one action on a notification is aimed at — **as a value, with no Android type in it**.
 *
 * The split is the whole design of this file. "Which actions appear, which one writes, and which
 * nonce it carries" is the part a review has to see asserted, and it is decided by [QuickActions]
 * off-device; turning a target into a `PendingIntent` is one mechanical step and is
 * [AndroidQuickActionIntents]' only job. A `PendingIntent` cannot be built in a JVM unit test at
 * all, so a builder that returned them directly would have made every one of those decisions
 * provable only on a device.
 *
 * The two **write** targets carry the notification's nonce; the two navigations do not, because
 * they write nothing and a nonce with nothing to authorise is only a value for a forged broadcast
 * to hunt for (D-21, invariant 56).
 */
sealed interface QuickActionTarget {
    val scheduleId: ScheduleId

    /** "Done" on a `QUICK` schedule with no meter rule: a broadcast to this app's own receiver. */
    data class Complete(override val scheduleId: ScheduleId, val nonce: String) : QuickActionTarget

    /** "Snooze 1 day": a broadcast, and the only thing it writes is the device-local instant. */
    data class Snooze(override val scheduleId: ScheduleId, val nonce: String) : QuickActionTarget

    /**
     * "Done" on a `FORM` schedule, **or** on a `QUICK` one carrying a meter rule: an activity, so
     * the owner supplies what the notification must never fabricate (§12.1, invariant 55).
     */
    data class CompletionForm(override val scheduleId: ScheduleId) : QuickActionTarget

    /** "Open": navigation and nothing else, on `servicetag://schedule/<uuid>` (invariant 57). */
    data class OpenSchedule(override val scheduleId: ScheduleId) : QuickActionTarget
}

/** One action on a notification: a RATIFIED label and what it is aimed at. */
data class QuickAction(val label: String, val target: QuickActionTarget)

/**
 * What a quick action needs to know about a schedule, and **nothing else**.
 *
 * Three facts decide the whole contract of §12.1, so three facts is what this carries — the same
 * reason [ScheduleStateReader] next door is one read method rather than a repository: a seam this
 * narrow cannot be the place a delivery path starts reaching into the domain.
 */
data class QuickActionShape(
    /** D-7: a group-targeted schedule's notification offers "Open" only. */
    val groupTargeted: Boolean,
    val completionMode: CompletionMode,
    /** Whether a meter rule is attached, which is §12.1's carve-out on a `QUICK` "Done". */
    val meterRule: Boolean,
)

fun interface QuickActionShapeSource {
    suspend fun shapeOf(id: ScheduleId): QuickActionShape?
}

/**
 * The actions one notification carries, and the nonce that protects them.
 *
 * Called by the local provider as each notification is posted, which is why [forSchedule] is the
 * point at which a fresh nonce is **issued and persisted**: D-21's first row is "a notification is
 * built for a schedule", and issuing anywhere else would leave a spendable value behind a
 * notification that was never posted.
 *
 * It decides **which** actions appear from the same three facts B06's digest policy decides
 * `ItemPost.actions` from, and `QuickActionsTest` asserts the two answers agree label for label —
 * two places have the facts and the targets respectively, and the review's real question is whether
 * they can disagree.
 */
class QuickActions(
    private val shapes: QuickActionShapeSource,
    private val nonces: NonceStore,
) {
    /**
     * Issues the notification's nonce as a side effect, and **only** when the notification it is
     * for carries a broadcast action. A schedule that has gone between the digest policy deciding
     * to post it and this call offers nothing: its notification is about to be cancelled anyway,
     * and issuing a nonce against a row whose schedule no longer exists would outlive it.
     */
    suspend fun forSchedule(id: ScheduleId): List<QuickAction> {
        val shape = shapes.shapeOf(id) ?: return emptyList()
        // D-7: "Done" on a group either completes nothing or falsely completes every member, so the
        // notification opens the checklist and offers nothing that writes — and therefore needs no
        // nonce at all.
        if (shape.groupTargeted) {
            return listOf(QuickAction(DigestPolicy.ACTION_OPEN, QuickActionTarget.OpenSchedule(id)))
        }
        val nonce = nonces.issue(id)
        // §12.1 and its carve-out (master plan N6): a form's required fields and a meter's reading
        // are both facts only the owner has, so "Done" opens the canonical flow rather than writing
        // a completion the notification would have had to invent half of.
        val done = if (shape.completionMode == CompletionMode.FORM || shape.meterRule) {
            QuickActionTarget.CompletionForm(id)
        } else {
            QuickActionTarget.Complete(id, nonce)
        }
        return listOf(
            QuickAction(DigestPolicy.ACTION_DONE, done),
            QuickAction(DigestPolicy.ACTION_SNOOZE_ONE_DAY, QuickActionTarget.Snooze(id, nonce)),
            QuickAction(DigestPolicy.ACTION_OPEN, QuickActionTarget.OpenSchedule(id)),
        )
    }
}

/** The one Android-shaped step: a target becomes something the notification shade can fire. */
fun interface QuickActionIntents {
    fun pendingIntentFor(target: QuickActionTarget): PendingIntent
}

/**
 * The four `PendingIntent`s, and the two rules that make them safe.
 *
 * **Every one is `FLAG_IMMUTABLE`, with the flag on the same physical line as the call** (invariant
 * 54, master plan §16's line-based release grep, `DigestAlarmTest`'s own scan). A mutable one would
 * let another app rewrite the schedule id the action acts on, which is the whole of what the nonce
 * would then be protecting the wrong row from.
 *
 * **A receiver never starts an activity** (API 31+ trampoline rule, invariant 55). The two actions
 * that open a screen are `getActivity` **directly**; they do not broadcast to a receiver that then
 * launches something, because Android 12+ silently drops such a launch and the action appears to do
 * nothing at all. That is why this file builds `Intent`s and [QuickActionReceiver]'s does not.
 *
 * `FLAG_UPDATE_CURRENT` rides beside `FLAG_IMMUTABLE` on the two broadcasts, and the schedule id
 * travels in the intent's **data** rather than only in an extra. Both are for one reason:
 * `PendingIntent` identity is `Intent.filterEquals`, which ignores extras. Two schedules whose
 * intents differed only by an extra would be the **same** pending intent, and a nonce re-issued for
 * one notification would be delivered for another — so the id distinguishes them by data, and
 * `UPDATE_CURRENT` guarantees the extras the shade fires are this build's and not a stale build's.
 */
class AndroidQuickActionIntents(private val context: Context) : QuickActionIntents {

    override fun pendingIntentFor(target: QuickActionTarget): PendingIntent {
        val app = context.applicationContext
        return when (target) {
            is QuickActionTarget.Complete -> broadcast(
                app,
                QuickActionReceiver.ACTION_COMPLETE,
                REQUEST_COMPLETE,
                target.scheduleId,
                target.nonce,
            )
            is QuickActionTarget.Snooze -> broadcast(
                app,
                QuickActionReceiver.ACTION_SNOOZE,
                REQUEST_SNOOZE,
                target.scheduleId,
                target.nonce,
            )
            // Until B14's completion form has a route of its own, "Done" on a form or meter
            // schedule opens the schedule, which is where that flow will live; what this brief
            // fixes is that it is an **activity** and that it writes nothing on the way (#11 AC 2).
            is QuickActionTarget.CompletionForm -> activity(app, REQUEST_COMPLETION_FORM, target.scheduleId)
            is QuickActionTarget.OpenSchedule -> activity(app, REQUEST_OPEN, target.scheduleId)
        }
    }

    private fun broadcast(
        app: Context,
        action: String,
        requestCode: Int,
        id: ScheduleId,
        nonce: String,
    ): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT
        val target = Intent(app, QuickActionReceiver::class.java)
            .setAction(action)
            .setData(scheduleUri(id))
            .putExtra(QuickActionReceiver.EXTRA_NONCE, nonce)
        return PendingIntent.getBroadcast(app, requestCode, target, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * The same `servicetag://schedule/<uuid>` the "Open" action navigates with, aimed explicitly at
     * this app's single activity: an explicit component means no other app can answer it, and
     * `MainActivity` turns it into a route and does nothing else (invariant 57).
     */
    private fun activity(app: Context, requestCode: Int, id: ScheduleId): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT
        val target = Intent(app, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(scheduleUri(id))
        return PendingIntent.getActivity(app, requestCode, target, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * Built rather than parsed (fix round 1, nit 6). Production ids are canonical uuids, so
     * `Uri.parse` on an interpolated string is safe today — but an id from an imported archive, or
     * any id carrying a character a URI reserves, would produce a link `DeepLinkRoute.parse` then
     * rejects as malformed, and the owner's "Open" would raise the not-here message instead of
     * navigating. `appendPath` encodes the segment, which removes the class rather than the case.
     */
    private fun scheduleUri(id: ScheduleId): Uri = Uri.Builder()
        .scheme(DeepLinkRoute.SCHEME)
        .authority(SCHEDULE_HOST)
        .appendPath(id.value)
        .build()

    internal companion object {
        /**
         * The deep-link host, named once. It is the parser's own word and the manifest's; spelling
         * it a second time here is what would let the three drift apart.
         */
        const val SCHEDULE_HOST = "schedule"

        /**
         * One request code per action **kind**, not per schedule: the id is in the intent's data, so
         * `filterEquals` already tells two schedules' actions apart, and per-schedule codes would
         * be an unbounded set of registry entries for no gain.
         */
        const val REQUEST_COMPLETE = 2301
        const val REQUEST_SNOOZE = 2302
        const val REQUEST_COMPLETION_FORM = 2303
        const val REQUEST_OPEN = 2304
    }
}
