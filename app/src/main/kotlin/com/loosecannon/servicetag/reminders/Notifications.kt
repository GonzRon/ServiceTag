package com.loosecannon.servicetag.reminders

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.ui.theme.ServiceTagLightSemanticColors

/**
 * Posting, clearing and — the interesting one — **reading back** what is currently showing.
 *
 * [standingItems] is why this is a seam and not a helper. The digest policy decides what to post by
 * comparing the subjects it was handed against what the shade is already showing, so the provider
 * keeps no record of its own: its whole projection is the posted notifications plus the armed
 * alarm, exactly as D4 §10 and invariant 44 say. A fake implements this for the JVM tests; the real
 * `NotificationManager` behind it is what the connected class proves.
 */
interface ReminderNotifications {
    /** The tags of this provider's per-item notifications that are showing right now. */
    fun standingItems(): Set<String>

    /** The tag of the standing summary, or null if none is showing. */
    fun standingSummary(): String?

    fun postItem(post: ItemPost)
    fun postSummary(summary: SummaryPost)
    fun cancelItem(tag: String)
    fun cancelSummary()
}

/**
 * The Android-backed [ReminderNotifications].
 *
 * Every notification carries its **ratified status word** as sub-text and a status-specific icon
 * beside it, so the DUE/OVERDUE distinction survives with colour removed — the one surface where
 * an in-app grayscale proof cannot reach, because the shade draws it. The accent colour is read
 * from the app's **semantic tokens** (`ui/theme/SemanticColors.kt`) and never written here: an
 * improvised hex in this file would be the one place in the app where operational meaning came
 * from a raw colour.
 *
 * The **light** token set is used for both themes. `setColor` tints a small monochrome icon against
 * the system shade's own background, which no app controls and which is not this app's light or
 * dark surface; picking per-theme accents here would be guessing at a surface we cannot see.
 *
 * It carries **no content intent and no actions**: the four quick actions, their `FLAG_IMMUTABLE`
 * `PendingIntent`s and the nonce routing are B07's, and [ItemPost.actions] is the ratified label
 * list this brief hands it. Until B07 lands a posted notification is an announcement and nothing
 * more, which is the honest state of the feature at this brief's tip.
 */
class AndroidReminderNotifications(private val context: Context) : ReminderNotifications {

    private val manager = NotificationManagerCompat.from(context)

    override fun standingItems(): Set<String> = standing(ITEM_ID)

    override fun standingSummary(): String? = standing(SUMMARY_ID).firstOrNull()

    override fun postItem(post: ItemPost) {
        val builder = NotificationCompat.Builder(context, post.channelId)
            .setSmallIcon(iconFor(post))
            .setColor(accentFor(post).toArgb())
            .setContentTitle(post.title)
            .setSubText(post.statusWord)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(false)
            .setOnlyAlertOnce(false)
        // A body is only set when there is one. The empty case is unreachable today — a DUE or
        // OVERDUE subject carries either a date or a crossed meter threshold — and that is exactly
        // why it must not ship a blank sentence if it ever becomes reachable: a title-only
        // notification is a worse answer than a missing line (fix round 1, nit 13).
        if (post.body.isNotEmpty()) {
            builder.setContentText(post.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(post.body))
        }
        notify(post.tag, ITEM_ID, builder)
    }

    override fun postSummary(summary: SummaryPost) {
        val builder = NotificationCompat.Builder(context, NotificationChannels.DUE)
            .setSmallIcon(R.drawable.ic_notifications_active)
            .setColor(ServiceTagLightSemanticColors.due.foreground.toArgb())
            .setContentTitle(summary.title)
            .setContentText(summary.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary.body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(false)
        notify(summary.tag, SUMMARY_ID, builder)
    }

    override fun cancelItem(tag: String) = manager.cancel(tag, ITEM_ID)

    override fun cancelSummary() {
        standing(SUMMARY_ID).forEach { manager.cancel(it, SUMMARY_ID) }
    }

    /**
     * A blocked or muted channel makes `notify` a no-op rather than an error, and the platform
     * throws nothing for it; the catch is for the `SecurityException` an OEM can raise on a
     * restricted process. A failure to post is never a failure of the run — the health finding is
     * what tells the owner, per D-22.
     */
    private fun notify(tag: String, id: Int, builder: NotificationCompat.Builder) {
        try {
            manager.notify(tag, id, builder.build())
        } catch (e: SecurityException) {
            // Nothing to do here: `health()` reports the reason and the next run tries again.
        }
    }

    private fun standing(id: Int): Set<String> = try {
        manager.activeNotifications.filter { it.id == id }.mapNotNull { it.tag }.toSet()
    } catch (e: SecurityException) {
        emptySet()
    }

    /**
     * The icon is the second, non-colour carrier of the distinction: a counter for a crossed meter
     * threshold, an active bell for something already past due, a clock for something due today.
     * Chosen from [ItemPost.meter] and the status word — never by matching the body against a
     * ratified sentence, which would break silently if §17.1e were reworded.
     */
    private fun iconFor(post: ItemPost): Int = when {
        post.meter -> R.drawable.ic_speed
        post.statusWord == DigestPolicy.WORD_OVERDUE -> R.drawable.ic_notifications_active
        else -> R.drawable.ic_schedule
    }

    private fun accentFor(post: ItemPost) =
        if (post.statusWord == DigestPolicy.WORD_OVERDUE) {
            ServiceTagLightSemanticColors.overdue.foreground
        } else {
            ServiceTagLightSemanticColors.due.foreground
        }

    internal companion object {
        /** One id for every per-item notification; the tag is what distinguishes them. */
        const val ITEM_ID = 2201

        /** One id for the one summary. */
        const val SUMMARY_ID = 2200
    }
}
