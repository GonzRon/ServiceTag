package com.loosecannon.servicetag.seasonsync

/**
 * #16 (C22) — how [SeasonSyncWorker], which WorkManager constructs and the graph never does, reaches the runner:
 * assigned synchronously in `ServiceTagApp.onCreate`, before any worker's `doWork` can run (`ReminderRunDispatch`'s
 * precedent). Unassigned, the worker reports success and does nothing.
 */
object SeasonSyncDispatch {
    @Volatile
    var runner: SeasonSyncRunner? = null
}
