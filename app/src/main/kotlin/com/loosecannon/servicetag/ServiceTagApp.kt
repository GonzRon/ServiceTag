package com.loosecannon.servicetag

import android.app.Application
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.reminders.NotificationChannels

class ServiceTagApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // Idempotent: Android never rewrites an importance the user has changed, and CHANNELS'
        // importances are fixed, so calling this on every process start is safe (spec §5.5).
        NotificationChannels.ensure(this)
    }
}
