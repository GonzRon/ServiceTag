package com.loosecannon.servicetag

import android.app.Application
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.reminders.BackstopWorker
import com.loosecannon.servicetag.reminders.NotificationChannels
import com.loosecannon.servicetag.reminders.QuickActionDispatch
import com.loosecannon.servicetag.reminders.ReminderDispatch
import com.loosecannon.servicetag.reminders.ReminderRunDispatch

class ServiceTagApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // Idempotent: Android never rewrites an importance the user has changed, and CHANNELS'
        // importances are fixed, so calling this on every process start is safe (spec §5.5).
        NotificationChannels.ensure(this)
        // Synchronously, here, before this method returns (B05's ReminderDispatch KDoc): a
        // BOOT_COMPLETED broadcast is the one event the owner never sees to retry, and
        // `Application.onCreate` always completes before any component's `onReceive` or any
        // worker's `doWork`. Assigning either of these lazily or from a coroutine is how boot
        // events and backstop runs are dropped silently, with nothing in any log to show for it.
        ReminderDispatch.trigger = graph.reminderRuns
        ReminderRunDispatch.run = graph.reminderRuns
        // And the quick actions, for the same reason and then some: a notification outlives the
        // process that posted it, so a cold process started by a tapped action looks this up
        // before it does anything else (B07).
        QuickActionDispatch.handler = graph.quickActionRuns
        // Unique periodic work with KEEP, so every process start is safe and none of them restarts
        // the period (spec §5.3). The alarm is armed by the run itself, not from here: the four
        // platform receivers, the digest fire and this worker all arm it, and an arm on the launch
        // path would be a seventh site with no event behind it.
        BackstopWorker.enqueue(this)
    }
}
