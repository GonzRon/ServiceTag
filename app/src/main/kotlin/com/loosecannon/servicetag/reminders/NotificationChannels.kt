package com.loosecannon.servicetag.reminders

import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat

/**
 * The two notification channels 1.2 creates, and nothing else (D-20 = B, invariant 53; spec
 * §5.5). A channel id is permanent once created — Android never lets an app rename or delete one
 * from under the user — so [CHANNELS] is exhaustive, not a starting point. No `supplies` and no
 * `sync_problems` channel exists anywhere in this module.
 */
object NotificationChannels {
    const val DUE = "maintenance_due"
    const val OVERDUE = "maintenance_overdue"

    /** One channel's fixed shape: an id, an importance and the two ratified system-settings strings. */
    internal data class Spec(
        val id: String,
        val importance: Int,
        val name: String,
        val description: String,
    )

    /** RATIFIED verbatim (master plan §17.1d); this brief has nothing left to draft. */
    internal val CHANNELS: List<Spec> = listOf(
        Spec(
            id = DUE,
            importance = NotificationManagerCompat.IMPORTANCE_DEFAULT,
            name = "Maintenance due",
            description = "Reminders for maintenance that is due.",
        ),
        Spec(
            id = OVERDUE,
            importance = NotificationManagerCompat.IMPORTANCE_HIGH,
            name = "Maintenance overdue",
            description = "Reminders for maintenance that is past due.",
        ),
    )

    /**
     * Creates both channels against the real `NotificationManagerCompat`, called once at process
     * start (`ServiceTagApp.onCreate`) and safe to call on every start: Android's own
     * `createNotificationChannel` is a no-op for an id that already exists and never rewrites an
     * importance the user has changed. That platform guarantee only holds if the importance this
     * brief passes never varies — [CHANNELS] is fixed, nothing here reads current state first.
     */
    fun ensure(context: Context) = ensure { spec ->
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(spec.id, spec.importance)
                .setName(spec.name)
                .setDescription(spec.description)
                .build(),
        )
    }

    /** The seam a unit test drives without a real `NotificationManager`. */
    internal fun ensure(create: (Spec) -> Unit) = CHANNELS.forEach(create)
}
