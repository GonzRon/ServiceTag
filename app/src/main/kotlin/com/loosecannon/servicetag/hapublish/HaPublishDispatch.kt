package com.loosecannon.servicetag.hapublish

/**
 * #113/#114 (C32) — how [PublishWorker], which WorkManager constructs and the graph never does, reaches the graph's
 * [PublishDispatch]: assigned synchronously in `ServiceTagApp.onCreate`, beside `SeasonSyncDispatch.runner`, before any
 * worker's `doWork` can run. Unassigned, the worker reports success and does nothing.
 */
object HaPublishDispatch {
    @Volatile
    var dispatch: PublishDispatch? = null
}
